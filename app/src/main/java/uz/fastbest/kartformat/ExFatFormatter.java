package uz.fastbest.kartformat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static uz.fastbest.kartformat.DiskFormatter.SECTOR;
import static uz.fastbest.kartformat.DiskFormatter.putInt;
import static uz.fastbest.kartformat.DiskFormatter.putLong;
import static uz.fastbest.kartformat.DiskFormatter.putShort;

/** Bir bölümü exFAT olarak biçimlendirir (Microsoft exFAT belirtimi, sürüm 1.00). */
public final class ExFatFormatter {

    private static final int FAT_OFFSET = 2048;   // birim başından, 1 MiB hizalı
    private static final int ALIGN = 2048;

    private ExFatFormatter() {
    }

    /** Microsoft'un varsayılan küme boyutları. */
    static int sectorsPerCluster(long volSectors) {
        long bytes = volSectors * SECTOR;
        if (bytes <= 256L * 1024 * 1024) return 8;          // 4 KB
        if (bytes <= 32L * 1024 * 1024 * 1024) return 64;   // 32 KB
        return 256;                                          // 128 KB
    }

    private static final class Layout {
        int spc;
        int spcShift;
        long fatLength;
        long heapOffset;
        long clusterCount;
        long bitmapBytes;
        long bitmapClusters;
        byte[] upcase;
        long upcaseClusters;
        long bitmapCluster;
        long upcaseCluster;
        long rootCluster;
        long usedClusters;
    }

    private static Layout layout(long volSectors) throws IOException {
        Layout l = new Layout();
        l.spc = sectorsPerCluster(volSectors);
        l.spcShift = Integer.numberOfTrailingZeros(l.spc);
        long estimate = (volSectors - FAT_OFFSET) / l.spc;
        l.fatLength = ceilDiv((estimate + 2) * 4, SECTOR);
        l.heapOffset = alignUp(FAT_OFFSET + l.fatLength, ALIGN);
        l.clusterCount = (volSectors - l.heapOffset) / l.spc;
        if (l.clusterCount < 16) throw new IOException("Kart exFAT için çok küçük");

        long clusterBytes = (long) l.spc * SECTOR;
        l.bitmapBytes = ceilDiv(l.clusterCount, 8);
        l.bitmapClusters = ceilDiv(l.bitmapBytes, clusterBytes);
        l.upcase = upcaseTable();
        l.upcaseClusters = ceilDiv(l.upcase.length, clusterBytes);
        l.bitmapCluster = 2;
        l.upcaseCluster = l.bitmapCluster + l.bitmapClusters;
        l.rootCluster = l.upcaseCluster + l.upcaseClusters;
        l.usedClusters = l.bitmapClusters + l.upcaseClusters + 1;
        return l;
    }

    static long estimateBlocks(long volSectors) {
        try {
            Layout l = layout(volSectors);
            return l.heapOffset + l.usedClusters * l.spc;
        } catch (IOException e) {
            return 1;
        }
    }

    static void formatVolume(BlockDevice dev, long start, long volSectors, String label,
                             DiskFormatter.Tracker t) throws IOException {
        Layout l = layout(volSectors);
        long clusterSectors = l.spc;

        // 1) Önyükleme bölgeleri (ana + yedek) ve FAT'e kadar olan boşluk
        byte[] region = bootRegion(start, volSectors, l);
        byte[] head = new byte[FAT_OFFSET * SECTOR];
        System.arraycopy(region, 0, head, 0, region.length);
        System.arraycopy(region, 0, head, region.length, region.length);
        DiskFormatter.writeData(dev, start, head, FAT_OFFSET, t);

        // 2) FAT: 0 ve 1 ayrılmış, ardından bitmap, büyük harf tablosu ve kök dizin zincirleri
        long lastUsed = l.rootCluster;
        byte[] fat = new byte[(int) ((lastUsed + 1) * 4)];
        putInt(fat, 0, 0xFFFFFFF8);
        putInt(fat, 4, 0xFFFFFFFF);
        chain(fat, l.bitmapCluster, l.bitmapClusters);
        chain(fat, l.upcaseCluster, l.upcaseClusters);
        chain(fat, l.rootCluster, 1);
        DiskFormatter.writeData(dev, start + FAT_OFFSET, fat, l.heapOffset - FAT_OFFSET, t);

        // 3) Küme yığını
        long heap = start + l.heapOffset;
        byte[] bitmap = new byte[(int) Math.min(l.bitmapBytes,
                ceilDiv(l.usedClusters, 8))];
        for (long c = 0; c < l.usedClusters; c++) {
            bitmap[(int) (c >>> 3)] |= (byte) (1 << (c & 7));
        }
        DiskFormatter.writeData(dev, heap + (l.bitmapCluster - 2) * clusterSectors, bitmap,
                l.bitmapClusters * clusterSectors, t);
        DiskFormatter.writeData(dev, heap + (l.upcaseCluster - 2) * clusterSectors, l.upcase,
                l.upcaseClusters * clusterSectors, t);
        DiskFormatter.writeData(dev, heap + (l.rootCluster - 2) * clusterSectors,
                rootDirectory(l, label), clusterSectors, t);
    }

    private static void chain(byte[] fat, long first, long count) {
        for (long i = 0; i < count; i++) {
            long c = first + i;
            putInt(fat, (int) (c * 4), i == count - 1 ? 0xFFFFFFFF : (int) (c + 1));
        }
    }

    private static byte[] bootRegion(long partOffset, long volSectors, Layout l) {
        byte[] r = new byte[12 * SECTOR];
        // Ana önyükleme sektörü
        r[0] = (byte) 0xEB;
        r[1] = 0x76;
        r[2] = (byte) 0x90;
        System.arraycopy("EXFAT   ".getBytes(StandardCharsets.US_ASCII), 0, r, 3, 8);
        putLong(r, 64, partOffset);
        putLong(r, 72, volSectors);
        putInt(r, 80, FAT_OFFSET);
        putInt(r, 84, (int) l.fatLength);
        putInt(r, 88, (int) l.heapOffset);
        putInt(r, 92, (int) l.clusterCount);
        putInt(r, 96, (int) l.rootCluster);
        putInt(r, 100, new SecureRandom().nextInt());
        putShort(r, 104, 0x0100);      // sürüm 1.00
        putShort(r, 106, 0);           // birim bayrakları
        r[108] = 9;                    // 512 bayt sektör
        r[109] = (byte) l.spcShift;
        r[110] = 1;                    // FAT sayısı
        r[111] = (byte) 0x80;
        r[112] = (byte) (l.usedClusters * 100 / l.clusterCount);
        for (int i = 120; i < 510; i++) r[i] = (byte) 0xF4; // önyükleme kodu yerine "hlt"
        r[510] = 0x55;
        r[511] = (byte) 0xAA;
        // Genişletilmiş önyükleme sektörleri (1-8)
        for (int s = 1; s <= 8; s++) {
            r[s * SECTOR + 510] = 0x55;
            r[s * SECTOR + 511] = (byte) 0xAA;
        }
        // 9: OEM parametreleri, 10: ayrılmış (sıfır). 11: sağlama toplamı
        int sum = 0;
        for (int i = 0; i < 11 * SECTOR; i++) {
            if (i == 106 || i == 107 || i == 112) continue;
            sum = ((sum & 1) != 0 ? 0x80000000 : 0) + (sum >>> 1) + (r[i] & 0xFF);
        }
        for (int i = 0; i < SECTOR; i += 4) putInt(r, 11 * SECTOR + i, sum);
        return r;
    }

    private static byte[] rootDirectory(Layout l, String label) {
        byte[] d = new byte[l.spc * SECTOR];
        int off = 0;
        String name = cleanLabel(label);
        if (!name.isEmpty()) {
            d[off] = (byte) 0x83;
            d[off + 1] = (byte) name.length();
            byte[] u = name.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(u, 0, d, off + 2, u.length);
            off += 32;
        }
        // Ayırma bitmap girişi
        d[off] = (byte) 0x81;
        putInt(d, off + 20, (int) l.bitmapCluster);
        putLong(d, off + 24, l.bitmapBytes);
        off += 32;
        // Büyük harf tablosu girişi
        d[off] = (byte) 0x82;
        putInt(d, off + 4, tableChecksum(l.upcase));
        putInt(d, off + 20, (int) l.upcaseCluster);
        putLong(d, off + 24, l.upcase.length);
        return d;
    }

    /** exFAT etiketi: en çok 11 karakter, \/:*?"<>| ve denetim karakterleri olmadan. */
    static String cleanLabel(String label) {
        if (label == null) return "";
        StringBuilder sb = new StringBuilder();
        String s = label.trim();
        for (int i = 0; i < s.length() && sb.length() < 11; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    static int tableChecksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum = ((sum & 1) != 0 ? 0x80000000 : 0) + (sum >>> 1) + (b & 0xFF);
        return sum;
    }

    /** Sıkıştırılmış büyük harf tablosu: değişmeyen karakter dizileri 0xFFFF + uzunluk ile. */
    static byte[] upcaseTable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i <= 0xFFFF) {
            char u = upper(i);
            if (u == i) {
                int j = i;
                while (j <= 0xFFFF && upper(j) == j && j - i < 0xFFFF) j++;
                writeChar(out, 0xFFFF);
                writeChar(out, j - i);
                i = j;
            } else {
                writeChar(out, u);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static char upper(int c) {
        if (c >= 0xD800 && c <= 0xDFFF) return (char) c;
        char u = Character.toUpperCase((char) c);
        return u == 0xFFFF ? (char) c : u;
    }

    private static void writeChar(ByteArrayOutputStream out, int c) {
        out.write(c & 0xFF);
        out.write((c >>> 8) & 0xFF);
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static long alignUp(long v, long a) {
        return (v + a - 1) / a * a;
    }
}

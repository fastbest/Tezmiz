package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static uz.fastbest.kartformat.DiskFormatter.SECTOR;
import static uz.fastbest.kartformat.DiskFormatter.putInt;
import static uz.fastbest.kartformat.DiskFormatter.putShort;

/** Bir bölümü FAT32 olarak biçimlendirir. */
public final class Fat32Formatter {

    public interface Progress {
        void onProgress(long doneBlocks, long totalBlocks);
    }

    private static final int RESERVED = 32;
    private static final int NUM_FATS = 2;

    private Fat32Formatter() {
    }

    /** Eski kullanım: MBR + tek FAT32 bölümü. */
    public static void format(BlockDevice dev, String label, Progress progress)
            throws IOException {
        DiskFormatter.format(dev, DiskFormatter.Fs.FAT32, false, label, progress);
    }

    static int sectorsPerCluster(long partSectors) {
        long mb = partSectors / 2048;
        if (mb <= 512) return 1;              // 512 bayt
        if (mb <= 8L * 1024) return 8;        // 4 KB
        if (mb <= 16L * 1024) return 16;      // 8 KB
        if (mb <= 32L * 1024) return 32;      // 16 KB
        if (mb <= 512L * 1024) return 64;     // 32 KB
        return 128;                           // 64 KB
    }

    static long fatSize(long partSectors, int spc) {
        // Microsoft'un FAT boyutu formülü
        long tmp1 = partSectors - RESERVED;
        long tmp2 = (256L * spc + NUM_FATS) / 2;
        return (tmp1 + tmp2 - 1) / tmp2;
    }

    static long estimateBlocks(long partSectors) {
        int spc = sectorsPerCluster(partSectors);
        return RESERVED + NUM_FATS * fatSize(partSectors, spc) + spc;
    }

    static void formatVolume(BlockDevice dev, long start, long partSectors, String label,
                             DiskFormatter.Tracker t) throws IOException {
        int spc = sectorsPerCluster(partSectors);
        long fatSize = fatSize(partSectors, spc);
        long dataSectors = partSectors - RESERVED - NUM_FATS * fatSize;
        long clusters = dataSectors / spc;
        if (clusters < 65525) throw new IOException("Kart FAT32 için çok küçük");

        int volumeId = (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
        byte[] label11 = makeLabel(label);

        // Ayrılmış bölge: önyükleme sektörü, FSInfo ve yedekleri
        byte[] reserved = new byte[RESERVED * SECTOR];
        byte[] boot = bootSector(start, partSectors, spc, fatSize, volumeId, label11);
        byte[] fsInfo = fsInfo(clusters - 1);
        byte[] third = new byte[SECTOR];
        third[510] = 0x55;
        third[511] = (byte) 0xAA;
        for (int copy = 0; copy <= 6; copy += 6) {
            System.arraycopy(boot, 0, reserved, copy * SECTOR, SECTOR);
            System.arraycopy(fsInfo, 0, reserved, (copy + 1) * SECTOR, SECTOR);
            System.arraycopy(third, 0, reserved, (copy + 2) * SECTOR, SECTOR);
        }
        DiskFormatter.writeData(dev, start, reserved, RESERVED, t);

        // FAT tabloları
        long fatStart = start + RESERVED;
        byte[] head = new byte[12];
        putInt(head, 0, 0x0FFFFFF8);   // ortam türü
        putInt(head, 4, 0x0FFFFFFF);   // temiz kapatma bayrakları
        putInt(head, 8, 0x0FFFFFFF);   // kök dizin: zincir sonu
        for (int f = 0; f < NUM_FATS; f++) {
            DiskFormatter.writeData(dev, fatStart + f * fatSize, head, fatSize, t);
        }

        // Kök dizin kümesi
        byte[] root = new byte[spc * SECTOR];
        if (label11 != null) {
            System.arraycopy(label11, 0, root, 0, 11);
            root[11] = 0x08; // birim etiketi
        }
        DiskFormatter.writeData(dev, fatStart + NUM_FATS * fatSize, root, spc, t);
    }

    private static byte[] bootSector(long hidden, long partSectors, int spc, long fatSize,
                                     int volumeId, byte[] label11) {
        byte[] b = new byte[SECTOR];
        b[0] = (byte) 0xEB;
        b[1] = 0x58;
        b[2] = (byte) 0x90;
        put(b, 3, "MSDOS5.0");
        putShort(b, 11, SECTOR);
        b[13] = (byte) spc;
        putShort(b, 14, RESERVED);
        b[16] = NUM_FATS;
        b[21] = (byte) 0xF8;           // ortam türü
        putShort(b, 24, 63);           // iz başına sektör
        putShort(b, 26, 255);          // kafa sayısı
        putInt(b, 28, (int) hidden);   // gizli sektörler
        putInt(b, 32, (int) partSectors);
        putInt(b, 36, (int) fatSize);
        putInt(b, 44, 2);              // kök dizin kümesi
        putShort(b, 48, 1);            // FSInfo sektörü
        putShort(b, 50, 6);            // yedek önyükleme sektörü
        b[64] = (byte) 0x80;           // sürücü numarası
        b[66] = 0x29;                  // genişletilmiş imza
        putInt(b, 67, volumeId);
        byte[] lab = label11 != null ? label11
                : "NO NAME    ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(lab, 0, b, 71, 11);
        put(b, 82, "FAT32   ");
        b[510] = 0x55;
        b[511] = (byte) 0xAA;
        return b;
    }

    private static byte[] fsInfo(long freeClusters) {
        byte[] b = new byte[SECTOR];
        putInt(b, 0, 0x41615252);
        putInt(b, 484, 0x61417272);
        putInt(b, 488, (int) freeClusters);
        putInt(b, 492, 3);             // sonraki boş küme
        b[510] = 0x55;
        b[511] = (byte) 0xAA;
        return b;
    }

    /** Etiketi FAT kurallarına uygun 11 baytlık hale getirir; boşsa null döner. */
    static byte[] makeLabel(String label) {
        String clean = cleanLabel(label);
        if (clean.isEmpty()) return null;
        byte[] out = new byte[11];
        Arrays.fill(out, (byte) ' ');
        byte[] src = clean.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    /** Etiketten izin verilmeyen karakterleri atar (A-Z, 0-9, boşluk, _ ve -). */
    static String cleanLabel(String label) {
        if (label == null) return "";
        String s = label.trim().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 11; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ' ' || c == '_' || c == '-';
            if (ok) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static void put(byte[] b, int off, String s) {
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, off, src.length);
    }
}

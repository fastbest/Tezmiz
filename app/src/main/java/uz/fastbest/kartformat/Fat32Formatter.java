package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Aygıta MBR bölüm tablosu ve tek bir FAT32 bölümü yazar. */
public final class Fat32Formatter {

    public interface Progress {
        void onProgress(long doneBlocks, long totalBlocks);
    }

    private static final int SECTOR = 512;
    private static final int PART_START = 2048;      // 1 MiB hizalama
    private static final int RESERVED = 32;
    private static final int NUM_FATS = 2;
    private static final int CHUNK = 128;            // tek seferde 64 KB
    private static final long MIN_SECTORS = 262144;  // 128 MB

    private final BlockDevice dev;
    private final Progress progress;
    private long done;
    private long total;

    private Fat32Formatter(BlockDevice dev, Progress progress) {
        this.dev = dev;
        this.progress = progress;
    }

    public static void format(BlockDevice dev, String label, Progress progress)
            throws IOException {
        new Fat32Formatter(dev, progress).run(label);
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

    private void run(String label) throws IOException {
        if (dev.blockSize() != SECTOR) {
            throw new IOException("Desteklenmeyen sektör boyutu: " + dev.blockSize());
        }
        long totalSectors = dev.blockCount();
        if (totalSectors < MIN_SECTORS) {
            throw new IOException("Kart çok küçük (en az 128 MB olmalı)");
        }
        if (totalSectors > 0xFFFFFFFFL) {
            throw new IOException("2 TB üstü kartlar desteklenmiyor");
        }

        long partSectors = totalSectors - PART_START;
        int spc = sectorsPerCluster(partSectors);

        // Microsoft'un FAT boyutu formülü
        long tmp1 = partSectors - RESERVED;
        long tmp2 = (256L * spc + NUM_FATS) / 2;
        long fatSize = (tmp1 + tmp2 - 1) / tmp2;

        long dataSectors = partSectors - RESERVED - NUM_FATS * fatSize;
        long clusters = dataSectors / spc;
        if (clusters < 65525) {
            throw new IOException("Kart FAT32 için çok küçük");
        }

        int volumeId = (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
        byte[] label11 = makeLabel(label);

        long tail = Math.min(64, partSectors);
        total = PART_START + RESERVED + NUM_FATS * fatSize + spc + tail;
        done = 0;

        // 1) Kartın başını temizle (eski MBR/GPT ve dosya sistemi izleri) ve MBR yaz
        byte[] head = new byte[PART_START * SECTOR];
        writeMbr(head, partSectors);
        writeRange(0, head, PART_START);

        // 2) Ayrılmış bölge: önyükleme sektörü, FSInfo ve yedekleri
        byte[] reserved = new byte[RESERVED * SECTOR];
        byte[] boot = bootSector(partSectors, spc, fatSize, volumeId, label11);
        byte[] fsInfo = fsInfo(clusters - 1);
        byte[] third = new byte[SECTOR];
        third[510] = 0x55;
        third[511] = (byte) 0xAA;
        for (int copy = 0; copy <= 6; copy += 6) {
            System.arraycopy(boot, 0, reserved, copy * SECTOR, SECTOR);
            System.arraycopy(fsInfo, 0, reserved, (copy + 1) * SECTOR, SECTOR);
            System.arraycopy(third, 0, reserved, (copy + 2) * SECTOR, SECTOR);
        }
        writeRange(PART_START, reserved, RESERVED);

        // 3) FAT tabloları
        long fatStart = PART_START + RESERVED;
        for (int f = 0; f < NUM_FATS; f++) {
            writeFat(fatStart + f * fatSize, fatSize);
        }

        // 4) Kök dizin kümesi
        byte[] root = new byte[spc * SECTOR];
        if (label11 != null) {
            System.arraycopy(label11, 0, root, 0, 11);
            root[11] = 0x08; // birim etiketi
        }
        long dataStart = fatStart + NUM_FATS * fatSize;
        writeRange(dataStart, root, spc);

        // 5) Kartın sonundaki olası yedek GPT'yi temizle
        long tailStart = totalSectors - tail;
        if (tailStart >= dataStart + spc) {
            writeRange(tailStart, new byte[(int) tail * SECTOR], (int) tail);
        } else {
            done += tail;
        }

        dev.flush();
        report(total);
    }

    private void writeFat(long start, long fatSize) throws IOException {
        byte[] chunk = new byte[CHUNK * SECTOR];
        putInt(chunk, 0, 0x0FFFFFF8);   // ortam türü
        putInt(chunk, 4, 0x0FFFFFFF);   // temiz kapatma bayrakları
        putInt(chunk, 8, 0x0FFFFFFF);   // kök dizin: zincir sonu
        long pos = 0;
        boolean first = true;
        while (pos < fatSize) {
            int n = (int) Math.min(CHUNK, fatSize - pos);
            dev.write(start + pos, chunk, n);
            pos += n;
            done += n;
            report(done);
            if (first) {
                Arrays.fill(chunk, 0, 12, (byte) 0);
                first = false;
            }
        }
    }

    private void writeRange(long lba, byte[] data, int blocks) throws IOException {
        byte[] chunk = new byte[CHUNK * SECTOR];
        int pos = 0;
        while (pos < blocks) {
            int n = Math.min(CHUNK, blocks - pos);
            System.arraycopy(data, pos * SECTOR, chunk, 0, n * SECTOR);
            dev.write(lba + pos, chunk, n);
            pos += n;
            done += n;
            report(done);
        }
    }

    private void report(long value) {
        if (progress != null) progress.onProgress(Math.min(value, total), total);
    }

    private static void writeMbr(byte[] mbr, long partSectors) {
        int e = 446;
        mbr[e] = 0x00;                 // önyüklenebilir değil
        mbr[e + 1] = (byte) 0xFE;      // CHS başlangıç (LBA kullanılır)
        mbr[e + 2] = (byte) 0xFF;
        mbr[e + 3] = (byte) 0xFF;
        mbr[e + 4] = 0x0C;             // FAT32 (LBA)
        mbr[e + 5] = (byte) 0xFE;      // CHS bitiş
        mbr[e + 6] = (byte) 0xFF;
        mbr[e + 7] = (byte) 0xFF;
        putInt(mbr, e + 8, PART_START);
        putInt(mbr, e + 12, (int) partSectors);
        int sig = (int) (System.nanoTime() & 0xFFFFFFFFL);
        putInt(mbr, 440, sig);         // disk imzası
        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;
    }

    private static byte[] bootSector(long partSectors, int spc, long fatSize,
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
        putShort(b, 17, 0);            // kök dizin girişi (FAT32'de 0)
        putShort(b, 19, 0);            // 16 bit toplam sektör
        b[21] = (byte) 0xF8;           // ortam türü
        putShort(b, 22, 0);            // 16 bit FAT boyutu
        putShort(b, 24, 63);           // iz başına sektör
        putShort(b, 26, 255);          // kafa sayısı
        putInt(b, 28, PART_START);     // gizli sektörler
        putInt(b, 32, (int) partSectors);
        putInt(b, 36, (int) fatSize);
        putShort(b, 40, 0);            // bayraklar
        putShort(b, 42, 0);            // sürüm
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
        if (label == null) return null;
        String s = label.trim().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 11; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ' ' || c == '_' || c == '-';
            if (ok) sb.append(c);
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) return null;
        byte[] out = new byte[11];
        Arrays.fill(out, (byte) ' ');
        byte[] src = clean.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static void put(byte[] b, int off, String s) {
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, off, src.length);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}

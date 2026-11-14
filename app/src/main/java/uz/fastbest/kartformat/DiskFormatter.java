package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Karta bölüm tablosu (MBR ya da GPT) ve tek bir bölüm yazar, bölümü FAT32 ya da exFAT
 * olarak biçimlendirir.
 */
public final class DiskFormatter {

    public enum Fs {
        FAT32, EXFAT
    }

    static final int SECTOR = 512;
    static final int PART_START = 2048;      // 1 MiB hizalama
    static final int CHUNK = 128;            // tek seferde 64 KB
    private static final long MIN_SECTORS = 262144;  // 128 MB
    private static final int GPT_ENTRIES = 128;
    private static final int GPT_ENTRY_SIZE = 128;
    private static final int GPT_ARRAY_SECTORS = GPT_ENTRIES * GPT_ENTRY_SIZE / SECTOR; // 32

    /** Microsoft temel veri bölümü (FAT32, exFAT, NTFS için). */
    private static final byte[] BASIC_DATA_GUID = {
            (byte) 0xA2, (byte) 0xA0, (byte) 0xD0, (byte) 0xEB, (byte) 0xE5, (byte) 0xB9,
            0x33, 0x44, (byte) 0x87, (byte) 0xC0, 0x68, (byte) 0xB6, (byte) 0xB7, 0x26,
            (byte) 0x99, (byte) 0xC7
    };

    /** Birden çok aşamalı işlemin toplam ilerlemesini tutar. */
    static final class Tracker {
        private final Fat32Formatter.Progress progress;
        long done;
        long total;

        Tracker(Fat32Formatter.Progress progress) {
            this.progress = progress;
        }

        void add(long blocks) {
            done += blocks;
            if (progress != null) progress.onProgress(Math.min(done, total), total);
        }
    }

    private DiskFormatter() {
    }

    /** Bir bölümün içini yazan nesne (dosya sistemi). */
    interface VolumeWriter {
        /** Bölüme yazılacak sektör sayısı; bölüm yetersizse hata verir. */
        long estimateBlocks(long sectors) throws IOException;

        void write(BlockDevice dev, long start, long sectors, Tracker t) throws IOException;
    }

    public static void format(BlockDevice dev, final Fs fs, boolean gpt, final String label,
                              Fat32Formatter.Progress progress) throws IOException {
        VolumeWriter w = new VolumeWriter() {
            @Override
            public long estimateBlocks(long sectors) {
                return fs == Fs.FAT32 ? Fat32Formatter.estimateBlocks(sectors)
                        : ExFatFormatter.estimateBlocks(sectors);
            }

            @Override
            public void write(BlockDevice d, long start, long sectors, Tracker t)
                    throws IOException {
                if (fs == Fs.FAT32) {
                    Fat32Formatter.formatVolume(d, start, sectors, label, t);
                } else {
                    ExFatFormatter.formatVolume(d, start, sectors, label, t);
                }
            }
        };
        layout(dev, gpt, fs == Fs.FAT32 ? 0x0C : 0x07, label, w, progress);
    }

    /** Kartı temizler, tek bölümü verilen yazıcıyla doldurur ve bölüm tablosunu en son yazar. */
    static void layout(BlockDevice dev, boolean gpt, int mbrType, String name, VolumeWriter w,
                       Fat32Formatter.Progress progress) throws IOException {
        if (dev.blockSize() != SECTOR) {
            throw new IOException("Desteklenmeyen sektör boyutu: " + dev.blockSize());
        }
        long total = dev.blockCount();
        if (total < MIN_SECTORS) throw new IOException("Kart çok küçük (en az 128 MB olmalı)");
        if (total > 0xFFFFFFFFL) throw new IOException("2 TB üstü kartlar desteklenmiyor");

        long partStart = PART_START;
        long partEnd = gpt ? total - 1 - GPT_ARRAY_SECTORS - 1 : total - 1; // dahil
        long partSectors = partEnd - partStart + 1;

        Tracker t = new Tracker(progress);
        long tail = 64;  // yedek GPT'nin durduğu son 33 sektörü kapsar
        t.total = PART_START + tail + w.estimateBlocks(partSectors);

        // 1) Kartın başını ve sonunu temizle (eski MBR/GPT ve dosya sistemi izleri)
        writeZeros(dev, 0, PART_START, t);
        writeZeros(dev, total - tail, tail, t);

        // 2) Dosya sistemi
        w.write(dev, partStart, partSectors, t);

        // 3) Bölüm tablosu en son yazılır; yarıda kalan işlem geçerli görünmez
        if (gpt) {
            writeGpt(dev, partStart, partEnd, name);
        } else {
            byte[] mbr = new byte[SECTOR];
            mbrEntry(mbr, 0, mbrType, partStart, partSectors);
            putInt(mbr, 440, new SecureRandom().nextInt()); // disk imzası
            mbr[510] = 0x55;
            mbr[511] = (byte) 0xAA;
            dev.write(0, mbr, 1);
        }
        dev.flush();
        if (progress != null) progress.onProgress(t.total, t.total);
    }

    private static void writeGpt(BlockDevice dev, long first, long last, String label)
            throws IOException {
        long total = dev.blockCount();
        SecureRandom rnd = new SecureRandom();

        byte[] entries = new byte[GPT_ARRAY_SECTORS * SECTOR];
        System.arraycopy(BASIC_DATA_GUID, 0, entries, 0, 16);
        byte[] unique = new byte[16];
        rnd.nextBytes(unique);
        unique[7] = (byte) ((unique[7] & 0x0F) | 0x40);   // sürüm 4 (karışık sıralı alan)
        unique[8] = (byte) ((unique[8] & 0x3F) | 0x80);
        System.arraycopy(unique, 0, entries, 16, 16);
        putLong(entries, 32, first);
        putLong(entries, 40, last);
        String name = label == null || label.trim().isEmpty() ? "Kart" : label.trim();
        byte[] n = name.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(n, 0, entries, 56, Math.min(n.length, 72));

        CRC32 crc = new CRC32();
        crc.update(entries, 0, entries.length);
        int arrayCrc = (int) crc.getValue();

        byte[] diskGuid = new byte[16];
        rnd.nextBytes(diskGuid);
        diskGuid[7] = (byte) ((diskGuid[7] & 0x0F) | 0x40);
        diskGuid[8] = (byte) ((diskGuid[8] & 0x3F) | 0x80);

        long backupArray = total - 1 - GPT_ARRAY_SECTORS;
        byte[] primary = gptHeader(1, total - 1, 2, total, diskGuid, arrayCrc);
        byte[] backup = gptHeader(total - 1, 1, backupArray, total, diskGuid, arrayCrc);

        dev.write(2, entries, GPT_ARRAY_SECTORS);
        dev.write(backupArray, entries, GPT_ARRAY_SECTORS);
        dev.write(total - 1, backup, 1);
        dev.write(1, primary, 1);

        byte[] mbr = new byte[SECTOR];
        mbrEntry(mbr, 0, 0xEE, 1, Math.min(total - 1, 0xFFFFFFFFL));
        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;
        dev.write(0, mbr, 1);
    }

    private static byte[] gptHeader(long my, long alt, long arrayLba, long total,
                                    byte[] diskGuid, int arrayCrc) {
        byte[] h = new byte[SECTOR];
        System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        putInt(h, 8, 0x00010000);
        putInt(h, 12, 92);
        putLong(h, 24, my);
        putLong(h, 32, alt);
        putLong(h, 40, 2 + GPT_ARRAY_SECTORS);                 // ilk kullanılabilir
        putLong(h, 48, total - 2 - GPT_ARRAY_SECTORS);         // son kullanılabilir
        System.arraycopy(diskGuid, 0, h, 56, 16);
        putLong(h, 72, arrayLba);
        putInt(h, 80, GPT_ENTRIES);
        putInt(h, 84, GPT_ENTRY_SIZE);
        putInt(h, 88, arrayCrc);
        CRC32 crc = new CRC32();
        crc.update(h, 0, 92);
        putInt(h, 16, (int) crc.getValue());
        return h;
    }

    private static void mbrEntry(byte[] mbr, int index, int type, long start, long size) {
        int e = 446 + index * 16;
        mbr[e] = 0x00;
        mbr[e + 1] = (byte) 0xFE;
        mbr[e + 2] = (byte) 0xFF;
        mbr[e + 3] = (byte) 0xFF;
        mbr[e + 4] = (byte) type;
        mbr[e + 5] = (byte) 0xFE;
        mbr[e + 6] = (byte) 0xFF;
        mbr[e + 7] = (byte) 0xFF;
        putInt(mbr, e + 8, (int) start);
        putInt(mbr, e + 12, (int) size);
    }

    // ------------------------------------------------------------ ortak yazma yardımcıları

    static void writeZeros(BlockDevice dev, long lba, long blocks, Tracker t) throws IOException {
        byte[] zero = new byte[CHUNK * SECTOR];
        long pos = 0;
        while (pos < blocks) {
            int n = (int) Math.min(CHUNK, blocks - pos);
            dev.write(lba + pos, zero, n);
            pos += n;
            t.add(n);
        }
    }

    static void writeData(BlockDevice dev, long lba, byte[] data, long blocks, Tracker t)
            throws IOException {
        byte[] chunk = new byte[CHUNK * SECTOR];
        long pos = 0;
        while (pos < blocks) {
            int n = (int) Math.min(CHUNK, blocks - pos);
            Arrays.fill(chunk, (byte) 0);
            long from = pos * SECTOR;
            if (from < data.length) {
                int len = (int) Math.min(n * SECTOR, data.length - from);
                System.arraycopy(data, (int) from, chunk, 0, len);
            }
            dev.write(lba + pos, chunk, n);
            pos += n;
            t.add(n);
        }
    }

    static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static void putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) v);
        putInt(b, off + 4, (int) (v >>> 32));
    }
}

package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Karta bölüm tablosu (MBR ya da GPT) ve tek bir bölüm yazar, bölümü FAT32 ya da exFAT
 * olarak biçimlendirir.
 */
public final class DiskFormatter {

    public enum Fs {
        FAT32, EXFAT, EXT4, NTFS;

        public String title() {
            switch (this) {
                case FAT32: return "FAT32";
                case EXFAT: return "exFAT";
                case EXT4: return "ext4";
                default: return "NTFS";
            }
        }

        int mbrType() {
            return this == FAT32 ? 0x0C : this == EXT4 ? 0x83 : 0x07;
        }
    }

    /** Bölüm sihirbazı için bir bölüm: boyut 0 ise kalan alanın tamamı. */
    public static final class Part {
        public final Fs fs;
        public final long sectors;
        public final String label;
        public final int clusterBytes;

        public Part(Fs fs, long sectors, String label, int clusterBytes) {
            this.fs = fs;
            this.sectors = sectors;
            this.label = label;
            this.clusterBytes = clusterBytes;
        }
    }

    static final int SECTOR = 512;
    static final int PART_START = 2048;      // 1 MiB hizalama
    static final int CHUNK = 128;            // tek seferde 64 KB
    private static final long MIN_SECTORS = 262144;  // 128 MB
    private static final int GPT_ENTRIES = 128;
    private static final int GPT_ENTRY_SIZE = 128;
    private static final int GPT_ARRAY_SECTORS = GPT_ENTRIES * GPT_ENTRY_SIZE / SECTOR; // 32

    /** Linux dosya sistemi bölümü. */
    private static final byte[] LINUX_GUID = {
            (byte) 0xAF, 0x3D, (byte) 0xC6, 0x0F, (byte) 0x83, (byte) 0x84, 0x72, 0x47,
            (byte) 0x8E, 0x79, 0x3D, 0x69, (byte) 0xD8, 0x47, 0x7D, (byte) 0xE4
    };

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

    public static void format(BlockDevice dev, Fs fs, boolean gpt, String label,
                              Fat32Formatter.Progress progress) throws IOException {
        format(dev, fs, gpt, label, 0, progress);
    }

    public static void format(BlockDevice dev, Fs fs, boolean gpt, String label, int clusterBytes,
                              Fat32Formatter.Progress progress) throws IOException {
        List<Part> one = new ArrayList<>();
        one.add(new Part(fs, 0, label, clusterBytes));
        partition(dev, gpt, one, progress);
    }

    /** Bir dosya sistemine uygun bölüm yazıcısı. */
    static VolumeWriter writer(final Fs fs, final String label, final int clusterBytes) {
        if (fs == Fs.EXT4) return new Ext4Formatter(label);
        if (fs == Fs.NTFS) return new NtfsFormatter(label, clusterBytes);
        final int spc = clusterBytes / SECTOR;
        return new VolumeWriter() {
            @Override
            public long estimateBlocks(long sectors) throws IOException {
                return fs == Fs.FAT32 ? Fat32Formatter.estimateBlocks(sectors, spc)
                        : ExFatFormatter.estimateBlocks(sectors, spc);
            }

            @Override
            public void write(BlockDevice d, long start, long sectors, Tracker t)
                    throws IOException {
                if (fs == Fs.FAT32) {
                    Fat32Formatter.formatVolume(d, start, sectors, label, t, spc);
                } else {
                    ExFatFormatter.formatVolume(d, start, sectors, label, t, spc);
                }
            }
        };
    }

    /** Tek bölüm, verilen yazıcıyla (Windows USB gibi özel içerikler için). */
    static void layout(BlockDevice dev, boolean gpt, int mbrType, String name, VolumeWriter w,
                       Fat32Formatter.Progress progress) throws IOException {
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec(w, 0, mbrType, BASIC_DATA_GUID, name));
        layout(dev, gpt, specs, progress);
    }

    /** Bölüm sihirbazı: en çok 4 bölüm. */
    public static void partition(BlockDevice dev, boolean gpt, List<Part> parts,
                                 Fat32Formatter.Progress progress) throws IOException {
        if (parts.isEmpty() || parts.size() > 4) throw new IOException("1 ile 4 arası bölüm olmalı");
        List<Spec> specs = new ArrayList<>();
        for (Part p : parts) {
            specs.add(new Spec(writer(p.fs, p.label, p.clusterBytes), p.sectors, p.fs.mbrType(),
                    p.fs == Fs.EXT4 ? LINUX_GUID : BASIC_DATA_GUID, p.label));
        }
        layout(dev, gpt, specs, progress);
    }

    private static final class Spec {
        final VolumeWriter w;
        final long sectors;
        final int mbrType;
        final byte[] gptType;
        final String name;
        long start;
        long count;

        Spec(VolumeWriter w, long sectors, int mbrType, byte[] gptType, String name) {
            this.w = w;
            this.sectors = sectors;
            this.mbrType = mbrType;
            this.gptType = gptType;
            this.name = name;
        }
    }

    /** Kartı temizler, bölümleri yazar ve bölüm tablosunu en son yazar. */
    private static void layout(BlockDevice dev, boolean gpt, List<Spec> specs,
                               Fat32Formatter.Progress progress) throws IOException {
        if (dev.blockSize() != SECTOR) {
            throw new IOException("Desteklenmeyen sektör boyutu: " + dev.blockSize());
        }
        long total = dev.blockCount();
        if (total < MIN_SECTORS) throw new IOException("Kart çok küçük (en az 128 MB olmalı)");
        if (total > 0xFFFFFFFFL) throw new IOException("2 TB üstü kartlar desteklenmiyor");

        long limit = gpt ? total - 1 - GPT_ARRAY_SECTORS : total; // ilk kullanılamayan sektör
        long pos = PART_START;
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            boolean last = i == specs.size() - 1;
            long size = s.sectors > 0 && !last ? s.sectors / PART_START * PART_START
                    : s.sectors > 0 ? Math.min(s.sectors, limit - pos) : limit - pos;
            if (s.sectors == 0 && !last) throw new IOException("Yalnızca son bölüm \"kalan\" olabilir");
            if (size <= 0 || pos + size > limit) {
                throw new IOException("Bölümler karta sığmıyor");
            }
            s.start = pos;
            s.count = size;
            pos += size;
        }

        Tracker t = new Tracker(progress);
        long tail = 64;  // yedek GPT'nin durduğu son 33 sektörü kapsar
        t.total = PART_START + tail;
        for (Spec s : specs) t.total += s.w.estimateBlocks(s.count);

        // 1) Kartın başını ve sonunu temizle (eski MBR/GPT ve dosya sistemi izleri)
        writeZeros(dev, 0, PART_START, t);
        writeZeros(dev, total - tail, tail, t);

        // 2) Dosya sistemleri
        for (Spec s : specs) s.w.write(dev, s.start, s.count, t);

        // 3) Bölüm tablosu en son yazılır; yarıda kalan işlem geçerli görünmez
        if (gpt) {
            writeGpt(dev, specs);
        } else {
            byte[] mbr = new byte[SECTOR];
            for (int i = 0; i < specs.size(); i++) {
                Spec s = specs.get(i);
                mbrEntry(mbr, i, s.mbrType, s.start, s.count);
            }
            putInt(mbr, 440, new SecureRandom().nextInt()); // disk imzası
            mbr[510] = 0x55;
            mbr[511] = (byte) 0xAA;
            dev.write(0, mbr, 1);
        }
        dev.flush();
        if (progress != null) progress.onProgress(t.total, t.total);
    }

    private static void writeGpt(BlockDevice dev, List<Spec> specs) throws IOException {
        long total = dev.blockCount();
        SecureRandom rnd = new SecureRandom();

        byte[] entries = new byte[GPT_ARRAY_SECTORS * SECTOR];
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            int o = i * GPT_ENTRY_SIZE;
            System.arraycopy(s.gptType, 0, entries, o, 16);
            byte[] unique = new byte[16];
            rnd.nextBytes(unique);
            unique[7] = (byte) ((unique[7] & 0x0F) | 0x40);
            unique[8] = (byte) ((unique[8] & 0x3F) | 0x80);
            System.arraycopy(unique, 0, entries, o + 16, 16);
            putLong(entries, o + 32, s.start);
            putLong(entries, o + 40, s.start + s.count - 1);
            String name = s.name == null || s.name.trim().isEmpty() ? "Kart" : s.name.trim();
            byte[] n = name.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(n, 0, entries, o + 56, Math.min(n.length, 72));
        }

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

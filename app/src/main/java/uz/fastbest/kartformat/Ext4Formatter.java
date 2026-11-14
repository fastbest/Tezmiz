package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static uz.fastbest.kartformat.DiskFormatter.putInt;
import static uz.fastbest.kartformat.DiskFormatter.putShort;

/**
 * Bir bölümü ext4 olarak biçimlendirir (günlüksüz, extent'li, 4 KB blok).
 * Linux ve Android'in bağlayabildiği, e2fsck ile hatasız çıkan en sade düzen.
 */
final class Ext4Formatter implements DiskFormatter.VolumeWriter {

    private static final int BS = 4096;
    private static final int SPB = BS / 512;           // blok başına sektör
    private static final int BPG = 32768;              // grup başına blok
    private static final int INODE_SIZE = 256;
    private static final int INODE_RATIO = 16384;      // inode başına bayt
    private static final int IPG = (int) ((long) BPG * BS / INODE_RATIO); // 8192
    private static final int ITB = IPG * INODE_SIZE / BS;        // inode tablosu blokları
    private static final int DESC = 32;
    private static final int LPF_BLOCKS = 4;
    private static final int ROOT_INO = 2;
    private static final int LPF_INO = 11;

    private final String label;

    private long blocks;
    private int groups;
    private int gdtBlocks;

    Ext4Formatter(String label) {
        this.label = label;
    }

    static boolean hasBackup(int g) {
        if (g <= 1) return true;
        for (int p : new int[]{3, 5, 7}) {
            long x = p;
            while (x < g) x *= p;
            if (x == g) return true;
        }
        return false;
    }

    private long groupStart(int g) {
        return (long) g * BPG;
    }

    private long groupBlocks(int g) {
        return Math.min(BPG, blocks - groupStart(g));
    }

    private int overhead(int g) {
        return (hasBackup(g) ? 1 + gdtBlocks : 0) + 2 + ITB;
    }

    private void plan(long sectors) throws IOException {
        blocks = sectors / SPB;
        if (blocks > 0xFFFFFFFFL) blocks = 0xFFFFFFFFL;
        for (int pass = 0; pass < 3; pass++) {
            groups = (int) ((blocks + BPG - 1) / BPG);
            gdtBlocks = (groups * DESC + BS - 1) / BS;
            long last = groupBlocks(groups - 1);
            if (groups > 1 && last < overhead(groups - 1) + 64) {
                blocks = groupStart(groups - 1); // çok küçük son grubu at
                continue;
            }
            break;
        }
        if (blocks < 2048 || groupBlocks(0) < overhead(0) + 1 + LPF_BLOCKS + 16) {
            throw new IOException("Bölüm ext4 için çok küçük (en az 8 MB)");
        }
    }

    @Override
    public long estimateBlocks(long sectors) throws IOException {
        plan(sectors);
        long metaBlocks = 0;
        for (int g = 0; g < groups; g++) metaBlocks += overhead(g);
        return (metaBlocks + 1 + LPF_BLOCKS + 1) * SPB;
    }

    @Override
    public void write(BlockDevice dev, long start, long sectors, DiskFormatter.Tracker t)
            throws IOException {
        plan(sectors);
        int now = (int) (System.currentTimeMillis() / 1000);
        SecureRandom rnd = new SecureRandom();
        byte[] uuid = new byte[16];
        rnd.nextBytes(uuid);
        uuid[6] = (byte) ((uuid[6] & 0x0F) | 0x40);
        uuid[8] = (byte) ((uuid[8] & 0x3F) | 0x80);
        byte[] hashSeed = new byte[16];
        rnd.nextBytes(hashSeed);

        // Grup 0'daki ilk veri blokları: kök dizin ve lost+found
        long g0data = overhead(0);
        long rootBlock = g0data;
        long lpfBlock = g0data + 1;
        long usedData0 = 1 + LPF_BLOCKS;

        long totalFree = 0;
        long[] freeBlocks = new long[groups];
        int[] freeInodes = new int[groups];
        for (int g = 0; g < groups; g++) {
            long used = overhead(g) + (g == 0 ? usedData0 : 0);
            freeBlocks[g] = groupBlocks(g) - used;
            totalFree += freeBlocks[g];
            freeInodes[g] = IPG - (g == 0 ? LPF_INO : 0);
        }
        long totalFreeInodes = (long) groups * IPG - LPF_INO;

        // Grup tanımlayıcı tablosu
        byte[] gdt = new byte[gdtBlocks * BS];
        for (int g = 0; g < groups; g++) {
            long base = groupStart(g) + (hasBackup(g) ? 1 + gdtBlocks : 0);
            int o = g * DESC;
            putInt(gdt, o, (int) base);                 // blok bitmap
            putInt(gdt, o + 4, (int) (base + 1));       // inode bitmap
            putInt(gdt, o + 8, (int) (base + 2));       // inode tablosu
            putShort(gdt, o + 12, (int) freeBlocks[g]);
            putShort(gdt, o + 14, freeInodes[g]);
            putShort(gdt, o + 16, g == 0 ? 2 : 0);      // dizin sayısı
        }

        for (int g = 0; g < groups; g++) {
            long gs = groupStart(g);
            long pos = gs;
            if (hasBackup(g)) {
                byte[] head = new byte[(1 + gdtBlocks) * BS];
                byte[] sb = superblock(g, now, uuid, hashSeed, totalFree, totalFreeInodes);
                System.arraycopy(sb, 0, head, g == 0 ? 1024 : 0, 1024);
                System.arraycopy(gdt, 0, head, BS, gdt.length);
                writeBlocks(dev, start, pos, head, t);
                pos += 1 + gdtBlocks;
            }
            // Blok bitmap
            byte[] bb = new byte[BS];
            long used = overhead(g) + (g == 0 ? usedData0 : 0);
            for (long i = 0; i < used; i++) setBit(bb, i);
            for (long i = groupBlocks(g); i < BS * 8L; i++) setBit(bb, i);
            writeBlocks(dev, start, pos, bb, t);
            // Inode bitmap
            byte[] ib = new byte[BS];
            if (g == 0) for (int i = 0; i < LPF_INO; i++) setBit(ib, i);
            for (int i = IPG; i < BS * 8; i++) setBit(ib, i);
            writeBlocks(dev, start, pos + 1, ib, t);
            // Inode tablosu (sıfır); grup 0'da kök ve lost+found
            byte[] table = new byte[ITB * BS];
            if (g == 0) {
                inode(table, ROOT_INO, 0x41FF, 3, rootBlock, 1, now); // herkes yazabilir
                inode(table, LPF_INO, 0x41C0, 2, lpfBlock, LPF_BLOCKS, now);
            }
            writeBlocks(dev, start, pos + 2, table, t);
        }

        // Kök dizin
        byte[] root = new byte[BS];
        int o = dirent(root, 0, ROOT_INO, 12, ".", 2);
        o = dirent(root, o, ROOT_INO, 12, "..", 2);
        dirent(root, o, LPF_INO, BS - o, "lost+found", 2);
        writeBlocks(dev, start, rootBlock, root, t);
        // lost+found
        byte[] lpf = new byte[LPF_BLOCKS * BS];
        o = dirent(lpf, 0, LPF_INO, 12, ".", 2);
        dirent(lpf, o, ROOT_INO, BS - o, "..", 2);
        for (int b = 1; b < LPF_BLOCKS; b++) putShort(lpf, b * BS + 4, BS); // boş girdi
        writeBlocks(dev, start, lpfBlock, lpf, t);

        // Bölümün ilk 1 KB'ı (eski önyükleme sektörü izleri) temizlenir
        byte[] zero = new byte[1024];
        byte[] first = new byte[BS];
        dev.read(start, first, SPB);
        System.arraycopy(zero, 0, first, 0, 1024);
        dev.write(start, first, SPB);
    }

    private byte[] superblock(int g, int now, byte[] uuid, byte[] seed, long freeBlocks,
                              long freeInodes) {
        byte[] s = new byte[1024];
        putInt(s, 0, groups * IPG);                 // inode sayısı
        putInt(s, 4, (int) blocks);                 // blok sayısı
        putInt(s, 8, 0);                            // ayrılmış bloklar
        putInt(s, 12, (int) freeBlocks);
        putInt(s, 16, (int) freeInodes);
        putInt(s, 20, 0);                           // ilk veri bloğu
        putInt(s, 24, 2);                           // log2(blok/1024)
        putInt(s, 28, 2);                           // küme boyutu
        putInt(s, 32, BPG);
        putInt(s, 36, BPG);
        putInt(s, 40, IPG);
        putInt(s, 48, now);                         // son yazma
        putShort(s, 54, 0xFFFF);                    // en çok bağlama sayısı (-1)
        putShort(s, 56, 0xEF53);                    // sihirli sayı
        putShort(s, 58, 1);                         // temiz
        putShort(s, 60, 1);                         // hata olursa devam et
        putInt(s, 64, now);                         // son denetim
        putInt(s, 76, 1);                           // dinamik revizyon
        putInt(s, 84, LPF_INO);                     // ilk normal inode
        putShort(s, 88, INODE_SIZE);
        putShort(s, 90, g);                         // bu kopyanın grubu
        putInt(s, 92, 0);                           // compat
        putInt(s, 96, 0x0002 | 0x0040);             // incompat: filetype, extents
        putInt(s, 100, 0x0001 | 0x0002 | 0x0008 | 0x0020 | 0x0040); // sparse, large, huge, dir_nlink, extra_isize
        System.arraycopy(uuid, 0, s, 104, 16);
        byte[] lab = Fat32Formatter.cleanLabel(label).isEmpty() ? new byte[0]
                : label.trim().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(lab, 0, s, 120, Math.min(16, lab.length));
        System.arraycopy(seed, 0, s, 236, 16);      // dizin karma tohumu
        s[252] = 1;                                 // half_md4
        putInt(s, 264, now);                        // oluşturma zamanı
        putShort(s, 348, 32);                       // en az ek inode alanı
        putShort(s, 350, 32);                       // istenen ek inode alanı
        putInt(s, 352, 0x0002);                     // işaretsiz karma
        return s;
    }

    private void inode(byte[] table, int ino, int mode, int links, long block, int count,
                       int now) {
        int o = (ino - 1) * INODE_SIZE;
        putShort(table, o, mode);
        putInt(table, o + 4, count * BS);           // boyut
        putInt(table, o + 8, now);
        putInt(table, o + 12, now);
        putInt(table, o + 16, now);
        putShort(table, o + 26, links);
        putInt(table, o + 28, count * SPB);         // 512 baytlık birimlerle
        putInt(table, o + 32, 0x80000);             // extent kullanılıyor
        // extent başlığı + tek extent
        putShort(table, o + 40, 0xF30A);
        putShort(table, o + 42, 1);
        putShort(table, o + 44, 4);
        putShort(table, o + 46, 0);
        putInt(table, o + 52, 0);                   // mantıksal blok 0
        putShort(table, o + 56, count);
        putShort(table, o + 58, 0);
        putInt(table, o + 60, (int) block);
        putShort(table, o + 128, 32);               // i_extra_isize
        putInt(table, o + 144, now);                // oluşturma zamanı
    }

    private static int dirent(byte[] b, int o, int ino, int recLen, String name, int type) {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        putInt(b, o, ino);
        putShort(b, o + 4, recLen);
        b[o + 6] = (byte) n.length;
        b[o + 7] = (byte) type;
        System.arraycopy(n, 0, b, o + 8, n.length);
        return o + recLen;
    }

    private static void setBit(byte[] b, long i) {
        b[(int) (i >>> 3)] |= (byte) (1 << (i & 7));
    }

    private static void writeBlocks(BlockDevice dev, long start, long block, byte[] data,
                                    DiskFormatter.Tracker t) throws IOException {
        DiskFormatter.writeData(dev, start + block * SPB, data, data.length / 512, t);
    }
}

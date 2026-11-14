package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static uz.fastbest.kartformat.DiskFormatter.SECTOR;
import static uz.fastbest.kartformat.DiskFormatter.putInt;
import static uz.fastbest.kartformat.DiskFormatter.putShort;
import static uz.fastbest.kartformat.Fat32Formatter.NUM_FATS;
import static uz.fastbest.kartformat.Fat32Formatter.RESERVED;

/** Boş bir bölüme FAT32 dosya sistemini dosyalarıyla birlikte tek seferde yazar. */
final class Fat32Builder implements DiskFormatter.VolumeWriter {

    static final long MAX_FILE = 0xFFFFFFFFL;
    private static final int WRITE_CHUNK = 1024 * 1024;

    static final class Node {
        final String name;
        final boolean dir;
        final Content content;
        final List<Node> kids = new ArrayList<>();
        long first;
        long clusters;
        byte[] shortName;
        byte[] lfn;

        private Node(String name, boolean dir, Content content) {
            this.name = name;
            this.dir = dir;
            this.content = content;
        }

        static Node dir(String name) {
            return new Node(name, true, null);
        }

        static Node file(String name, Content c) {
            return new Node(name, false, c);
        }

        long size() {
            return dir ? 0 : content.size();
        }
    }

    private final Node root;
    private final byte[] label11;
    private final DiskOps.Cancel cancel;
    private final int date;
    private final int time;

    private long planned = -1;
    private int spc;
    private long fatSize;
    private long clusterCount;
    private long next;
    private final List<Node> dirs = new ArrayList<>();
    private final List<Node> files = new ArrayList<>();
    private long[] chainEnds;

    Fat32Builder(Node root, String label, DiskOps.Cancel cancel) {
        this.root = root;
        this.label11 = Fat32Formatter.makeLabel(label);
        this.cancel = cancel;
        Calendar c = Calendar.getInstance();
        date = ((c.get(Calendar.YEAR) - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5)
                | c.get(Calendar.DAY_OF_MONTH);
        time = (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5)
                | (c.get(Calendar.SECOND) / 2);
    }

    /** Dosyaların kapladığı bayt (küme boyutuna yuvarlanmadan). */
    static long contentBytes(Node n) {
        long sum = n.size();
        for (Node k : n.kids) sum += contentBytes(k);
        return sum;
    }

    @Override
    public long estimateBlocks(long sectors) throws IOException {
        plan(sectors);
        return RESERVED + NUM_FATS * fatSize + (next - 2) * spc;
    }

    private void plan(long sectors) throws IOException {
        if (planned == sectors) return;
        spc = Fat32Formatter.sectorsPerCluster(sectors);
        fatSize = Fat32Formatter.fatSize(sectors, spc);
        clusterCount = (sectors - RESERVED - NUM_FATS * fatSize) / spc;
        if (clusterCount < 65525) throw new IOException("Kart FAT32 için çok küçük");
        long cb = (long) spc * SECTOR;

        dirs.clear();
        files.clear();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node d = queue.poll();
            dirs.add(d);
            Set<String> used = new HashSet<>();
            long entries = d == root ? (label11 != null ? 1 : 0) : 2;
            for (Node k : d.kids) {
                if (!k.dir && k.size() > MAX_FILE) {
                    throw new IOException(k.name + " FAT32 için çok büyük (4 GB sınırı)");
                }
                makeNames(k, used);
                entries += 1 + k.lfn.length / 32;
                if (k.dir) queue.add(k); else files.add(k);
            }
            d.clusters = Math.max(1, (entries * 32 + cb - 1) / cb);
        }
        next = 2;
        List<Long> ends = new ArrayList<>();
        for (Node d : dirs) {
            d.first = next;
            next += d.clusters;
            ends.add(next - 1);
        }
        for (Node f : files) {
            long size = f.size();
            f.clusters = (size + cb - 1) / cb;
            if (f.clusters == 0) {
                f.first = 0;
            } else {
                f.first = next;
                next += f.clusters;
                ends.add(next - 1);
            }
        }
        long used = next - 2;
        if (used > clusterCount) {
            long need = (RESERVED + NUM_FATS * fatSize + used * spc + 4096) * SECTOR;
            throw new IOException("Kart küçük: en az " + DiskOps.sizeText(need)
                    + " boş alan gerekiyor");
        }
        chainEnds = new long[ends.size()];
        for (int i = 0; i < chainEnds.length; i++) chainEnds[i] = ends.get(i);
        Arrays.sort(chainEnds);
        planned = sectors;
    }

    @Override
    public void write(BlockDevice dev, long start, long sectors, DiskFormatter.Tracker t)
            throws IOException {
        plan(sectors);
        long fatStart = start + RESERVED;
        long dataStart = fatStart + NUM_FATS * fatSize;

        // 1) Dosya içerikleri
        for (Node f : files) {
            if (f.clusters == 0) continue;
            ClusterSink sink = new ClusterSink(dev, dataStart + (f.first - 2) * spc,
                    f.size(), t);
            f.content.writeTo(sink);
            sink.finish(f.name);
        }

        // 2) Dizinler
        for (Node d : dirs) {
            check();
            byte[] data = dirData(d);
            DiskFormatter.writeData(dev, dataStart + (d.first - 2) * spc, data,
                    d.clusters * spc, t);
        }

        // 3) FAT tabloları
        int perChunk = 16384;
        byte[] buf = new byte[perChunk * 4];
        long maxUsed = next - 1;
        long entriesTotal = fatSize * (SECTOR / 4);
        for (int f = 0; f < NUM_FATS; f++) {
            int p = 0;
            for (long c0 = 0; c0 < entriesTotal; c0 += perChunk) {
                check();
                int n = (int) Math.min(perChunk, entriesTotal - c0);
                Arrays.fill(buf, (byte) 0);
                for (int i = 0; i < n; i++) {
                    long c = c0 + i;
                    int v;
                    if (c == 0) {
                        v = 0x0FFFFFF8;
                    } else if (c == 1) {
                        v = 0x0FFFFFFF;
                    } else if (c <= maxUsed) {
                        while (p < chainEnds.length && chainEnds[p] < c) p++;
                        v = p < chainEnds.length && chainEnds[p] == c ? 0x0FFFFFFF : (int) (c + 1);
                    } else {
                        break;
                    }
                    putInt(buf, i * 4, v);
                }
                int blocks = n * 4 / SECTOR;
                dev.write(fatStart + f * fatSize + c0 * 4 / SECTOR, buf, blocks);
                t.add(blocks);
            }
        }

        // 4) Önyükleme bölgesi en son
        int volumeId = (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
        byte[] reserved = new byte[RESERVED * SECTOR];
        byte[] boot = Fat32Formatter.bootSector(start, sectors, spc, fatSize, volumeId, label11);
        byte[] fsInfo = Fat32Formatter.fsInfo(clusterCount - (next - 2), next);
        byte[] third = new byte[SECTOR];
        third[510] = 0x55;
        third[511] = (byte) 0xAA;
        for (int copy = 0; copy <= 6; copy += 6) {
            System.arraycopy(boot, 0, reserved, copy * SECTOR, SECTOR);
            System.arraycopy(fsInfo, 0, reserved, (copy + 1) * SECTOR, SECTOR);
            System.arraycopy(third, 0, reserved, (copy + 2) * SECTOR, SECTOR);
        }
        DiskFormatter.writeData(dev, start, reserved, RESERVED, t);
    }

    private void check() throws DiskOps.CancelledException {
        if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
    }

    private byte[] dirData(Node d) {
        byte[] b = new byte[(int) (d.clusters * spc * SECTOR)];
        int p = 0;
        if (d == root) {
            if (label11 != null) {
                System.arraycopy(label11, 0, b, 0, 11);
                b[11] = 0x08;
                putShort(b, 22, time);
                putShort(b, 24, date);
                p += 32;
            }
        } else {
            entry(b, 0, ".          ".getBytes(StandardCharsets.US_ASCII), 0x10, d.first, 0);
            Node parent = parentOf(root, d);
            long up = parent == root || parent == null ? 0 : parent.first;
            entry(b, 32, "..         ".getBytes(StandardCharsets.US_ASCII), 0x10, up, 0);
            p += 64;
        }
        for (Node k : d.kids) {
            System.arraycopy(k.lfn, 0, b, p, k.lfn.length);
            p += k.lfn.length;
            entry(b, p, k.shortName, k.dir ? 0x10 : 0x20, k.first, k.size());
            p += 32;
        }
        return b;
    }

    private static Node parentOf(Node from, Node target) {
        for (Node k : from.kids) {
            if (k == target) return from;
            if (k.dir) {
                Node r = parentOf(k, target);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void entry(byte[] b, int p, byte[] name, int attr, long cluster, long size) {
        System.arraycopy(name, 0, b, p, 11);
        b[p + 11] = (byte) attr;
        putShort(b, p + 14, time);
        putShort(b, p + 16, date);
        putShort(b, p + 18, date);
        putShort(b, p + 20, (int) (cluster >>> 16));
        putShort(b, p + 22, time);
        putShort(b, p + 24, date);
        putShort(b, p + 26, (int) cluster);
        putInt(b, p + 28, (int) size);
    }

    // ------------------------------------------------------------ adlar

    private static final String SHORT_OK = "!#$%&'()-@^_`{}~";

    private static boolean shortChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || SHORT_OK.indexOf(c) >= 0;
    }

    static void makeNames(Node n, Set<String> used) throws IOException {
        byte[][] r = fatNames(n.name, used);
        n.shortName = r[0];
        n.lfn = r[1];
    }

    /** FAT kısa adı (11 bayt) ve gerekirse uzun ad girdileri; kısa ad "used" kümesine eklenir. */
    static byte[][] fatNames(String name, Set<String> used) throws IOException {
        if (name.isEmpty() || name.length() > 255) throw new IOException("Geçersiz dosya adı");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                throw new IOException("FAT32'de geçersiz dosya adı: " + name);
            }
        }
        String upper = name.toUpperCase(Locale.ROOT);
        int dot = upper.lastIndexOf('.');
        String base = dot > 0 ? upper.substring(0, dot) : upper;
        String ext = dot > 0 ? upper.substring(dot + 1) : "";

        boolean exact = base.length() >= 1 && base.length() <= 8 && ext.length() <= 3
                && upper.indexOf('.') == dot;
        for (int i = 0; exact && i < base.length(); i++) exact = shortChar(base.charAt(i));
        for (int i = 0; exact && i < ext.length(); i++) exact = shortChar(ext.charAt(i));

        String b = clean(base);
        String e = clean(ext);
        if (e.length() > 3) e = e.substring(0, 3);
        String chosen = null;
        if (exact) {
            String cand = pad(base, 8) + pad(ext, 3);
            if (used.add(cand)) chosen = cand;
        }
        if (chosen == null) {
            if (b.isEmpty()) b = "_";
            for (int i = 1; i < 1000000; i++) {
                String tail = "~" + i;
                String cand = pad(b.substring(0, Math.min(b.length(), 8 - tail.length())) + tail, 8)
                        + pad(e, 3);
                if (used.add(cand)) {
                    chosen = cand;
                    break;
                }
            }
            if (chosen == null) throw new IOException("Klasörde çok fazla dosya var");
        }
        byte[] shortName = chosen.getBytes(StandardCharsets.US_ASCII);
        byte[] lfn = exact && name.equals(upper) ? new byte[0] : lfnEntries(name, shortName);
        return new byte[][]{shortName, lfn};
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '.') continue;
            sb.append(shortChar(c) ? c : '_');
        }
        return sb.toString();
    }

    private static String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    static int checksum(byte[] shortName) {
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum = (((sum & 1) << 7) + ((sum & 0xFF) >> 1) + (shortName[i] & 0xFF)) & 0xFF;
        }
        return sum;
    }

    private static byte[] lfnEntries(String name, byte[] shortName) {
        int count = (name.length() + 12) / 13;
        byte[] out = new byte[count * 32];
        int sum = checksum(shortName);
        int[] slots = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        for (int seq = 1; seq <= count; seq++) {
            int p = (count - seq) * 32;
            out[p] = (byte) (seq | (seq == count ? 0x40 : 0));
            out[p + 11] = 0x0F;
            out[p + 13] = (byte) sum;
            for (int k = 0; k < 13; k++) {
                int idx = (seq - 1) * 13 + k;
                int ch = idx < name.length() ? name.charAt(idx) : idx == name.length() ? 0 : 0xFFFF;
                putShort(out, p + slots[k], ch);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ ardışık küme yazıcı

    private final class ClusterSink implements Content.Sink {
        private final BlockDevice dev;
        private final DiskFormatter.Tracker t;
        private final long size;
        private final byte[] buf = new byte[WRITE_CHUNK];
        private long lba;
        private int fill;
        private long written;

        ClusterSink(BlockDevice dev, long lba, long size, DiskFormatter.Tracker t) {
            this.dev = dev;
            this.lba = lba;
            this.size = size;
            this.t = t;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written + len > size) throw new IOException("Dosya beklenenden büyük");
            written += len;
            while (len > 0) {
                int n = Math.min(len, buf.length - fill);
                System.arraycopy(b, off, buf, fill, n);
                fill += n;
                off += n;
                len -= n;
                if (fill == buf.length) flushBuf();
            }
        }

        private void flushBuf() throws IOException {
            check();
            int blocks = (fill + SECTOR - 1) / SECTOR;
            if (fill < blocks * SECTOR) Arrays.fill(buf, fill, blocks * SECTOR, (byte) 0);
            dev.write(lba, buf, blocks);
            lba += blocks;
            t.add(blocks);
            fill = 0;
        }

        void finish(String name) throws IOException {
            if (fill > 0) flushBuf();
            if (written != size) throw new IOException(name + " eksik okundu");
            long cb = (long) spc * SECTOR;
            long padBlocks = ((size + cb - 1) / cb * cb - (size + SECTOR - 1) / SECTOR * SECTOR)
                    / SECTOR;
            t.add(padBlocks); // kümenin geri kalanı yazılmaz ama ilerlemeye sayılır
        }
    }
}

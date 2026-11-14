package uz.fastbest.kartformat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Karttaki bir FAT ya da exFAT bölümünde dosya işlemleri. */
public abstract class CardVolume {

    public static final class Entry {
        public String name;
        public boolean dir;
        public long size;
        long cluster;
        boolean contiguous;      // exFAT: FAT zinciri yok, kümeler ardışık
        long[] slots;            // dizin girdilerinin bölüm içi bayt konumları
        Entry parent;

        public String path() {
            if (parent == null) return "/";
            String p = parent.path();
            return (p.endsWith("/") ? p : p + "/") + name;
        }
    }

    protected final BlockDevice dev;
    protected Entry root;

    protected CardVolume(BlockDevice dev) {
        this.dev = dev;
    }

    public Entry root() {
        return root;
    }

    public abstract String type();

    public abstract long totalBytes();

    public abstract long freeBytes() throws IOException;

    public abstract boolean writable();

    public abstract List<Entry> list(Entry dir) throws IOException;

    public abstract void read(Entry file, Content.Sink out, Fat32Formatter.Progress p,
                              DiskOps.Cancel c) throws IOException;

    /** Akıştaki veriyi yeni bir dosyaya yazar; aynı adda dosya varsa adı değiştirir. */
    public abstract Entry create(Entry dir, String name, InputStream in, long sizeHint,
                                 Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException;

    public abstract Entry mkdir(Entry dir, String name) throws IOException;

    public abstract void delete(Entry e, DiskOps.Cancel c) throws IOException;

    /** Dosyanın kümeleri (sırasıyla). */
    abstract List<Long> fileClusters(Entry e) throws IOException;

    /** Kümenin bölüm içindeki ilk sektörü. */
    abstract long clusterLbaOf(long c);

    abstract int sectorsPerCluster();

    /** Aynı klasörde adı değiştirir. */
    public abstract Entry rename(Entry e, String newName) throws IOException;

    /** Dosyayı akış olarak okur (ardışık kümeler toplu okunur). */
    public java.io.InputStream openStream(final Entry e) throws IOException {
        if (e.dir) throw new IOException("Klasör açılamaz");
        final List<Long> cl = e.size == 0 ? new ArrayList<Long>() : fileClusters(e);
        final int spc = sectorsPerCluster();
        final long cb = spc * 512L;
        if (cl.size() < (e.size + cb - 1) / cb) throw new IOException("Dosya zinciri eksik: " + e.name);
        final int maxRun = (int) Math.max(1, (512 * 1024) / cb);
        return new java.io.InputStream() {
            private final byte[] buf = new byte[(int) (maxRun * cb)];
            private int bufLen;
            private int bufPos;
            private int idx;
            private long left = e.size;

            private boolean fillBuf() throws IOException {
                if (left <= 0 || idx >= cl.size()) return false;
                int run = 1;
                while (run < maxRun && idx + run < cl.size()
                        && cl.get(idx + run) == cl.get(idx) + run && run * cb < left) run++;
                dev.read(clusterLbaOf(cl.get(idx)), buf, run * spc);
                bufLen = (int) Math.min(left, run * cb);
                bufPos = 0;
                left -= bufLen;
                idx += run;
                return true;
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (bufPos >= bufLen && !fillBuf()) return -1;
                int n = Math.min(len, bufLen - bufPos);
                System.arraycopy(buf, bufPos, b, off, n);
                bufPos += n;
                return n;
            }
        };
    }

    /** Dosyanın kaç ayrı parçada durduğu (1 = ardışık). */
    public abstract int fragments(Entry e) throws IOException;

    /** Dosyayı ardışık boş alana taşır; zaten ardışıksa false döner. */
    public abstract boolean defragment(Entry e, Fat32Formatter.Progress p, DiskOps.Cancel c)
            throws IOException;

    static int countRuns(List<Long> clusters) {
        int runs = clusters.isEmpty() ? 0 : 1;
        for (int i = 1; i < clusters.size(); i++) {
            if (clusters.get(i) != clusters.get(i - 1) + 1) runs++;
        }
        return runs;
    }

    /** Eski kümelerden yeni ardışık alana veri kopyalar. */
    void copyClusters(List<Long> from, long to, int spc, long dataLbaOf2, long size,
                      Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        long cb = spc * 512L;
        int maxRun = (int) Math.max(1, (1024 * 1024) / cb);
        byte[] buf = new byte[(int) (maxRun * cb)];
        int i = 0;
        long done = 0;
        while (i < from.size()) {
            if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
            int run = 1;
            while (run < maxRun && i + run < from.size() && from.get(i + run) == from.get(i) + run) {
                run++;
            }
            dev.read(dataLbaOf2 + (from.get(i) - 2) * spc, buf, run * spc);
            dev.write(dataLbaOf2 + (to + i - 2) * spc, buf, run * spc);
            i += run;
            done += run * cb;
            if (p != null) p.onProgress(Math.min(done, size), size);
        }
    }

    // ------------------------------------------------------------ açma

    /** Gösterim için: "Bölüm 2" gibi. */
    public String name = "Kart";

    /** Karttaki ilk FAT/exFAT bölümünü açar. */
    public static CardVolume open(BlockDevice dev) throws IOException {
        return openAll(dev).get(0);
    }

    /** Karttaki bütün FAT/exFAT bölümlerini açar (en az bir tane, yoksa hata). */
    public static List<CardVolume> openAll(BlockDevice dev) throws IOException {
        if (dev.blockSize() != 512) throw new IOException("Desteklenmeyen sektör boyutu");
        byte[] s0 = new byte[512];
        dev.read(0, s0, 1);
        List<CardVolume> found = new ArrayList<>();
        CardVolume v = tryOpen(dev, 0, dev.blockCount(), s0);
        if (v != null) {
            found.add(v);
            return found;
        }
        if ((s0[510] & 0xFF) != 0x55 || (s0[511] & 0xFF) != 0xAA) {
            throw new IOException("Kartta tanınan bir dosya sistemi yok. Önce biçimlendirin.");
        }
        List<long[]> parts = new ArrayList<>();
        if ((s0[450] & 0xFF) == 0xEE) {
            byte[] h = new byte[512];
            dev.read(1, h, 1);
            if (new String(h, 0, 8, StandardCharsets.US_ASCII).equals("EFI PART")) {
                long el = UdfReader.u64(h, 72);
                int n = (int) Math.min(UdfReader.u32(h, 80), 128);
                int es = (int) UdfReader.u32(h, 84);
                if (es >= 128 && es <= 512 && 512 % es == 0) {
                    byte[] sec = new byte[512];
                    for (int i = 0; i < n; i++) {
                        if (i % (512 / es) == 0) dev.read(el + i / (512 / es), sec, 1);
                        int o = (i % (512 / es)) * es;
                        long first = UdfReader.u64(sec, o + 32);
                        long last = UdfReader.u64(sec, o + 40);
                        if (first > 0 && last >= first && last < dev.blockCount()) {
                            parts.add(new long[]{first, last - first + 1});
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < 4; i++) {
                int e = 446 + i * 16;
                int type = s0[e + 4] & 0xFF;
                long start = UdfReader.u32(s0, e + 8);
                long size = UdfReader.u32(s0, e + 12);
                if (type == 0 || type == 0x05 || type == 0x0F || size == 0) continue;
                if (start + size <= dev.blockCount()) parts.add(new long[]{start, size});
            }
        }
        IOException firstError = null;
        for (int i = 0; i < parts.size(); i++) {
            long[] p = parts.get(i);
            byte[] b = new byte[512];
            dev.read(p[0], b, 1);
            try {
                v = tryOpen(dev, p[0], p[1], b);
            } catch (IOException e) {
                if (firstError == null) firstError = e;
                v = null;
            }
            if (v != null) {
                v.name = "Bölüm " + (i + 1);
                found.add(v);
            }
        }
        if (!found.isEmpty()) return found;
        if (firstError != null) throw firstError;
        throw new IOException("Kartta FAT ya da exFAT bölümü bulunamadı (NTFS ve ext4 bölümleri "
                + "dosya yöneticisinde açılamıyor).");
    }

    private static CardVolume tryOpen(BlockDevice dev, long start, long count, byte[] b)
            throws IOException {
        if ((b[510] & 0xFF) != 0x55 || (b[511] & 0xFF) != 0xAA) return null;
        String oem = new String(b, 3, 8, StandardCharsets.US_ASCII);
        BlockDevice part = new OffsetDevice(dev, start, count);
        if (oem.equals("EXFAT   ")) return new ExFatVolume(part);
        int bps = UdfReader.u16(b, 11);
        int spc = b[13] & 0xFF;
        int b0 = b[0] & 0xFF;
        boolean jump = b0 == 0xEB || b0 == 0xE9;
        if (jump && bps == 512 && spc != 0 && (spc & (spc - 1)) == 0
                && UdfReader.u16(b, 14) > 0 && (b[16] & 0xFF) > 0
                && !oem.equals("NTFS    ")) {
            return new FatVolume(part);
        }
        return null;
    }

    /** Kartın bir bölümünü kendi başına bir aygıt gibi gösterir. */
    static final class OffsetDevice implements BlockDevice {
        private final BlockDevice dev;
        private final long start;
        private final long count;

        OffsetDevice(BlockDevice dev, long start, long count) {
            this.dev = dev;
            this.start = start;
            this.count = count;
        }

        @Override
        public int blockSize() {
            return dev.blockSize();
        }

        @Override
        public long blockCount() {
            return count;
        }

        private void check(long lba, int blocks) throws IOException {
            if (lba < 0 || lba + blocks > count) throw new IOException("Bölüm sınırı aşıldı");
        }

        @Override
        public void read(long lba, byte[] buf, int blocks) throws IOException {
            check(lba, blocks);
            dev.read(start + lba, buf, blocks);
        }

        @Override
        public void write(long lba, byte[] buf, int blocks) throws IOException {
            check(lba, blocks);
            dev.write(start + lba, buf, blocks);
        }

        @Override
        public void flush() throws IOException {
            dev.flush();
        }
    }

    // ------------------------------------------------------------ ortak yardımcılar

    /** Yazmadan önce sektörleri bellekte tutan basit önbellek (FAT ve bitmap için). */
    static final class SectorCache {
        private final BlockDevice dev;
        private final Map<Long, byte[]> map = new HashMap<>();
        private final TreeSet<Long> dirty = new TreeSet<>();
        private final long[] mirrors;

        SectorCache(BlockDevice dev, long... mirrors) {
            this.dev = dev;
            this.mirrors = mirrors;
        }

        byte[] get(long lba) throws IOException {
            byte[] b = map.get(lba);
            if (b == null) {
                if (map.size() > 4096) evictClean();
                b = new byte[512];
                dev.read(lba, b, 1);
                map.put(lba, b);
            }
            return b;
        }

        void markDirty(long lba) {
            dirty.add(lba);
        }

        private void evictClean() {
            Iterator<Map.Entry<Long, byte[]>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (!dirty.contains(it.next().getKey())) it.remove();
            }
        }

        void flush() throws IOException {
            if (dirty.isEmpty()) return;
            List<Long> list = new ArrayList<>(dirty);
            int i = 0;
            while (i < list.size()) {
                int j = i;
                while (j + 1 < list.size() && list.get(j + 1) == list.get(j) + 1 && j - i < 127) j++;
                int n = j - i + 1;
                byte[] buf = new byte[n * 512];
                for (int k = 0; k < n; k++) {
                    System.arraycopy(map.get(list.get(i + k)), 0, buf, k * 512, 512);
                }
                dev.write(list.get(i), buf, n);
                for (long m : mirrors) dev.write(list.get(i) + m, buf, n);
                i = j + 1;
            }
            dirty.clear();
        }

        void discard() {
            for (Long l : dirty) map.remove(l);
            dirty.clear();
        }
    }

    /** Ad kurallarını denetler (FAT ve exFAT ortak). */
    static String checkName(String name) throws IOException {
        if (name == null) throw new IOException("Geçersiz ad");
        String n = name.trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty() || n.length() > 255) throw new IOException("Geçersiz ad");
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                throw new IOException("Adda kullanılamayan karakter var: " + c);
            }
        }
        return n;
    }

    /** Klasörde aynı ad varsa "ad (2).uzantı" biçiminde yeni ad üretir. */
    String uniqueName(Entry dir, String name) throws IOException {
        List<Entry> kids = list(dir);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Entry k : kids) names.add(fold(k.name));
        if (!names.contains(fold(name))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!names.contains(fold(cand))) return cand;
        }
        throw new IOException("Klasörde çok fazla aynı adlı dosya var");
    }

    String fold(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    static int dosDate(Calendar c) {
        return ((c.get(Calendar.YEAR) - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5)
                | c.get(Calendar.DAY_OF_MONTH);
    }

    static int dosTime(Calendar c) {
        return (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5)
                | (c.get(Calendar.SECOND) / 2);
    }

    /** Akıştan en çok len bayt okur; akış biterse daha az döner. */
    static int fill(InputStream in, byte[] buf, int len) throws IOException {
        int t = 0;
        while (t < len) {
            int r = in.read(buf, t, len - t);
            if (r < 0) break;
            t += r;
        }
        return t;
    }

    /** Belirli bayt konumlarındaki 32 baytlık dizin girdilerini yazar (sektör sektör). */
    void writeSlots(long[] slots, byte[][] data) throws IOException {
        Map<Long, byte[]> secs = new HashMap<>();
        for (int i = 0; i < slots.length; i++) {
            long lba = slots[i] / 512;
            byte[] s = secs.get(lba);
            if (s == null) {
                s = new byte[512];
                dev.read(lba, s, 1);
                secs.put(lba, s);
            }
            System.arraycopy(data[i], 0, s, (int) (slots[i] % 512), 32);
        }
        for (Map.Entry<Long, byte[]> e : new java.util.TreeMap<>(secs).entrySet()) {
            dev.write(e.getKey(), e.getValue(), 1);
        }
    }

    byte[][] readSlots(long[] slots) throws IOException {
        byte[][] out = new byte[slots.length][32];
        long cached = -1;
        byte[] s = new byte[512];
        for (int i = 0; i < slots.length; i++) {
            long lba = slots[i] / 512;
            if (lba != cached) {
                dev.read(lba, s, 1);
                cached = lba;
            }
            System.arraycopy(s, (int) (slots[i] % 512), out[i], 0, 32);
        }
        return out;
    }
}

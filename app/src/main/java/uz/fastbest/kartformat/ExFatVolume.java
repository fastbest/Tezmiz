package uz.fastbest.kartformat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TreeSet;

/** exFAT bölümlerinde okuma/yazma. */
final class ExFatVolume extends CardVolume {

    private static final int RUN_BYTES = 1024 * 1024;
    private static final long EOC = 0xFFFFFFFFL;

    private final long fatOffset;
    private final long heapOffset;
    private final long clusterCount;
    private final int spc;
    private final boolean canWrite;
    private final SectorCache fat;

    private byte[] bitmap;
    private long bitmapCluster;
    private long bitmapLength;
    private final TreeSet<Long> dirtyBitmap = new TreeSet<>();
    private char[] upcase;
    private long hint = 2;
    private long freeCount = -1;

    ExFatVolume(BlockDevice dev) throws IOException {
        super(dev);
        byte[] b = new byte[512];
        dev.read(0, b, 1);
        if ((b[108] & 0xFF) != 9) throw new IOException("Desteklenmeyen exFAT sektör boyutu");
        fatOffset = UdfReader.u32(b, 80);
        heapOffset = UdfReader.u32(b, 88);
        clusterCount = UdfReader.u32(b, 92);
        int shift = b[109] & 0xFF;
        if (shift > 16) throw new IOException("exFAT bozuk");
        spc = 1 << shift;
        int fats = b[110] & 0xFF;
        canWrite = fats == 1;
        if (heapOffset + clusterCount * spc > dev.blockCount()) {
            throw new IOException("exFAT bölümü karttan büyük görünüyor (sahte kart olabilir)");
        }
        fat = new SectorCache(dev);

        root = new Entry();
        root.name = "";
        root.dir = true;
        root.cluster = UdfReader.u32(b, 96);
        loadSpecial();
    }

    private void loadSpecial() throws IOException {
        long[] slots = dirSlots(root);
        byte[][] all = readSlots(slots);
        long upCluster = 0;
        long upLength = 0;
        for (byte[] e : all) {
            int t = e[0] & 0xFF;
            if (t == 0) break;
            if (t == 0x81 && (e[1] & 1) == 0) {
                bitmapCluster = UdfReader.u32(e, 20);
                bitmapLength = UdfReader.u64(e, 24);
            } else if (t == 0x82) {
                upCluster = UdfReader.u32(e, 20);
                upLength = UdfReader.u64(e, 24);
            }
        }
        if (bitmapCluster < 2 || bitmapLength < (clusterCount + 7) / 8
                || bitmapLength > 64L * 1024 * 1024) {
            throw new IOException("exFAT ayırma tablosu bulunamadı");
        }
        bitmap = readData(bitmapCluster, bitmapLength, false);

        upcase = new char[65536];
        for (int i = 0; i < upcase.length; i++) upcase[i] = (char) i;
        if (upCluster >= 2 && upLength > 0 && upLength <= 256 * 1024) {
            byte[] u = readData(upCluster, upLength, false);
            int idx = 0;
            for (int i = 0; i + 1 < u.length && idx < 65536; i += 2) {
                int ch = UdfReader.u16(u, i);
                if (ch == 0xFFFF && i + 3 < u.length) {
                    idx += UdfReader.u16(u, i + 2);
                    i += 2;
                } else {
                    upcase[idx++] = (char) ch;
                }
            }
        }
    }

    @Override
    public String type() {
        return "exFAT";
    }

    @Override
    public long totalBytes() {
        return clusterCount * spc * 512L;
    }

    @Override
    public boolean writable() {
        return canWrite;
    }

    private long clusterBytes() {
        return spc * 512L;
    }

    private long clusterLba(long c) {
        return heapOffset + (c - 2) * spc;
    }

    private boolean valid(long c) {
        return c >= 2 && c < clusterCount + 2;
    }

    // ------------------------------------------------------------ FAT ve bitmap

    private long next(long c) throws IOException {
        long off = c * 4;
        byte[] s = fat.get(fatOffset + off / 512);
        return UdfReader.u32(s, (int) (off % 512));
    }

    private void set(long c, long v) throws IOException {
        long off = c * 4;
        long lba = fatOffset + off / 512;
        DiskFormatter.putInt(fat.get(lba), (int) (off % 512), (int) v);
        fat.markDirty(lba);
    }

    private boolean used(long c) {
        long i = c - 2;
        return (bitmap[(int) (i >>> 3)] & (1 << (i & 7))) != 0;
    }

    private void mark(long c, boolean on) {
        long i = c - 2;
        int idx = (int) (i >>> 3);
        if (on) bitmap[idx] |= (byte) (1 << (i & 7)); else bitmap[idx] &= (byte) ~(1 << (i & 7));
        dirtyBitmap.add((long) idx / 512);
        if (freeCount >= 0) freeCount += on ? -1 : 1;
    }

    private long allocate() throws IOException {
        long c = hint;
        for (long i = 0; i < clusterCount; i++) {
            if (!valid(c)) c = 2;
            if (!used(c)) {
                mark(c, true);
                set(c, EOC);
                hint = c + 1;
                return c;
            }
            c++;
        }
        throw new IOException("Kartta boş yer kalmadı");
    }

    private List<Long> clusters(long first, long length, boolean contiguous) throws IOException {
        List<Long> out = new ArrayList<>();
        if (!valid(first)) return out;
        long cb = clusterBytes();
        if (contiguous) {
            long n = (length + cb - 1) / cb;
            for (long i = 0; i < n; i++) out.add(first + i);
            return out;
        }
        long c = first;
        while (valid(c)) {
            out.add(c);
            if (out.size() > clusterCount) throw new IOException("exFAT zinciri bozuk (döngü)");
            long n = next(c);
            if (n == EOC || n == 0) break;
            c = n;
        }
        return out;
    }

    private void freeClusters(Entry e) throws IOException {
        for (long c : clusters(e.cluster, e.size, e.contiguous)) {
            if (valid(c)) {
                mark(c, false);
                if (!e.contiguous) set(c, 0);
                if (c < hint) hint = c;
            }
        }
    }

    @Override
    public long freeBytes() {
        if (freeCount < 0) {
            long n = 0;
            for (long c = 2; c < clusterCount + 2; c++) if (!used(c)) n++;
            freeCount = n;
        }
        return freeCount * clusterBytes();
    }

    private void commit() throws IOException {
        fat.flush();
        if (!dirtyBitmap.isEmpty()) {
            List<Long> bms = dataClusters(bitmapCluster, bitmapLength);
            long cb = clusterBytes();
            for (long s : dirtyBitmap) {
                long byteOff = s * 512;
                int ci = (int) (byteOff / cb);
                if (ci >= bms.size()) continue;
                long lba = clusterLba(bms.get(ci)) + (byteOff % cb) / 512;
                byte[] sec = new byte[512];
                int n = (int) Math.min(512, bitmap.length - byteOff);
                System.arraycopy(bitmap, (int) byteOff, sec, 0, n);
                dev.write(lba, sec, 1);
            }
            dirtyBitmap.clear();
        }
        dev.flush();
    }

    /** Bitmap ve büyük harf tablosu için: FAT zinciri eksikse kümeler ardışık kabul edilir. */
    private List<Long> dataClusters(long first, long length) throws IOException {
        List<Long> cl = clusters(first, length, false);
        long need = (length + clusterBytes() - 1) / clusterBytes();
        return cl.size() >= need ? cl : clusters(first, length, true);
    }

    private byte[] readData(long first, long length, boolean contiguous) throws IOException {
        byte[] out = new byte[(int) length];
        List<Long> cl = contiguous ? clusters(first, length, true) : dataClusters(first, length);
        long cb = clusterBytes();
        byte[] buf = new byte[(int) cb];
        long pos = 0;
        for (long c : cl) {
            if (pos >= length) break;
            dev.read(clusterLba(c), buf, spc);
            int n = (int) Math.min(cb, length - pos);
            System.arraycopy(buf, 0, out, (int) pos, n);
            pos += n;
        }
        if (pos < length) throw new IOException("exFAT verisi eksik");
        return out;
    }

    // ------------------------------------------------------------ dizinler

    private long[] dirSlots(Entry dir) throws IOException {
        List<Long> cl = dir == root ? clusters(dir.cluster, 0, false)
                : clusters(dir.cluster, dir.size, dir.contiguous);
        long per = clusterBytes() / 32;
        long[] slots = new long[(int) (cl.size() * per)];
        int k = 0;
        for (long c : cl) {
            long base = clusterLba(c) * 512;
            for (int i = 0; i < per; i++) slots[k++] = base + i * 32L;
        }
        return slots;
    }

    @Override
    public List<Entry> list(Entry dir) throws IOException {
        long[] slots = dirSlots(dir);
        byte[][] all = readSlots(slots);
        List<Entry> out = new ArrayList<>();
        int i = 0;
        while (i < all.length) {
            int t = all[i][0] & 0xFF;
            if (t == 0) break;
            if (t != 0x85) {
                i++;
                continue;
            }
            int sec = all[i][1] & 0xFF;
            if (sec < 2 || i + sec >= all.length) {
                i++;
                continue;
            }
            byte[] st = all[i + 1];
            if ((st[0] & 0xFF) != 0xC0) {
                i++;
                continue;
            }
            int nameLen = st[3] & 0xFF;
            StringBuilder name = new StringBuilder();
            for (int k = 2; k <= sec && name.length() < nameLen; k++) {
                byte[] fn = all[i + k];
                if ((fn[0] & 0xFF) != 0xC1) break;
                for (int j = 0; j < 15 && name.length() < nameLen; j++) {
                    name.append((char) UdfReader.u16(fn, 2 + j * 2));
                }
            }
            Entry e = new Entry();
            e.name = name.toString();
            e.dir = (UdfReader.u16(all[i], 4) & 0x10) != 0;
            e.contiguous = (st[1] & 0x02) != 0;
            e.cluster = UdfReader.u32(st, 20);
            e.size = UdfReader.u64(st, 24);
            e.parent = dir;
            e.slots = Arrays.copyOfRange(slots, i, i + sec + 1);
            if (!e.name.isEmpty()) out.add(e);
            i += sec + 1;
        }
        return out;
    }

    @Override
    String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) sb.append(upcase[s.charAt(i)]);
        return sb.toString();
    }

    private int nameHash(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = upcase[name.charAt(i)];
            h = (((h & 1) != 0 ? 0x8000 : 0) + (h >>> 1) + (c & 0xFF)) & 0xFFFF;
            h = (((h & 1) != 0 ? 0x8000 : 0) + (h >>> 1) + (c >>> 8)) & 0xFFFF;
        }
        return h;
    }

    static int setChecksum(byte[][] set) {
        int sum = 0;
        for (int k = 0; k < set.length; k++) {
            for (int i = 0; i < 32; i++) {
                if (k == 0 && (i == 2 || i == 3)) continue;
                sum = (((sum & 1) != 0 ? 0x8000 : 0) + (sum >>> 1) + (set[k][i] & 0xFF)) & 0xFFFF;
            }
        }
        return sum;
    }

    private byte[][] entrySet(String name, boolean isDir, long cluster, long size) {
        int names = (name.length() + 14) / 15;
        byte[][] set = new byte[2 + names][32];
        Calendar c = Calendar.getInstance();
        int ts = (dosDate(c) << 16) | dosTime(c);
        byte[] f = set[0];
        f[0] = (byte) 0x85;
        f[1] = (byte) (1 + names);
        DiskFormatter.putShort(f, 4, isDir ? 0x10 : 0x20);
        DiskFormatter.putInt(f, 8, ts);
        DiskFormatter.putInt(f, 12, ts);
        DiskFormatter.putInt(f, 16, ts);
        byte[] s = set[1];
        s[0] = (byte) 0xC0;
        s[1] = 0x01; // ayırma mümkün, FAT zinciri kullanılıyor
        s[3] = (byte) name.length();
        DiskFormatter.putShort(s, 4, nameHash(name));
        DiskFormatter.putLong(s, 8, size);
        DiskFormatter.putInt(s, 20, (int) cluster);
        DiskFormatter.putLong(s, 24, size);
        for (int k = 0; k < names; k++) {
            byte[] n = set[2 + k];
            n[0] = (byte) 0xC1;
            for (int j = 0; j < 15; j++) {
                int idx = k * 15 + j;
                if (idx < name.length()) DiskFormatter.putShort(n, 2 + j * 2, name.charAt(idx));
            }
        }
        DiskFormatter.putShort(f, 2, setChecksum(set));
        return set;
    }

    private long[] freeSlots(Entry dir, int n) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long[] slots = dirSlots(dir);
            byte[][] all = readSlots(slots);
            int run = 0;
            for (int i = 0; i < all.length; i++) {
                int t = all[i][0] & 0xFF;
                if (t == 0) {
                    int start = i - run;
                    if (all.length - start >= n) return Arrays.copyOfRange(slots, start, start + n);
                    break;
                }
                run = t < 0x80 ? run + 1 : 0;
                if (run == n) return Arrays.copyOfRange(slots, i - n + 1, i + 1);
            }
            growDir(dir);
        }
        throw new IOException("Klasöre girdi eklenemedi");
    }

    /** Dizine sıfırlanmış bir küme ekler ve dizin girdisindeki boyutu günceller. */
    private void growDir(Entry dir) throws IOException {
        List<Long> cl = dir == root ? clusters(dir.cluster, 0, false)
                : clusters(dir.cluster, dir.size, dir.contiguous);
        if (dir.contiguous) {
            for (int i = 0; i < cl.size(); i++) {
                set(cl.get(i), i == cl.size() - 1 ? EOC : cl.get(i + 1));
            }
            dir.contiguous = false;
        }
        long add = allocate();
        dev.write(clusterLba(add), new byte[spc * 512], spc);
        set(cl.get(cl.size() - 1), add);
        fat.flush();
        if (dir != root) {
            dir.size += clusterBytes();
            byte[][] set = readSlots(dir.slots);
            set[1][1] = (byte) (set[1][1] & ~0x02);
            DiskFormatter.putLong(set[1], 8, dir.size);
            DiskFormatter.putLong(set[1], 24, dir.size);
            DiskFormatter.putShort(set[0], 2, setChecksum(set));
            writeSlots(dir.slots, set);
        }
    }

    private Entry addEntry(Entry dir, String name, boolean isDir, long cluster, long size)
            throws IOException {
        byte[][] set = entrySet(name, isDir, cluster, size);
        long[] slots = freeSlots(dir, set.length);
        writeSlots(slots, set);
        Entry e = new Entry();
        e.name = name;
        e.dir = isDir;
        e.size = size;
        e.cluster = cluster;
        e.parent = dir;
        e.slots = slots;
        return e;
    }

    // ------------------------------------------------------------ dosya işlemleri

    @Override
    public void read(Entry f, Content.Sink out, Fat32Formatter.Progress p, DiskOps.Cancel c)
            throws IOException {
        long left = f.size;
        if (left == 0) return;
        List<Long> ch = clusters(f.cluster, f.size, f.contiguous);
        long cb = clusterBytes();
        if (ch.size() < (left + cb - 1) / cb) throw new IOException("Dosya zinciri eksik: " + f.name);
        int maxRun = (int) Math.max(1, RUN_BYTES / cb);
        byte[] buf = new byte[(int) (maxRun * cb)];
        int i = 0;
        long done = 0;
        while (left > 0) {
            if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
            int run = 1;
            while (run < maxRun && i + run < ch.size() && ch.get(i + run) == ch.get(i) + run
                    && run * cb < left) run++;
            dev.read(clusterLba(ch.get(i)), buf, run * spc);
            int n = (int) Math.min(left, run * cb);
            out.write(buf, 0, n);
            left -= n;
            done += n;
            i += run;
            if (p != null) p.onProgress(done, f.size);
        }
    }

    private void requireWritable() throws IOException {
        if (!canWrite) throw new IOException("Bu exFAT bölümüne yazılamıyor (TexFAT)");
    }

    @Override
    public Entry create(Entry dir, String name, InputStream in, long sizeHint,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        requireWritable();
        name = uniqueName(dir, checkName(name));
        if (sizeHint > 0 && freeBytes() < sizeHint) throw new IOException("Kartta yeterli yer yok");
        long cb = clusterBytes();
        int maxRun = (int) Math.max(1, RUN_BYTES / cb);
        byte[] buf = new byte[(int) (maxRun * cb)];
        List<Long> owned = new ArrayList<>();
        long first = 0;
        long prev = 0;
        long size = 0;
        try {
            while (true) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                int n = fill(in, buf, buf.length);
                if (n <= 0) break;
                int count = (int) ((n + cb - 1) / cb);
                if (n < count * cb) Arrays.fill(buf, n, (int) (count * cb), (byte) 0);
                int k = 0;
                while (k < count) {
                    long start = allocate();
                    owned.add(start);
                    if (prev != 0) set(prev, start); else first = start;
                    prev = start;
                    int run = 1;
                    while (k + run < count && valid(start + run) && !used(start + run)) {
                        long cc = start + run;
                        mark(cc, true);
                        set(cc, EOC);
                        set(prev, cc);
                        prev = cc;
                        owned.add(cc);
                        run++;
                    }
                    if (prev + 1 > hint) hint = prev + 1;
                    byte[] part = k == 0 && run == count ? buf
                            : Arrays.copyOfRange(buf, (int) (k * cb), (int) ((k + run) * cb));
                    dev.write(clusterLba(start), part, run * spc);
                    k += run;
                }
                size += n;
                if (p != null) p.onProgress(size, Math.max(size, sizeHint));
                if (n < buf.length) break;
            }
            commit();
            Entry e = addEntry(dir, name, false, first, size);
            commit();
            return e;
        } catch (IOException | RuntimeException ex) {
            try {
                for (long cl : owned) {
                    mark(cl, false);
                    set(cl, 0);
                }
                commit();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    @Override
    public Entry mkdir(Entry dir, String name) throws IOException {
        requireWritable();
        name = uniqueName(dir, checkName(name));
        long c = allocate();
        try {
            dev.write(clusterLba(c), new byte[spc * 512], spc);
            commit();
            Entry e = addEntry(dir, name, true, c, clusterBytes());
            commit();
            return e;
        } catch (IOException | RuntimeException ex) {
            try {
                mark(c, false);
                set(c, 0);
                commit();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    @Override
    public void delete(Entry e, DiskOps.Cancel c) throws IOException {
        requireWritable();
        deleteRec(e, c, 0);
        commit();
    }

    private void deleteRec(Entry e, DiskOps.Cancel c, int depth) throws IOException {
        if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
        if (e.parent == null) throw new IOException("Kök klasör silinemez");
        if (depth > 64) throw new IOException("Klasör yapısı çok derin");
        if (e.dir) {
            for (Entry k : list(e)) deleteRec(k, c, depth + 1);
        }
        byte[][] set = readSlots(e.slots);
        for (byte[] s : set) s[0] = (byte) (s[0] & 0x7F);
        writeSlots(e.slots, set);
        freeClusters(e);
        commit();
    }
}

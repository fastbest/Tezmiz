package uz.fastbest.kartformat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** FAT12, FAT16 ve FAT32 bölümlerinde okuma/yazma. */
final class FatVolume extends CardVolume {

    private static final int RUN_BYTES = 1024 * 1024;

    private final int bits;
    private final int spc;
    private final long fatStart;
    private final long fatSize;
    private final int numFats;
    private final long rootStart;     // FAT12/16 sabit kök dizin
    private final long rootSectors;
    private final long dataStart;
    private final long clusterCount;
    private final long totalSectors;
    private final int fsInfoSector;
    private final SectorCache fat;
    private long freeCount = -1;
    private long hint = 2;

    FatVolume(BlockDevice dev) throws IOException {
        super(dev);
        byte[] b = new byte[512];
        dev.read(0, b, 1);
        spc = b[13] & 0xFF;
        int reserved = UdfReader.u16(b, 14);
        numFats = b[16] & 0xFF;
        int rootEntries = UdfReader.u16(b, 17);
        long ts = UdfReader.u16(b, 19);
        if (ts == 0) ts = UdfReader.u32(b, 32);
        long fs = UdfReader.u16(b, 22);
        if (fs == 0) fs = UdfReader.u32(b, 36);
        totalSectors = Math.min(ts, dev.blockCount());
        fatStart = reserved;
        fatSize = fs;
        rootSectors = (rootEntries * 32L + 511) / 512;
        rootStart = fatStart + numFats * fatSize;
        dataStart = rootStart + rootSectors;
        if (fs == 0 || dataStart >= totalSectors) throw new IOException("FAT bölümü bozuk");
        clusterCount = (totalSectors - dataStart) / spc;
        bits = clusterCount < 4085 ? 12 : clusterCount < 65525 ? 16 : 32;
        long[] mirrors = new long[Math.max(0, numFats - 1)];
        for (int i = 1; i < numFats; i++) mirrors[i - 1] = i * fatSize;
        fat = new SectorCache(dev, mirrors);

        root = new Entry();
        root.name = "";
        root.dir = true;
        if (bits == 32) {
            root.cluster = UdfReader.u32(b, 44);
            fsInfoSector = UdfReader.u16(b, 48);
            if (fsInfoSector > 0 && fsInfoSector < reserved) {
                byte[] fi = new byte[512];
                dev.read(fsInfoSector, fi, 1);
                if (UdfReader.u32(fi, 0) == 0x41615252L && UdfReader.u32(fi, 484) == 0x61417272L) {
                    long free = UdfReader.u32(fi, 488);
                    long next = UdfReader.u32(fi, 492);
                    if (free <= clusterCount) freeCount = free;
                    if (next >= 2 && next < clusterCount + 2) hint = next;
                }
            }
        } else {
            root.cluster = 0;
            fsInfoSector = 0;
        }
    }

    @Override
    public String type() {
        return "FAT" + bits;
    }

    @Override
    public long totalBytes() {
        return clusterCount * spc * 512L;
    }

    @Override
    public boolean writable() {
        return true;
    }

    private long clusterBytes() {
        return spc * 512L;
    }

    private long clusterLba(long c) {
        return dataStart + (c - 2) * spc;
    }

    // ------------------------------------------------------------ FAT erişimi

    private int fatByte(long off) throws IOException {
        return fat.get(fatStart + off / 512)[(int) (off % 512)] & 0xFF;
    }

    private void setFatByte(long off, int v) throws IOException {
        long lba = fatStart + off / 512;
        fat.get(lba)[(int) (off % 512)] = (byte) v;
        fat.markDirty(lba);
    }

    long next(long c) throws IOException {
        if (bits == 12) {
            long off = c + c / 2;
            int v = fatByte(off) | (fatByte(off + 1) << 8);
            return (c & 1) != 0 ? v >>> 4 : v & 0xFFF;
        }
        if (bits == 16) return fatByte(c * 2) | (fatByte(c * 2 + 1) << 8);
        long off = c * 4;
        return (fatByte(off) | ((long) fatByte(off + 1) << 8) | ((long) fatByte(off + 2) << 16)
                | ((long) fatByte(off + 3) << 24)) & 0x0FFFFFFFL;
    }

    private void set(long c, long v) throws IOException {
        if (bits == 12) {
            long off = c + c / 2;
            int cur = fatByte(off) | (fatByte(off + 1) << 8);
            int nv = (c & 1) != 0 ? (cur & 0x000F) | ((int) (v & 0xFFF) << 4)
                    : (cur & 0xF000) | (int) (v & 0xFFF);
            setFatByte(off, nv & 0xFF);
            setFatByte(off + 1, (nv >>> 8) & 0xFF);
        } else if (bits == 16) {
            setFatByte(c * 2, (int) v & 0xFF);
            setFatByte(c * 2 + 1, (int) (v >>> 8) & 0xFF);
        } else {
            long off = c * 4;
            int top = fatByte(off + 3) & 0xF0;
            setFatByte(off, (int) v & 0xFF);
            setFatByte(off + 1, (int) (v >>> 8) & 0xFF);
            setFatByte(off + 2, (int) (v >>> 16) & 0xFF);
            setFatByte(off + 3, top | ((int) (v >>> 24) & 0x0F));
        }
    }

    private long eoc() {
        return bits == 12 ? 0xFFF : bits == 16 ? 0xFFFF : 0x0FFFFFFF;
    }

    private boolean isEnd(long v) {
        return v >= (bits == 12 ? 0xFF8 : bits == 16 ? 0xFFF8 : 0x0FFFFFF8L);
    }

    private boolean valid(long c) {
        return c >= 2 && c < clusterCount + 2;
    }

    private List<Long> chain(long first) throws IOException {
        List<Long> out = new ArrayList<>();
        long c = first;
        while (valid(c)) {
            out.add(c);
            if (out.size() > clusterCount) throw new IOException("FAT zinciri bozuk (döngü)");
            long n = next(c);
            if (isEnd(n) || n == 0) break;
            c = n;
        }
        return out;
    }

    private long allocate() throws IOException {
        long c = hint;
        for (long i = 0; i < clusterCount; i++) {
            if (!valid(c)) c = 2;
            if (next(c) == 0) {
                set(c, eoc());
                hint = c + 1;
                if (freeCount > 0) freeCount--;
                return c;
            }
            c++;
        }
        throw new IOException("Kartta boş yer kalmadı");
    }

    private void freeChain(long first) throws IOException {
        for (long c : chain(first)) {
            set(c, 0);
            if (freeCount >= 0) freeCount++;
        }
        if (valid(first) && first < hint) hint = first;
    }

    @Override
    public long freeBytes() throws IOException {
        if (freeCount < 0) {
            long n = 0;
            for (long c = 2; c < clusterCount + 2; c++) {
                if (next(c) == 0) n++;
            }
            freeCount = n;
        }
        return freeCount * clusterBytes();
    }

    private void commit() throws IOException {
        fat.flush();
        if (bits == 32 && fsInfoSector > 0) {
            byte[] fi = new byte[512];
            dev.read(fsInfoSector, fi, 1);
            if (UdfReader.u32(fi, 0) == 0x41615252L) {
                DiskFormatter.putInt(fi, 488, freeCount >= 0 ? (int) freeCount : -1);
                DiskFormatter.putInt(fi, 492, (int) hint);
                dev.write(fsInfoSector, fi, 1);
            }
        }
        dev.flush();
    }

    // ------------------------------------------------------------ dizinler

    /** Dizinin 32 baytlık girdi konumları (bölüm içi bayt). */
    private long[] dirSlots(Entry dir) throws IOException {
        List<Long> lbas = new ArrayList<>();
        if (dir.cluster == 0 && bits != 32) {
            for (long s = 0; s < rootSectors; s++) lbas.add(rootStart + s);
        } else {
            for (long c : chain(dir.cluster)) {
                for (int s = 0; s < spc; s++) lbas.add(clusterLba(c) + s);
            }
        }
        long[] slots = new long[lbas.size() * 16];
        int k = 0;
        for (long lba : lbas) {
            for (int i = 0; i < 16; i++) slots[k++] = lba * 512 + i * 32;
        }
        return slots;
    }

    private static final class Raw {
        Entry entry;
        byte[] shortName;
    }

    private List<Raw> scan(Entry dir) throws IOException {
        long[] slots = dirSlots(dir);
        List<Raw> out = new ArrayList<>();
        byte[] sec = new byte[512];
        long cached = -1;
        List<byte[]> lfn = new ArrayList<>();
        List<Long> lfnSlots = new ArrayList<>();
        for (long pos : slots) {
            long lba = pos / 512;
            if (lba != cached) {
                dev.read(lba, sec, 1);
                cached = lba;
            }
            int o = (int) (pos % 512);
            int first = sec[o] & 0xFF;
            if (first == 0) break;
            if (first == 0xE5) {
                lfn.clear();
                lfnSlots.clear();
                continue;
            }
            int attr = sec[o + 11] & 0xFF;
            if (attr == 0x0F) {
                if ((first & 0x40) != 0) {
                    lfn.clear();
                    lfnSlots.clear();
                }
                lfn.add(Arrays.copyOfRange(sec, o, o + 32));
                lfnSlots.add(pos);
                continue;
            }
            byte[] sn = Arrays.copyOfRange(sec, o, o + 11);
            if ((attr & 0x08) != 0 || sn[0] == '.') {
                lfn.clear();
                lfnSlots.clear();
                continue;
            }
            String name = null;
            if (!lfn.isEmpty() && (lfn.get(0)[13] & 0xFF) == Fat32Builder.checksum(sn)) {
                StringBuilder sb = new StringBuilder();
                for (int i = lfn.size() - 1; i >= 0; i--) {
                    byte[] e = lfn.get(i);
                    int[] at = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
                    for (int a : at) {
                        char ch = (char) UdfReader.u16(e, a);
                        if (ch == 0) break;
                        if (ch != 0xFFFF) sb.append(ch);
                    }
                }
                name = sb.toString();
            } else {
                lfnSlots.clear();
            }
            if (name == null || name.isEmpty()) name = shortToString(sn, sec[o + 12] & 0xFF);
            Entry e = new Entry();
            e.name = name;
            e.dir = (attr & 0x10) != 0;
            e.size = e.dir ? 0 : UdfReader.u32(sec, o + 28);
            e.cluster = ((long) UdfReader.u16(sec, o + 20) << 16 | UdfReader.u16(sec, o + 26));
            if (bits != 32) e.cluster &= 0xFFFF;
            e.parent = dir;
            long[] s = new long[lfnSlots.size() + 1];
            for (int i = 0; i < lfnSlots.size(); i++) s[i] = lfnSlots.get(i);
            s[s.length - 1] = pos;
            e.slots = s;
            Raw r = new Raw();
            r.entry = e;
            r.shortName = sn;
            out.add(r);
            lfn.clear();
            lfnSlots.clear();
        }
        return out;
    }

    private static String shortToString(byte[] sn, int ntFlags) {
        String base = new String(sn, 0, 8, StandardCharsets.ISO_8859_1).trim();
        String ext = new String(sn, 8, 3, StandardCharsets.ISO_8859_1).trim();
        if (base.startsWith("\u0005")) base = "å" + base.substring(1);
        if ((ntFlags & 0x08) != 0) base = base.toLowerCase(java.util.Locale.ROOT);
        if ((ntFlags & 0x10) != 0) ext = ext.toLowerCase(java.util.Locale.ROOT);
        return ext.isEmpty() ? base : base + "." + ext;
    }

    @Override
    public List<Entry> list(Entry dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Raw r : scan(dir)) out.add(r.entry);
        return out;
    }

    /** Dizinde n ardışık boş girdi bulur; yoksa dizini büyütür. */
    private long[] freeSlots(Entry dir, int n) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long[] slots = dirSlots(dir);
            byte[] sec = new byte[512];
            long cached = -1;
            int run = 0;
            for (int i = 0; i < slots.length; i++) {
                long lba = slots[i] / 512;
                if (lba != cached) {
                    dev.read(lba, sec, 1);
                    cached = lba;
                }
                int first = sec[(int) (slots[i] % 512)] & 0xFF;
                if (first == 0) {
                    // Sonrası zaten boş; sığıyorsa al
                    int start = i - run;
                    if (slots.length - start >= n) return Arrays.copyOfRange(slots, start, start + n);
                    break;
                }
                run = first == 0xE5 ? run + 1 : 0;
                if (run == n) return Arrays.copyOfRange(slots, i - n + 1, i + 1);
            }
            if (dir.cluster == 0 && bits != 32) throw new IOException("Kök klasör dolu");
            // Dizine yeni (sıfırlanmış) küme ekle
            List<Long> ch = chain(dir.cluster);
            long add = allocate();
            zeroCluster(add);
            set(ch.get(ch.size() - 1), add);
        }
        throw new IOException("Klasöre girdi eklenemedi");
    }

    private void zeroCluster(long c) throws IOException {
        dev.write(clusterLba(c), new byte[spc * 512], spc);
    }

    private byte[][] makeEntries(Entry dir, String name, int attr, long cluster, long size)
            throws IOException {
        Set<String> used = new HashSet<>();
        for (Raw r : scan(dir)) used.add(new String(r.shortName, StandardCharsets.ISO_8859_1));
        byte[][] names = Fat32Builder.fatNames(name, used);
        int lfnCount = names[1].length / 32;
        byte[][] out = new byte[lfnCount + 1][];
        for (int i = 0; i < lfnCount; i++) out[i] = Arrays.copyOfRange(names[1], i * 32, i * 32 + 32);
        byte[] e = new byte[32];
        System.arraycopy(names[0], 0, e, 0, 11);
        e[11] = (byte) attr;
        Calendar c = Calendar.getInstance();
        int d = dosDate(c);
        int t = dosTime(c);
        DiskFormatter.putShort(e, 14, t);
        DiskFormatter.putShort(e, 16, d);
        DiskFormatter.putShort(e, 18, d);
        DiskFormatter.putShort(e, 20, (int) (cluster >>> 16));
        DiskFormatter.putShort(e, 22, t);
        DiskFormatter.putShort(e, 24, d);
        DiskFormatter.putShort(e, 26, (int) cluster);
        DiskFormatter.putInt(e, 28, (int) size);
        out[lfnCount] = e;
        return out;
    }

    private Entry addEntry(Entry dir, String name, boolean isDir, long cluster, long size)
            throws IOException {
        byte[][] entries = makeEntries(dir, name, isDir ? 0x10 : 0x20, cluster, size);
        long[] slots = freeSlots(dir, entries.length);
        fat.flush(); // dizin büyüdüyse zincir önce yazılsın
        writeSlots(slots, entries);
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
        List<Long> ch = chain(f.cluster);
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

    @Override
    public Entry create(Entry dir, String name, InputStream in, long sizeHint,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        name = uniqueName(dir, checkName(name));
        long cb = clusterBytes();
        if (sizeHint > Fat32Builder.MAX_FILE) {
            throw new IOException(name + " 4 GB'tan büyük; FAT kartlara yazılamaz (exFAT gerekir)");
        }
        if (sizeHint > 0 && freeBytes() < sizeHint) throw new IOException("Kartta yeterli yer yok");
        int maxRun = (int) Math.max(1, RUN_BYTES / cb);
        byte[] buf = new byte[(int) (maxRun * cb)];
        long first = 0;
        long prev = 0;
        long size = 0;
        try {
            while (true) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                int n = fill(in, buf, buf.length);
                if (n <= 0) break;
                if (size + n > Fat32Builder.MAX_FILE) {
                    throw new IOException(name + " 4 GB'tan büyük; FAT kartlara yazılamaz");
                }
                int clusters = (int) ((n + cb - 1) / cb);
                if (n < clusters * cb) Arrays.fill(buf, n, (int) (clusters * cb), (byte) 0);
                int k = 0;
                while (k < clusters) {
                    long start = allocate();
                    if (prev != 0) set(prev, start); else first = start;
                    prev = start;
                    int run = 1;
                    while (k + run < clusters && valid(start + run) && next(start + run) == 0) {
                        set(start + run, eoc());
                        set(prev, start + run);
                        prev = start + run;
                        if (freeCount > 0) freeCount--;
                        run++;
                    }
                    if (prev + 1 > hint) hint = prev + 1;
                    byte[] part = k == 0 && run == clusters ? buf
                            : Arrays.copyOfRange(buf, (int) (k * cb), (int) ((k + run) * cb));
                    dev.write(clusterLba(start), part, run * spc);
                    k += run;
                }
                size += n;
                if (p != null) p.onProgress(size, Math.max(size, sizeHint));
                if (n < buf.length) break;
            }
            Entry e = addEntry(dir, name, false, first, size);
            commit();
            return e;
        } catch (IOException | RuntimeException ex) {
            try {
                if (first != 0) freeChain(first);
                commit();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    @Override
    public Entry mkdir(Entry dir, String name) throws IOException {
        name = uniqueName(dir, checkName(name));
        long c = allocate();
        try {
            byte[] data = new byte[spc * 512];
            Calendar cal = Calendar.getInstance();
            dotEntry(data, 0, ".          ", c, cal);
            long up = dir.cluster == 0 || (bits == 32 && dir == root) ? 0 : dir.cluster;
            dotEntry(data, 32, "..         ", up, cal);
            dev.write(clusterLba(c), data, spc);
            Entry e = addEntry(dir, name, true, c, 0);
            commit();
            return e;
        } catch (IOException | RuntimeException ex) {
            try {
                freeChain(c);
                commit();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    private void dotEntry(byte[] b, int o, String n, long cluster, Calendar cal) {
        System.arraycopy(n.getBytes(StandardCharsets.US_ASCII), 0, b, o, 11);
        b[o + 11] = 0x10;
        DiskFormatter.putShort(b, o + 20, (int) (cluster >>> 16));
        DiskFormatter.putShort(b, o + 22, dosTime(cal));
        DiskFormatter.putShort(b, o + 24, dosDate(cal));
        DiskFormatter.putShort(b, o + 26, (int) cluster);
    }

    @Override
    public void delete(Entry e, DiskOps.Cancel c) throws IOException {
        deleteRec(e, c, 0);
        commit();
    }

    @Override
    List<Long> fileClusters(Entry e) throws IOException {
        return valid(e.cluster) ? chain(e.cluster) : new ArrayList<Long>();
    }

    @Override
    long clusterLbaOf(long c) {
        return clusterLba(c);
    }

    @Override
    int sectorsPerCluster() {
        return spc;
    }

    @Override
    public Entry rename(Entry e, String newName) throws IOException {
        if (e.parent == null) throw new IOException("Kök klasör yeniden adlandırılamaz");
        newName = checkName(newName);
        for (Entry k : list(e.parent)) {
            if (k != e && fold(k.name).equals(fold(newName)) && !sameSlots(k, e)) {
                throw new IOException("Bu adda bir öğe zaten var");
            }
        }
        byte[][] old = readSlots(e.slots);
        byte[] oldShort = old[old.length - 1];
        // Eski girdileri önce sil ki kısa ad çakışmasın; yenisi yazılamazsa geri yaz
        byte[][] del = new byte[old.length][];
        for (int i = 0; i < old.length; i++) {
            del[i] = old[i].clone();
            del[i][0] = (byte) 0xE5;
        }
        writeSlots(e.slots, del);
        try {
            byte[][] entries = makeEntries(e.parent, newName, oldShort[11] & 0xFF, e.cluster,
                    e.dir ? 0 : e.size);
            byte[] sh = entries[entries.length - 1];
            System.arraycopy(oldShort, 12, sh, 12, 14);   // tarih ve saatler korunur
            DiskFormatter.putShort(sh, 20, (int) (e.cluster >>> 16));
            DiskFormatter.putShort(sh, 26, (int) e.cluster);
            long[] slots = freeSlots(e.parent, entries.length);
            fat.flush();
            writeSlots(slots, entries);
            commit();
            Entry n = new Entry();
            n.name = newName;
            n.dir = e.dir;
            n.size = e.size;
            n.cluster = e.cluster;
            n.parent = e.parent;
            n.slots = slots;
            return n;
        } catch (IOException | RuntimeException ex) {
            writeSlots(e.slots, old);
            throw ex;
        }
    }

    private static boolean sameSlots(Entry a, Entry b) {
        return a.slots != null && b.slots != null
                && a.slots[a.slots.length - 1] == b.slots[b.slots.length - 1];
    }

    @Override
    public int fragments(Entry e) throws IOException {
        return valid(e.cluster) ? countRuns(chain(e.cluster)) : 0;
    }

    private long findRun(long n) throws IOException {
        long run = 0;
        for (long c = 2; c < clusterCount + 2; c++) {
            if (next(c) == 0) {
                if (++run == n) return c - n + 1;
            } else {
                run = 0;
            }
        }
        return -1;
    }

    @Override
    public boolean defragment(Entry e, Fat32Formatter.Progress p, DiskOps.Cancel c)
            throws IOException {
        if (e.dir || !valid(e.cluster)) return false;
        List<Long> old = chain(e.cluster);
        if (countRuns(old) <= 1) return false;
        long n = old.size();
        long to = findRun(n);
        if (to < 0) throw new IOException(e.name + ": kartta yeterli ardışık boş alan yok");
        try {
            for (long i = 0; i < n; i++) set(to + i, i == n - 1 ? eoc() : to + i + 1);
            if (freeCount >= 0) freeCount -= n;
            fat.flush();
            copyClusters(old, to, spc, dataStart, e.size, p, c);
        } catch (IOException | RuntimeException ex) {
            for (long i = 0; i < n; i++) set(to + i, 0);
            if (freeCount >= 0) freeCount += n;
            commit();
            throw ex;
        }
        byte[][] slots = readSlots(e.slots);
        byte[] sh = slots[slots.length - 1];
        DiskFormatter.putShort(sh, 20, (int) (to >>> 16));
        DiskFormatter.putShort(sh, 26, (int) to);
        writeSlots(new long[]{e.slots[e.slots.length - 1]}, new byte[][]{sh});
        long oldFirst = e.cluster;
        e.cluster = to;
        freeChain(oldFirst);
        commit();
        return true;
    }

    private void deleteRec(Entry e, DiskOps.Cancel c, int depth) throws IOException {
        if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
        if (e.parent == null) throw new IOException("Kök klasör silinemez");
        if (depth > 64) throw new IOException("Klasör yapısı çok derin");
        if (e.dir) {
            for (Entry k : list(e)) deleteRec(k, c, depth + 1);
        }
        byte[][] data = readSlots(e.slots);
        for (byte[] d : data) d[0] = (byte) 0xE5;
        writeSlots(e.slots, data);
        if (valid(e.cluster)) freeChain(e.cluster);
        fat.flush();
    }
}

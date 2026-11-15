package uz.fastbest.kartformat;

import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.ICryptoGetTextPassword;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 7-Zip motoruyla arşivlere göz atma ve çıkarma: RAR (RAR5 dahil), 7z, ZIP, TAR, GZ, BZ2, XZ,
 * CAB, ARJ, LZH, WIM, CPIO, RPM, DEB, DMG, ISO… Parolalı arşivler desteklenir. Salt okunur.
 */
public final class SevenFs implements Vfs {

    /** Parola gerektiğinde fırlatılır; arayüz parola sorup yeniden dener. */
    public static final class PasswordNeeded extends IOException {
        public final boolean wrong;

        PasswordNeeded(boolean wrong) {
            super(wrong ? "Parola yanlış" : "Bu arşiv parolalı");
            this.wrong = wrong;
        }
    }

    /** Bu motorla açılan uzantılar. */
    public static final String[] EXTS = {"rar", "7z", "zip", "jar", "apk", "aar", "epub", "tar", "gz", "tgz",
            "bz2", "tbz", "tbz2", "xz", "txz", "lzma", "tlz", "z", "taz", "cab", "arj", "lzh", "lha", "wim",
            "swm", "cpio", "rpm", "deb", "dmg", "iso", "vhd", "chm", "msi", "zst", "001", "r00", "cbz", "cbr",
            "cb7", "xar", "squashfs", "udf", "fat", "ntfs", "hfs", "mbr", "ext", "ext4", "qcow2", "vmdk", "vdi"};

    public static boolean handles(String ext) {
        for (String e : EXTS) if (e.equals(ext)) return true;
        return false;
    }

    private final File file;
    private final File cacheDir;
    private final String title;
    private final String password;
    private final RandomAccessFile raf;
    private final IInArchive arc;
    private final VNode root;
    private final Map<String, List<VNode>> kids = new HashMap<>();
    private final Map<String, VNode> dirs = new HashMap<>();

    public SevenFs(File file, File cacheDir, String password) throws IOException {
        this.file = file;
        this.cacheDir = cacheDir;
        this.title = file.getName();
        this.password = password;
        raf = new RandomAccessFile(file, "r");
        IInArchive a;
        try {
            a = password == null
                    ? SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))
                    : SevenZip.openInArchive(null, new RandomAccessFileInStream(raf), password);
        } catch (SevenZipException e) {
            raf.close();
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            String ext = title.toLowerCase(Locale.ROOT);
            boolean maybeEncrypted = ext.endsWith(".7z") || ext.endsWith(".rar");
            if (m.contains("password") || m.contains("encrypt") || m.contains("headers error")
                    || m.contains("wrong") || maybeEncrypted) {
                // 7z/RAR'da dosya adları da şifreliyse arşiv parolasız hiç açılamaz
                throw new PasswordNeeded(password != null);
            }
            throw new IOException("Arşiv açılamadı: " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            raf.close();
            throw new IOException("7-Zip motoru bu telefonda yüklenemedi");
        }
        arc = a;
        root = new VNode(this, null, title, true, 0, file.lastModified(), "", null);
        kids.put("", new ArrayList<VNode>());
        dirs.put("", root);
        int n = arc.getNumberOfItems();
        boolean anyEncrypted = false;
        for (int i = 0; i < n; i++) {
            String path = str(i, PropID.PATH);
            if (path == null || path.isEmpty()) path = defaultName();
            path = path.replace('\\', '/');
            while (path.startsWith("/")) path = path.substring(1);
            Object folder = arc.getProperty(i, PropID.IS_FOLDER);
            boolean dir = folder instanceof Boolean && (Boolean) folder;
            Object sz = arc.getProperty(i, PropID.SIZE);
            long size = sz instanceof Number ? ((Number) sz).longValue() : 0;
            Object tm = arc.getProperty(i, PropID.LAST_MODIFICATION_TIME);
            long time = tm instanceof Date ? ((Date) tm).getTime() : 0;
            Object enc = arc.getProperty(i, PropID.ENCRYPTED);
            if (enc instanceof Boolean && (Boolean) enc) anyEncrypted = true;
            add(path, dir, size, time, i);
        }
        if (anyEncrypted && password == null) {
            close();
            throw new PasswordNeeded(false);
        }
    }

    private String str(int i, PropID p) throws SevenZipException {
        Object o = arc.getProperty(i, p);
        return o == null ? null : o.toString();
    }

    /** Tek dosyalık sıkıştırmalarda (ör. x.txt.gz) içteki dosyanın adı. */
    private String defaultName() {
        String n = title;
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n + "_";
        String ext = dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("tgz") || ext.equals("taz") || ext.equals("tbz") || ext.equals("tbz2")
                || ext.equals("txz") || ext.equals("tlz")) base += ".tar";
        return base;
    }

    private void add(String path, boolean isDir, long size, long time, int index) {
        String[] parts = path.split("/");
        String cur = "";
        VNode parent = root;
        for (int k = 0; k < parts.length; k++) {
            if (parts[k].isEmpty()) continue;
            String child = cur.isEmpty() ? parts[k] : cur + "/" + parts[k];
            boolean last = k == parts.length - 1;
            if (!last || isDir) {
                VNode d = dirs.get(child);
                if (d == null) {
                    d = new VNode(this, parent, parts[k], true, 0, time, child, null);
                    dirs.put(child, d);
                    kids.put(child, new ArrayList<VNode>());
                    kids.get(cur).add(d);
                }
                parent = d;
            } else {
                kids.get(cur).add(new VNode(this, parent, parts[k], false, size, time, child, index));
            }
            cur = child;
        }
    }

    @Override public String title() { return title; }

    @Override public VNode root() { return root; }

    @Override
    public synchronized List<VNode> list(VNode dir) {
        List<VNode> l = kids.get(dir.path);
        return l == null ? new ArrayList<VNode>() : new ArrayList<>(l);
    }

    /** Seçilen öğeleri sırayla çıkarır; her öğe için out.open(index) akışı istenir. */
    public interface Sink {
        OutputStream open(int index) throws IOException;

        void done(int index) throws IOException;
    }

    public synchronized void extract(int[] indices, final Sink sink, final DiskOps.Cancel cancel,
                                     final Fat32Formatter.Progress progress) throws IOException {
        final IOException[] err = new IOException[1];
        final ExtractOperationResult[] bad = new ExtractOperationResult[1];
        final long[] total = {0};
        class Cb implements IArchiveExtractCallback, ICryptoGetTextPassword {
            int current = -1;
            OutputStream os;

            @Override
            public ISequentialOutStream getStream(int index, ExtractAskMode mode) throws SevenZipException {
                if (mode != ExtractAskMode.EXTRACT) return null;
                if (cancel != null && cancel.isCancelled()) throw new SevenZipException("İptal edildi");
                current = index;
                try {
                    os = sink.open(index);
                } catch (IOException e) {
                    err[0] = e;
                    throw new SevenZipException(e.getMessage());
                }
                return data -> {
                    try {
                        if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
                        os.write(data);
                    } catch (IOException e) {
                        err[0] = e;
                        throw new SevenZipException(e.getMessage());
                    }
                    return data.length;
                };
            }

            @Override
            public void prepareOperation(ExtractAskMode mode) {
            }

            @Override
            public void setOperationResult(ExtractOperationResult r) throws SevenZipException {
                try {
                    if (os != null) {
                        os.close();
                        os = null;
                        sink.done(current);
                    }
                } catch (IOException e) {
                    err[0] = e;
                }
                if (r != ExtractOperationResult.OK && bad[0] == null) bad[0] = r;
            }

            @Override
            public void setTotal(long t) {
                total[0] = t;
            }

            @Override
            public void setCompleted(long done) {
                if (progress != null && total[0] > 0) progress.onProgress(done, total[0]);
            }

            @Override
            public String cryptoGetTextPassword() {
                return password == null ? "" : password;
            }
        }
        Cb cb = new Cb();
        try {
            arc.extract(indices, false, cb);
        } catch (SevenZipException e) {
            if (cb.os != null) {
                try {
                    cb.os.close();
                } catch (IOException ignored) {
                }
            }
            if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
            if (err[0] != null) throw err[0];
            throw new IOException("Arşiv okunamadı: " + e.getMessage(), e);
        }
        if (err[0] != null) throw err[0];
        if (bad[0] == ExtractOperationResult.WRONG_PASSWORD
                || (bad[0] == ExtractOperationResult.CRCERROR && password != null)
                || (bad[0] == ExtractOperationResult.DATAERROR && password != null)) {
            throw new PasswordNeeded(true);
        }
        if (bad[0] == ExtractOperationResult.UNSUPPORTEDMETHOD) {
            throw new IOException("Bu arşivin sıkıştırma yöntemi desteklenmiyor");
        }
        if (bad[0] != null) throw new IOException("Arşiv bozuk ya da eksik (" + bad[0] + ")");
    }

    /** Öğe önce önbelleğe çıkarılır (7-Zip sırayla okur), oradan okunur. */
    @Override
    public InputStream open(VNode f) throws IOException {
        final File out = File.createTempFile("7z", ".tmp", cacheDir);
        out.deleteOnExit();
        extract(new int[]{(Integer) f.handle}, new Sink() {
            @Override
            public OutputStream open(int index) throws IOException {
                return new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            }

            @Override
            public void done(int index) {
            }
        }, null, null);
        return new FileInputStream(out) {
            @Override
            public void close() throws IOException {
                super.close();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        };
    }

    /** Arşivin tamamını hedef Vfs klasörüne çıkarır. */
    public int extractAll(final VNode target, final DiskOps.Cancel cancel, final Fat32Formatter.Progress pr,
                          final File tmpDir) throws IOException {
        // Klasör yapısını önce kur
        final Map<String, VNode> made = new HashMap<>();
        made.put("", target);
        final List<VNode> files = new ArrayList<>();
        collect(root, files);
        int[] idx = new int[files.size()];
        final Map<Integer, VNode> byIndex = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            idx[i] = (Integer) files.get(i).handle;
            byIndex.put(idx[i], files.get(i));
        }
        for (VNode d : allDirs()) dirFor(d.path, made, target);
        final int[] count = {0};
        extract(idx, new Sink() {
            File tmp;
            int idxNow;

            @Override
            public OutputStream open(int index) throws IOException {
                idxNow = index;
                tmp = File.createTempFile("x7", ".tmp", tmpDir);
                return new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            }

            @Override
            public void done(int index) throws IOException {
                VNode src = byIndex.get(index);
                String parentPath = src.path.contains("/") ? src.path.substring(0, src.path.lastIndexOf('/')) : "";
                VNode dir = dirFor(parentPath, made, target);
                try (InputStream in = new FileInputStream(tmp)) {
                    dir.fs.create(dir, src.name, in, tmp.length(), null, cancel);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                count[0]++;
            }
        }, cancel, pr);
        return count[0];
    }

    private List<VNode> allDirs() {
        List<VNode> l = new ArrayList<>(dirs.values());
        l.remove(root);
        java.util.Collections.sort(l, (a, b) -> a.path.length() - b.path.length());
        return l;
    }

    private VNode dirFor(String path, Map<String, VNode> made, VNode target) throws IOException {
        VNode d = made.get(path);
        if (d != null) return d;
        int slash = path.lastIndexOf('/');
        VNode parent = dirFor(slash < 0 ? "" : path.substring(0, slash), made, target);
        String name = slash < 0 ? path : path.substring(slash + 1);
        d = parent.fs.mkdir(parent, CardVolume.checkName(name));
        made.put(path, d);
        return d;
    }

    private void collect(VNode dir, List<VNode> out) {
        List<VNode> l = kids.get(dir.path);
        if (l == null) return;
        for (VNode k : l) {
            if (k.dir) collect(k, out);
            else out.add(k);
        }
    }

    private static IOException readOnly() {
        return new IOException("Arşiv içi salt okunur; önce dışarı kopyalayın");
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        throw readOnly();
    }

    @Override public VNode mkdir(VNode dir, String name) throws IOException { throw readOnly(); }

    @Override public void delete(VNode n, DiskOps.Cancel c) throws IOException { throw readOnly(); }

    @Override public VNode rename(VNode n, String newName) throws IOException { throw readOnly(); }

    @Override public VNode move(VNode n, VNode dir) { return null; }

    @Override public boolean writable() { return false; }

    @Override public long freeBytes() { return -1; }

    @Override public long totalBytes() { return -1; }

    @Override public File localFile(VNode n) { return null; }

    @Override
    public synchronized void close() {
        try {
            arc.close();
        } catch (Exception ignored) {
        }
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }

    File archiveFile() {
        return file;
    }

    /** 7z arşivi oluşturur (isteğe bağlı parola ile, dosya adları da şifrelenir). */
    public static void create7z(final List<File[]> items, File out, final String password, int level,
                                final Fat32Formatter.Progress pr) throws IOException {
        // items: {dosya, arşivdeki yol olarak "göreli adı" taşıyan File (yalnız getPath kullanılır)}
        net.sf.sevenzipjbinding.IOutCreateArchive7z oa;
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        raf.setLength(0);
        try {
            oa = SevenZip.openOutArchive7z();
            oa.setLevel(level);
            if (password != null && !password.isEmpty()) oa.setHeaderEncryption(true);
            final long[] total = {0};
            class Cb implements net.sf.sevenzipjbinding.IOutCreateCallback<net.sf.sevenzipjbinding.IOutItem7z>,
                    ICryptoGetTextPassword {
                RandomAccessFile cur;

                @Override
                public void setOperationResult(boolean ok) {
                    if (cur != null) {
                        try {
                            cur.close();
                        } catch (IOException ignored) {
                        }
                        cur = null;
                    }
                }

                @Override
                public void setTotal(long t) {
                    total[0] = t;
                }

                @Override
                public void setCompleted(long c) {
                    if (pr != null && total[0] > 0) pr.onProgress(c, total[0]);
                }

                @Override
                public net.sf.sevenzipjbinding.IOutItem7z getItemInformation(int index,
                        net.sf.sevenzipjbinding.impl.OutItemFactory<net.sf.sevenzipjbinding.IOutItem7z> f) {
                    net.sf.sevenzipjbinding.IOutItem7z it = f.createOutItem();
                    File src = items.get(index)[0];
                    String path = items.get(index)[1].getPath();
                    it.setPropertyPath(path);
                    it.setPropertyLastModificationTime(new Date(src.lastModified()));
                    if (src.isDirectory()) {
                        it.setPropertyIsDir(true);
                    } else {
                        it.setDataSize(src.length());
                    }
                    return it;
                }

                @Override
                public net.sf.sevenzipjbinding.ISequentialInStream getStream(int index) throws SevenZipException {
                    File src = items.get(index)[0];
                    if (src.isDirectory()) return null;
                    try {
                        cur = new RandomAccessFile(src, "r");
                    } catch (IOException e) {
                        throw new SevenZipException(e.getMessage());
                    }
                    return new RandomAccessFileInStream(cur);
                }

                @Override
                public String cryptoGetTextPassword() {
                    return password == null ? "" : password;
                }
            }
            oa.createArchive(new net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream(raf), items.size(), new Cb());
            oa.close();
        } catch (SevenZipException e) {
            throw new IOException("7z oluşturulamadı: " + e.getMessage(), e);
        } finally {
            raf.close();
        }
    }
}

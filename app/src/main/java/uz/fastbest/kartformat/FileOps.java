package uz.fastbest.kartformat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dosya sistemleri arası kopyalama, taşıma, silme, ZIP, arama ve boyut hesaplama. */
public final class FileOps {

    public interface Status {
        void show(String text);
    }

    private final Fat32Formatter.Progress progress;
    private final DiskOps.Cancel cancel;
    private final Status status;
    private long done;
    private long total;
    private int files;

    public FileOps(Fat32Formatter.Progress progress, DiskOps.Cancel cancel, Status status) {
        this.progress = progress;
        this.cancel = cancel;
        this.status = status;
    }

    public int files() {
        return files;
    }

    private void check() throws DiskOps.CancelledException {
        if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
    }

    private void show(String s) {
        if (status != null) status.show(s);
    }

    private void report() {
        if (progress != null) progress.onProgress(Math.min(done, total), Math.max(total, 1));
    }

    // ------------------------------------------------------------ boyut

    /** {bayt, dosya sayısı, klasör sayısı} */
    public long[] measure(List<VNode> nodes) throws IOException {
        long[] r = new long[3];
        for (VNode n : nodes) measure(n, r, 0);
        return r;
    }

    private void measure(VNode n, long[] r, int depth) throws IOException {
        check();
        if (depth > 128) return;
        if (n.dir) {
            r[2]++;
            for (VNode k : n.fs.list(n)) measure(k, r, depth + 1);
        } else {
            r[0] += Math.max(0, n.size);
            r[1]++;
        }
    }

    // ------------------------------------------------------------ kopyala / taşı

    /** Kaynakları hedef klasöre kopyalar (move=true ise taşır). */
    public String copy(List<VNode> sources, VNode target, boolean move) throws IOException {
        if (!target.fs.writable()) throw new IOException("Hedef salt okunur");
        for (VNode s : sources) {
            if (s.dir && target.inside(s)) {
                throw new IOException("Bir klasör kendi içine kopyalanamaz: " + s.name);
            }
        }
        // Aynı dosya sisteminde taşıma: önce hızlı yol
        List<VNode> rest = new ArrayList<>();
        int moved = 0;
        for (VNode s : sources) {
            if (move && s.fs == target.fs) {
                if (s.parent != null && s.parent.same(target)) continue; // zaten orada
                VNode m = s.fs.move(s, target);
                if (m != null) {
                    moved++;
                    continue;
                }
            }
            rest.add(s);
        }
        long[] size = measure(rest);
        total = size[0];
        done = 0;
        long free = target.fs.freeBytes();
        if (free >= 0 && total > free) {
            throw new IOException("Hedefte yeterli yer yok (" + DiskOps.sizeText(total) + " gerekli, "
                    + DiskOps.sizeText(free) + " boş)");
        }
        for (VNode s : rest) {
            copyOne(s, target, 0);
            if (move) s.fs.delete(s, cancel);
        }
        int count = files + moved;
        return count + (move ? " öğe taşındı." : " dosya kopyalandı.");
    }

    private void copyOne(VNode s, VNode targetDir, int depth) throws IOException {
        check();
        if (depth > 128) throw new IOException("Klasör yapısı çok derin");
        if (s.dir) {
            VNode d = dirFor(targetDir, s.name);
            for (VNode k : s.fs.list(s)) copyOne(k, d, depth + 1);
            return;
        }
        show((files + 1) + ": " + s.name);
        final long base = done;
        try (InputStream in = s.fs.open(s)) {
            targetDir.fs.create(targetDir, s.name, in, s.size, (d, t) -> {
                done = base + d;
                report();
            }, cancel);
        }
        done = base + Math.max(0, s.size);
        files++;
        report();
    }

    /** Klasörde aynı adlı klasör varsa onu, yoksa yenisini verir. */
    public static VNode dirFor(VNode parent, String name) throws IOException {
        for (VNode k : parent.fs.list(parent)) {
            if (k.dir && k.name.equalsIgnoreCase(name)) return k;
        }
        return parent.fs.mkdir(parent, name);
    }

    public void delete(List<VNode> nodes) throws IOException {
        for (VNode n : nodes) {
            check();
            show("Siliniyor: " + n.name);
            n.fs.delete(n, cancel);
            files++;
        }
    }

    // ------------------------------------------------------------ ZIP

    /** Seçilenleri ZIP dosyasına sıkıştırır ve hedef klasöre koyar. */
    public VNode zip(List<VNode> sources, VNode targetDir, String zipName, File tempDir)
            throws IOException {
        total = measure(sources)[0];
        done = 0;
        File local = targetDir.fs.localFile(targetDir);
        File out = local != null ? new File(local, LocalFs.unique(local, zipName))
                : File.createTempFile("zip", ".tmp", tempDir);
        boolean ok = false;
        try {
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(
                    new FileOutputStream(out), 256 * 1024))) {
                for (VNode s : sources) addToZip(z, s, "", 0);
            }
            ok = true;
            if (local != null) return ((LocalFs) targetDir.fs).node(out);
            try (InputStream in = new FileInputStream(out)) {
                return targetDir.fs.create(targetDir, zipName, in, out.length(), null, cancel);
            }
        } finally {
            if (!ok || local == null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    private void addToZip(ZipOutputStream z, VNode n, String prefix, int depth)
            throws IOException {
        check();
        if (depth > 128) throw new IOException("Klasör yapısı çok derin");
        String name = prefix + n.name;
        if (n.dir) {
            ZipEntry e = new ZipEntry(name + "/");
            if (n.modified > 0) e.setTime(n.modified);
            z.putNextEntry(e);
            z.closeEntry();
            for (VNode k : n.fs.list(n)) addToZip(z, k, name + "/", depth + 1);
            return;
        }
        show("Sıkıştırılıyor: " + n.name);
        ZipEntry e = new ZipEntry(name);
        if (n.modified > 0) e.setTime(n.modified);
        z.putNextEntry(e);
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = n.fs.open(n)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                check();
                z.write(buf, 0, r);
                done += r;
                report();
            }
        }
        z.closeEntry();
        files++;
    }

    // ------------------------------------------------------------ arşiv açma

    /** Arşivi (zip, 7z, tar..., ISO) hedef klasörün içinde yeni bir klasöre açar. */
    public VNode extract(VNode archive, VNode targetDir, File tempDir) throws IOException {
        File local = archive.fs.localFile(archive);
        File temp = null;
        try {
            if (local == null) {
                show("Arşiv hazırlanıyor…");
                temp = File.createTempFile("arc", ".tmp", tempDir);
                try (InputStream in = archive.fs.open(archive);
                     java.io.OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                    byte[] buf = new byte[256 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        check();
                        out.write(buf, 0, r);
                    }
                }
                local = temp;
            }
            try (RandomAccessFile raf = new RandomAccessFile(local, "r")) {
                FileChannel ch = raf.getChannel();
                RandomSource src = new RandomSource.Channel(ch);
                String type = Extractor.detect(src, archive.name);
                if (type == null) throw new IOException("Bu dosya tanınan bir arşiv değil");
                String base = archive.name;
                for (String ext : new String[]{".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz",
                        ".tbz2", ".zip", ".7z", ".tar", ".iso", ".img", ".jar", ".apk"}) {
                    if (base.toLowerCase(Locale.ROOT).endsWith(ext)) {
                        base = base.substring(0, base.length() - ext.length());
                        break;
                    }
                }
                if (base.trim().isEmpty()) base = "arsiv";
                VNode dir = targetDir.fs.mkdir(targetDir, base);
                Extractor x = new Extractor(new VfsTarget(dir), progress, cancel, this::show);
                x.extract(type, src, ch, null);
                files += x.files();
                return dir;
            }
        } finally {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /** Extractor'ın yazacağı hedef: herhangi bir Vfs klasörü. */
    static final class VfsTarget implements Extractor.Target {
        private final VNode root;

        VfsTarget(VNode root) {
            this.root = root;
        }

        @Override
        public Object root() {
            return root;
        }

        @Override
        public List<Object[]> list(Object dir) throws IOException {
            List<Object[]> out = new ArrayList<>();
            VNode d = (VNode) dir;
            for (VNode k : d.fs.list(d)) out.add(new Object[]{k.name, k.dir, k});
            return out;
        }

        @Override
        public Object mkdir(Object dir, String name) throws IOException {
            VNode d = (VNode) dir;
            return d.fs.mkdir(d, name);
        }

        @Override
        public String create(Object dir, String name, InputStream in, long size,
                             Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
            VNode d = (VNode) dir;
            return d.fs.create(d, name, in, size, p, c).name;
        }

        @Override
        public String fold(String name) {
            return name.toUpperCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------ arama

    public List<VNode> search(VNode dir, String query, int limit) throws IOException {
        List<VNode> out = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT).trim();
        searchRec(dir, q, out, limit, 0);
        return out;
    }

    private void searchRec(VNode dir, String q, List<VNode> out, int limit, int depth)
            throws IOException {
        if (out.size() >= limit || depth > 64) return;
        check();
        show("Aranıyor: " + dir.name);
        List<VNode> kids;
        try {
            kids = dir.fs.list(dir);
        } catch (IOException e) {
            return;   // okunamayan klasör atlanır
        }
        for (VNode k : kids) {
            if (out.size() >= limit) return;
            if (k.name.toLowerCase(Locale.ROOT).contains(q)) out.add(k);
            if (k.dir) searchRec(k, q, out, limit, depth + 1);
        }
    }

    // ------------------------------------------------------------ disk analizi

    /** Klasördeki her öğenin toplam boyutu: {düğüm, bayt}. */
    public List<Object[]> analyze(VNode dir) throws IOException {
        List<Object[]> out = new ArrayList<>();
        List<VNode> kids = dir.fs.list(dir);
        for (int i = 0; i < kids.size(); i++) {
            VNode k = kids.get(i);
            check();
            show("İnceleniyor: " + k.name);
            long bytes;
            try {
                bytes = k.dir ? measure(java.util.Collections.singletonList(k))[0] : k.size;
            } catch (IOException e) {
                bytes = 0;
            }
            out.add(new Object[]{k, bytes});
            if (progress != null) progress.onProgress(i + 1, kids.size());
        }
        return out;
    }

    /** Telefon dışındaki bir dosyayı açmak için önbelleğe kopyalar. */
    public File toCache(VNode f, File cacheDir) throws IOException {
        File local = f.fs.localFile(f);
        if (local != null) return local;
        File dir = new File(cacheDir, "acilan");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Önbellek oluşturulamadı");
        File out = new File(dir, f.name);
        total = Math.max(1, f.size);
        done = 0;
        try (InputStream in = new BufferedInputStream(f.fs.open(f), 256 * 1024);
             java.io.OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                check();
                o.write(buf, 0, r);
                done += r;
                report();
            }
        }
        return out;
    }
}

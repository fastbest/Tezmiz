package uz.fastbest.kartformat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Telefon belleğindeki (ya da okunabilir SD karttaki) dosyalar. */
public final class LocalFs implements Vfs {

    private final String title;
    private final File rootFile;
    private final VNode root;

    public LocalFs(String title, File rootFile) {
        this.title = title;
        this.rootFile = rootFile;
        this.root = new VNode(this, null, title, true, 0, rootFile.lastModified(),
                rootFile.getAbsolutePath(), rootFile);
    }

    public File rootFile() {
        return rootFile;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public VNode root() {
        return root;
    }

    /** Bu dosya sistemindeki bir dosyanın düğümü (kök altında olmalı). */
    public VNode node(File f) {
        String rootPath = rootFile.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(rootPath)) return root;
        VNode parent = p.startsWith(rootPath + "/") ? node(f.getParentFile()) : null;
        return wrap(parent, f);
    }

    private VNode wrap(VNode parent, File f) {
        boolean d = f.isDirectory();
        return new VNode(this, parent, f.getName(), d, d ? 0 : f.length(), f.lastModified(),
                f.getAbsolutePath(), f);
    }

    private static File file(VNode n) {
        return (File) n.handle;
    }

    @Override
    public List<VNode> list(VNode dir) throws IOException {
        File[] files = file(dir).listFiles();
        if (files == null) {
            throw new IOException("Klasör okunamadı (izin verilmemiş olabilir): " + dir.name);
        }
        List<VNode> out = new ArrayList<>(files.length);
        for (File f : files) out.add(wrap(dir, f));
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        return new FileInputStream(file(f));
    }

    static String unique(File dir, String name) {
        if (!new File(dir, name).exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!new File(dir, cand).exists()) return cand;
        }
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        File d = file(dir);
        File target = new File(d, unique(d, CardVolume.checkName(name)));
        boolean ok = false;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                out.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, Math.max(done, size));
            }
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
        return wrap(dir, target);
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        File d = file(dir);
        File target = new File(d, unique(d, CardVolume.checkName(name)));
        if (!target.mkdir()) throw new IOException("Klasör oluşturulamadı: " + target.getName());
        return wrap(dir, target);
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        deleteFile(file(n), c, 0);
    }

    private static boolean isLink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File canon = new File(parent.getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteFile(File f, DiskOps.Cancel c, int depth) throws IOException {
        if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
        if (depth > 128) throw new IOException("Klasör yapısı çok derin");
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteFile(k, c, depth + 1);
        }
        if (!f.delete() && f.exists()) throw new IOException("Silinemedi: " + f.getName());
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        File f = file(n);
        File target = new File(f.getParentFile(), CardVolume.checkName(newName));
        if (target.exists() && !target.getName().equalsIgnoreCase(f.getName())) {
            throw new IOException("Bu adda bir öğe zaten var");
        }
        if (!f.renameTo(target)) throw new IOException("Yeniden adlandırılamadı");
        return wrap(n.parent, target);
    }

    @Override
    public VNode move(VNode n, VNode dir) {
        File f = file(n);
        File d = file(dir);
        File target = new File(d, unique(d, f.getName()));
        return f.renameTo(target) ? wrap(dir, target) : null;
    }

    @Override
    public boolean writable() {
        return rootFile.canWrite();
    }

    @Override
    public long freeBytes() {
        return rootFile.getUsableSpace();
    }

    @Override
    public long totalBytes() {
        return rootFile.getTotalSpace();
    }

    @Override
    public File localFile(VNode n) {
        return file(n);
    }

    @Override
    public void close() {
    }
}

package uz.fastbest.kartformat;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parolalı (AES-256) ZIP dosyası: kasa olarak kullanılır. Bilgisayarda 7-Zip / WinRAR ile
 * aynı parolayla açılabilir. İçine dosya eklenir, silinir, yeniden adlandırılır.
 */
public final class Zip4jFs implements Vfs {

    /** Parolayı doğrulamak için kasanın içine konan küçük şifreli dosya. */
    static final String MARKER = ".kasa";

    private final File file;
    private final char[] pass;
    private final String title;
    private ZipFile zip;
    private VNode root;
    private final Map<String, List<VNode>> kids = new HashMap<>();

    private Zip4jFs(File file, char[] pass, String title) {
        this.file = file;
        this.pass = pass;
        this.title = title;
    }

    /** Yeni boş kasa oluşturur. */
    public static Zip4jFs create(File file, String password, String title) throws IOException {
        if (file.exists()) throw new IOException("Bu adda bir dosya zaten var: " + file.getName());
        Zip4jFs fs = new Zip4jFs(file, password.toCharArray(), title);
        fs.zip = new ZipFile(file, fs.pass);
        fs.zip.addStream(new ByteArrayInputStream("Gravity Manager".getBytes(StandardCharsets.UTF_8)),
                fs.params(MARKER, true));
        fs.reload();
        return fs;
    }

    /** Var olan parolalı ZIP'i açar; parola yanlışsa IOException("Parola yanlış"). */
    public static Zip4jFs open(File file, String password, String title) throws IOException {
        Zip4jFs fs = new Zip4jFs(file, password.toCharArray(), title);
        fs.zip = new ZipFile(file, fs.pass);
        if (!fs.zip.isValidZipFile()) throw new IOException("Geçerli bir ZIP dosyası değil");
        fs.verify();
        fs.reload();
        return fs;
    }

    public static boolean isEncrypted(File f) {
        try {
            ZipFile z = new ZipFile(f);
            return z.isValidZipFile() && z.isEncrypted();
        } catch (Exception e) {
            return false;
        }
    }

    /** En küçük şifreli dosyadan birkaç bayt okuyarak parolayı sınar. */
    private void verify() throws IOException {
        FileHeader best = null;
        for (FileHeader h : zip.getFileHeaders()) {
            if (h.isDirectory() || !h.isEncrypted()) continue;
            if (best == null || h.getUncompressedSize() < best.getUncompressedSize()) best = h;
        }
        if (best == null) return;
        try (InputStream in = zip.getInputStream(best)) {
            byte[] b = new byte[64];
            //noinspection StatementWithEmptyBody
            while (in.read(b) > 0) {
                if (best.getUncompressedSize() > 1 << 20) break;
            }
        } catch (ZipException e) {
            if (e.getType() == ZipException.Type.WRONG_PASSWORD
                    || e.getType() == ZipException.Type.CHECKSUM_MISMATCH) {
                throw new IOException("Parola yanlış");
            }
            throw new IOException(e.getMessage(), e);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("password") || m.contains("crc")) throw new IOException("Parola yanlış");
            throw e;
        }
    }

    private ZipParameters params(String name, boolean encrypt) {
        ZipParameters p = new ZipParameters();
        p.setFileNameInZip(name);
        p.setCompressionLevel(CompressionLevel.NORMAL);
        if (encrypt) {
            p.setEncryptFiles(true);
            p.setEncryptionMethod(EncryptionMethod.AES);
            p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        p.setLastModifiedFileTime(System.currentTimeMillis());
        return p;
    }

    /** ZIP içindeki adları (a/b/c.txt) klasör ağacına çevirir. */
    private synchronized void reload() throws IOException {
        kids.clear();
        root = new VNode(this, null, title, true, 0, file.lastModified(), "", null);
        kids.put("", new ArrayList<VNode>());
        Map<String, VNode> dirs = new HashMap<>();
        dirs.put("", root);
        for (FileHeader h : zip.getFileHeaders()) {
            String name = h.getFileName().replace('\\', '/');
            while (name.startsWith("/")) name = name.substring(1);
            boolean isDir = h.isDirectory() || name.endsWith("/");
            while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty() || name.equals(MARKER)) continue;
            String[] parts = name.split("/");
            String path = "";
            VNode parent = root;
            for (int i = 0; i < parts.length; i++) {
                String child = path.isEmpty() ? parts[i] : path + "/" + parts[i];
                boolean last = i == parts.length - 1;
                if (!last || isDir) {
                    VNode d = dirs.get(child);
                    if (d == null) {
                        d = new VNode(this, parent, parts[i], true, 0, h.getLastModifiedTimeEpoch(), child, null);
                        dirs.put(child, d);
                        kids.put(child, new ArrayList<VNode>());
                        kids.get(path).add(d);
                    }
                    parent = d;
                } else {
                    kids.get(path).add(new VNode(this, parent, parts[i], false, h.getUncompressedSize(),
                            h.getLastModifiedTimeEpoch(), child, h.getFileName()));
                }
                path = child;
            }
        }
    }

    @Override public String title() { return title; }

    @Override public synchronized VNode root() { return root; }

    public File zipFile() {
        return file;
    }

    @Override
    public synchronized List<VNode> list(VNode dir) {
        List<VNode> l = kids.get(dir.path);
        return l == null ? new ArrayList<VNode>() : new ArrayList<>(l);
    }

    @Override
    public synchronized InputStream open(VNode f) throws IOException {
        FileHeader h = zip.getFileHeader((String) f.handle);
        if (h == null) throw new IOException("Kasada bulunamadı: " + f.name);
        final InputStream in = zip.getInputStream(h);
        return new FilterInputStream(in) { };
    }

    private String join(VNode dir, String name) {
        return dir.path.isEmpty() ? name : dir.path + "/" + name;
    }

    private String unique(VNode dir, String name) {
        Set<String> names = new HashSet<>();
        for (VNode k : list(dir)) names.add(k.name.toLowerCase(Locale.ROOT));
        if (!names.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String c = b + " (" + i + ")" + ext;
            if (!names.contains(c.toLowerCase(Locale.ROOT))) return c;
        }
    }

    @Override
    public synchronized VNode create(VNode dir, String name, InputStream in, long size,
                                     Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir, name);
        final long[] done = {0};
        InputStream counting = new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                int r = super.read(b, off, len);
                if (r > 0) {
                    done[0] += r;
                    if (p != null) p.onProgress(done[0], Math.max(done[0], size));
                }
                return r;
            }
        };
        zip.addStream(counting, params(path, true));
        reload();
        return find(path);
    }

    private VNode find(String path) {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        List<VNode> l = kids.get(parent);
        if (l != null) for (VNode v : l) if (v.path.equals(path)) return v;
        return new VNode(this, root, path, false, 0, System.currentTimeMillis(), path, path);
    }

    @Override
    public synchronized VNode mkdir(VNode dir, String name) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir, name);
        zip.addStream(new ByteArrayInputStream(new byte[0]), params(path + "/", false));
        reload();
        VNode d = find(path);
        return d;
    }

    /** Öğenin ZIP'teki tüm kayıt adları (klasörse içindekilerle birlikte). */
    private List<String> entries(VNode n) throws IOException {
        List<String> out = new ArrayList<>();
        for (FileHeader h : zip.getFileHeaders()) {
            String name = h.getFileName().replace('\\', '/');
            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (clean.equals(n.path) || (n.dir && clean.startsWith(n.path + "/"))) out.add(h.getFileName());
        }
        return out;
    }

    @Override
    public synchronized void delete(VNode n, DiskOps.Cancel c) throws IOException {
        List<String> e = entries(n);
        if (!e.isEmpty()) zip.removeFiles(e);
        reload();
    }

    private VNode moveTo(VNode n, VNode parent, String newName) throws IOException {
        String to = join(parent, newName);
        Map<String, String> m = new HashMap<>();
        for (String e : entries(n)) {
            String clean = e.replace('\\', '/');
            m.put(e, to + clean.substring(n.path.length()));
        }
        if (!m.isEmpty()) zip.renameFiles(m);
        reload();
        return find(to);
    }

    @Override
    public synchronized VNode rename(VNode n, String newName) throws IOException {
        return moveTo(n, n.parent == null ? root : n.parent, unique(n.parent == null ? root : n.parent,
                CardVolume.checkName(newName)));
    }

    @Override
    public synchronized VNode move(VNode n, VNode dir) {
        if (dir.fs != this) return null;
        try {
            return moveTo(n, dir, unique(dir, n.name));
        } catch (IOException e) {
            return null;
        }
    }

    @Override public boolean writable() { return true; }

    @Override public long freeBytes() { return -1; }

    @Override public long totalBytes() { return -1; }

    @Override public File localFile(VNode n) { return null; }

    @Override
    public synchronized void close() {
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
        java.util.Arrays.fill(pass, '\0');
    }
}

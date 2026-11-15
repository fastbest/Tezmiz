package uz.fastbest.kartformat;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** FTP sunucusundaki dosyalar (Apache Commons Net). */
public final class FtpFs implements Vfs {

    public static final class Server {
        public String name;
        public String host;
        public int port = 21;
        public String user = "anonymous";
        public String pass = "";
        public String path = "/";
        /** "ftp" ya da "smb" (Windows paylaşımı / NAS). */
        public String type = "ftp";
    }

    private final Server s;
    private final VNode root;
    private FTPClient ftp;

    public FtpFs(Server s) {
        this.s = s;
        String start = s.path == null || s.path.isEmpty() ? "/" : s.path;
        this.root = new VNode(this, null, s.name, true, 0, 0, start, null);
    }

    private FTPClient client() throws IOException {
        if (ftp != null && ftp.isConnected()) {
            try {
                if (ftp.sendNoOp()) return ftp;
            } catch (IOException ignored) {
            }
            closeQuietly();
        }
        FTPClient c = new FTPClient();
        c.setControlEncoding("UTF-8");
        c.setConnectTimeout(15000);
        c.setDefaultTimeout(30000);
        c.connect(s.host, s.port);
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
            c.disconnect();
            throw new IOException("FTP sunucusu bağlantıyı reddetti");
        }
        if (!c.login(s.user, s.pass)) {
            c.disconnect();
            throw new IOException("FTP girişi başarısız (kullanıcı adı ya da parola yanlış)");
        }
        c.enterLocalPassiveMode();
        c.setFileType(FTP.BINARY_FILE_TYPE);
        c.setSoTimeout(60000);
        ftp = c;
        return c;
    }

    private void closeQuietly() {
        try {
            if (ftp != null) {
                ftp.logout();
                ftp.disconnect();
            }
        } catch (IOException ignored) {
        }
        ftp = null;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static IOException fail(FTPClient c, String what) {
        String reply = c.getReplyString();
        return new IOException(what + (reply == null ? "" : ": " + reply.trim()));
    }

    @Override
    public String title() {
        return s.name;
    }

    @Override
    public VNode root() {
        return root;
    }

    @Override
    public List<VNode> list(VNode dir) throws IOException {
        FTPClient c = client();
        FTPFile[] files = c.listFiles(dir.path);
        List<VNode> out = new ArrayList<>();
        if (files == null) return out;
        for (FTPFile f : files) {
            if (f == null) continue;
            String n = f.getName();
            if (n == null || n.equals(".") || n.equals("..")) continue;
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);
            long t = f.getTimestamp() != null ? f.getTimestamp().getTimeInMillis() : 0;
            out.add(new VNode(this, dir, n, f.isDirectory(), f.isDirectory() ? 0 : f.getSize(), t,
                    join(dir.path, n), f));
        }
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        final FTPClient c = client();
        InputStream in = c.retrieveFileStream(f.path);
        if (in == null) throw fail(c, "Dosya indirilemedi");
        return new FilterInputStream(in) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                super.close();
                if (!c.completePendingCommand()) throw fail(c, "Aktarım tamamlanamadı");
            }
        };
    }

    private String unique(VNode dir, String name) throws IOException {
        Set<String> names = new HashSet<>();
        for (VNode k : list(dir)) names.add(k.name.toLowerCase(Locale.ROOT));
        if (!names.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!names.contains(cand.toLowerCase(Locale.ROOT))) return cand;
        }
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        final Fat32Formatter.Progress p, final DiskOps.Cancel c)
            throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        FTPClient cl = client();
        final long total = size;
        InputStream counted = new FilterInputStream(in) {
            long done;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                int n = super.read(b, off, len);
                if (n > 0) {
                    done += n;
                    if (p != null) p.onProgress(done, Math.max(done, total));
                }
                return n;
            }
        };
        if (!cl.storeFile(path, counted)) throw fail(cl, "Yüklenemedi");
        return new VNode(this, dir, name, false, size, System.currentTimeMillis(), path, null);
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        FTPClient c = client();
        if (!c.makeDirectory(path)) throw fail(c, "Klasör oluşturulamadı");
        return new VNode(this, dir, name, true, 0, System.currentTimeMillis(), path, null);
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        deleteRec(n, c, 0);
    }

    private void deleteRec(VNode n, DiskOps.Cancel c, int depth) throws IOException {
        if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
        if (depth > 64) throw new IOException("Klasör yapısı çok derin");
        FTPClient cl = client();
        if (n.dir) {
            for (VNode k : list(n)) deleteRec(k, c, depth + 1);
            if (!cl.removeDirectory(n.path)) throw fail(cl, "Klasör silinemedi");
        } else if (!cl.deleteFile(n.path)) {
            throw fail(cl, "Silinemedi");
        }
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        newName = CardVolume.checkName(newName);
        String parent = n.parent != null ? n.parent.path : "/";
        String to = join(parent, newName);
        FTPClient c = client();
        if (!c.rename(n.path, to)) throw fail(c, "Yeniden adlandırılamadı");
        return new VNode(this, n.parent, newName, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public VNode move(VNode n, VNode dir) throws IOException {
        String to = join(dir.path, unique(dir, n.name));
        FTPClient c = client();
        if (!c.rename(n.path, to)) return null;
        return new VNode(this, dir, n.name, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public long freeBytes() {
        return -1;
    }

    @Override
    public long totalBytes() {
        return -1;
    }

    @Override
    public File localFile(VNode n) {
        return null;
    }

    @Override
    public void close() {
        closeQuietly();
    }
}

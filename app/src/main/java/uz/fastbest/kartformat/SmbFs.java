package uz.fastbest.kartformat;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.security.bc.BCSecurityProvider;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Windows paylaşımı / NAS (SMB2-3) dosyaları — smbj kütüphanesi.
 * Sunucu kaydındaki "path" ilk parçası paylaşım adıdır: "/Paylasim/alt/klasor".
 */
public final class SmbFs implements Vfs {

    private final FtpFs.Server s;
    private final String shareName;
    private final String startDir;
    private final VNode root;
    private SMBClient client;
    private Connection conn;
    private DiskShare share;

    public SmbFs(FtpFs.Server s) {
        this.s = s;
        String p = s.path == null ? "" : s.path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        int slash = p.indexOf('/');
        shareName = slash < 0 ? p : p.substring(0, slash);
        String rest = slash < 0 ? "" : p.substring(slash + 1);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        startDir = rest;
        root = new VNode(this, null, s.name, true, 0, 0, "/" + rest, null);
    }

    private synchronized DiskShare share() throws IOException {
        if (share != null && share.isConnected()) return share;
        close();
        if (shareName.isEmpty()) {
            throw new IOException("Paylaşım adı gerekli (Başlangıç klasörüne ör. /Belgeler yazın)");
        }
        SmbConfig cfg = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withSecurityProvider(new BCSecurityProvider())
                .build();
        client = new SMBClient(cfg);
        try {
            conn = client.connect(s.host, s.port > 0 ? s.port : 445);
            AuthenticationContext ac;
            String user = s.user == null ? "" : s.user.trim();
            if (user.isEmpty() || user.equalsIgnoreCase("anonymous") || user.equalsIgnoreCase("guest")) {
                ac = user.equalsIgnoreCase("guest") ? AuthenticationContext.guest()
                        : AuthenticationContext.anonymous();
            } else {
                String domain = "";
                int bs = user.indexOf('\\');
                if (bs > 0) {
                    domain = user.substring(0, bs);
                    user = user.substring(bs + 1);
                }
                ac = new AuthenticationContext(user, (s.pass == null ? "" : s.pass).toCharArray(), domain);
            }
            Session session = conn.authenticate(ac);
            share = (DiskShare) session.connectShare(shareName);
            return share;
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw new IOException("SMB bağlantısı kurulamadı: " + e.getMessage(), e);
        }
    }

    /** VNode yolu ("/a/b") → paylaşım içi yol ("a\\b"). */
    private static String rel(String path) {
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        return p.replace('/', '\\');
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
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
        DiskShare sh = share();
        List<VNode> out = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation f : sh.list(rel(dir.path))) {
                String n = f.getFileName();
                if (n == null || n.equals(".") || n.equals("..")) continue;
                boolean isDir = (f.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                long t = f.getLastWriteTime() == null ? 0 : f.getLastWriteTime().toEpochMillis();
                out.add(new VNode(this, dir, n, isDir, isDir ? 0 : f.getEndOfFile(), t,
                        join(dir.path, n), null));
            }
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        try {
            final com.hierynomus.smbj.share.File file = share().openFile(rel(f.path),
                    EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null);
            return new FilterInputStream(file.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        file.close();
                    }
                }
            };
        } catch (RuntimeException e) {
            throw new IOException("Dosya açılamadı: " + e.getMessage(), e);
        }
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
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        try (com.hierynomus.smbj.share.File file = share().openFile(rel(path),
                EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE, null);
             OutputStream os = file.getOutputStream()) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                os.write(buf, 0, r);
                done += r;
                if (p != null) p.onProgress(done, Math.max(done, size));
            }
        } catch (RuntimeException e) {
            throw new IOException("Yüklenemedi: " + e.getMessage(), e);
        }
        return new VNode(this, dir, name, false, size, System.currentTimeMillis(), path, null);
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        try {
            share().mkdir(rel(path));
        } catch (RuntimeException e) {
            throw new IOException("Klasör oluşturulamadı: " + e.getMessage(), e);
        }
        return new VNode(this, dir, name, true, 0, System.currentTimeMillis(), path, null);
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        try {
            if (n.dir) share().rmdir(rel(n.path), true);
            else share().rm(rel(n.path));
        } catch (RuntimeException e) {
            throw new IOException("Silinemedi: " + e.getMessage(), e);
        }
    }

    private VNode renameTo(VNode n, VNode newParent, String newName) throws IOException {
        String parent = newParent != null ? newParent.path : "/";
        String to = join(parent, newName);
        try (DiskEntry e = share().open(rel(n.path),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                n.dir ? EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE) : null)) {
            e.rename(rel(to));
        } catch (RuntimeException ex) {
            throw new IOException("Yeniden adlandırılamadı: " + ex.getMessage(), ex);
        }
        return new VNode(this, newParent, newName, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        return renameTo(n, n.parent, CardVolume.checkName(newName));
    }

    @Override
    public VNode move(VNode n, VNode dir) throws IOException {
        try {
            return renameTo(n, dir, unique(dir, n.name));
        } catch (IOException e) {
            return null;
        }
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
    public synchronized void close() {
        try {
            if (share != null) share.close();
        } catch (Exception ignored) {
        }
        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {
        }
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
        }
        share = null;
        conn = null;
        client = null;
    }

    @SuppressWarnings("unused")
    private FileAllInformation info(String path) {
        return share.getFileInformation(rel(path));
    }

    String startDir() {
        return startDir;
    }
}

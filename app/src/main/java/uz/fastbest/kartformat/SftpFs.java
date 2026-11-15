package uz.fastbest.kartformat;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/** SSH sunucusundaki dosyalar (SFTP, JSch). */
public final class SftpFs implements Vfs {

    private final FtpFs.Server s;
    private final VNode root;
    private Session session;
    private ChannelSftp sftp;

    public SftpFs(FtpFs.Server s) {
        this.s = s;
        String start = s.path == null || s.path.isEmpty() ? "/" : s.path;
        root = new VNode(this, null, s.name, true, 0, 0, start, null);
    }

    private synchronized ChannelSftp ch() throws IOException {
        if (sftp != null && sftp.isConnected() && session != null && session.isConnected()) return sftp;
        close();
        try {
            JSch j = new JSch();
            session = j.getSession(s.user, s.host, s.port > 0 ? s.port : 22);
            session.setPassword(s.pass == null ? "" : s.pass);
            Properties cfg = new Properties();
            cfg.put("StrictHostKeyChecking", "no");
            session.setConfig(cfg);
            session.connect(20000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(20000);
            return sftp;
        } catch (JSchException e) {
            close();
            throw new IOException("SFTP bağlantısı kurulamadı: " + e.getMessage(), e);
        }
    }

    private static IOException io(String what, Exception e) {
        return new IOException(what + ": " + e.getMessage(), e);
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    @Override public String title() { return s.name; }

    @Override public VNode root() { return root; }

    @Override
    public synchronized List<VNode> list(VNode dir) throws IOException {
        List<VNode> out = new ArrayList<>();
        try {
            Vector<?> v = ch().ls(dir.path);
            for (Object o : v) {
                ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                boolean d = a.isDir();
                if (a.isLink()) {
                    try {
                        d = ch().stat(join(dir.path, n)).isDir();
                    } catch (SftpException ignored) {
                    }
                }
                out.add(new VNode(this, dir, n, d, d ? 0 : a.getSize(), a.getMTime() * 1000L,
                        join(dir.path, n), null));
            }
        } catch (SftpException e) {
            throw io("Listelenemedi", e);
        }
        return out;
    }

    /** Okuma için ayrı kanal: liste işlemleriyle çakışmasın. */
    @Override
    public InputStream open(VNode f) throws IOException {
        ch();
        try {
            final ChannelSftp c = (ChannelSftp) session.openChannel("sftp");
            c.connect(20000);
            return new FilterInputStream(c.get(f.path)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        c.disconnect();
                    }
                }
            };
        } catch (JSchException | SftpException e) {
            throw io("Dosya açılamadı", e);
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
    public synchronized VNode create(VNode dir, String name, InputStream in, long size,
                                     Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        try (OutputStream os = ch().put(path)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                os.write(buf, 0, r);
                done += r;
                if (p != null) p.onProgress(done, Math.max(done, size));
            }
        } catch (SftpException e) {
            throw io("Yüklenemedi", e);
        }
        return new VNode(this, dir, name, false, size, System.currentTimeMillis(), path, null);
    }

    @Override
    public synchronized VNode mkdir(VNode dir, String name) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        try {
            ch().mkdir(path);
        } catch (SftpException e) {
            throw io("Klasör oluşturulamadı", e);
        }
        return new VNode(this, dir, name, true, 0, System.currentTimeMillis(), path, null);
    }

    @Override
    public synchronized void delete(VNode n, DiskOps.Cancel c) throws IOException {
        deleteRec(n, c, 0);
    }

    private void deleteRec(VNode n, DiskOps.Cancel c, int depth) throws IOException {
        if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
        if (depth > 64) throw new IOException("Klasör yapısı çok derin");
        try {
            if (n.dir) {
                for (VNode k : list(n)) deleteRec(k, c, depth + 1);
                ch().rmdir(n.path);
            } else {
                ch().rm(n.path);
            }
        } catch (SftpException e) {
            throw io("Silinemedi", e);
        }
    }

    @Override
    public synchronized VNode rename(VNode n, String newName) throws IOException {
        newName = CardVolume.checkName(newName);
        String to = join(n.parent != null ? n.parent.path : "/", newName);
        try {
            ch().rename(n.path, to);
        } catch (SftpException e) {
            throw io("Yeniden adlandırılamadı", e);
        }
        return new VNode(this, n.parent, newName, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public synchronized VNode move(VNode n, VNode dir) throws IOException {
        String to = join(dir.path, unique(dir, n.name));
        try {
            ch().rename(n.path, to);
        } catch (SftpException e) {
            return null;
        }
        return new VNode(this, dir, n.name, n.dir, n.size, n.modified, to, null);
    }

    @Override public boolean writable() { return true; }

    @Override public long freeBytes() { return -1; }

    @Override public long totalBytes() { return -1; }

    @Override public File localFile(VNode n) { return null; }

    @Override
    public synchronized void close() {
        try {
            if (sftp != null) sftp.disconnect();
        } catch (Exception ignored) {
        }
        try {
            if (session != null) session.disconnect();
        } catch (Exception ignored) {
        }
        sftp = null;
        session = null;
    }
}

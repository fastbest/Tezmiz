package uz.fastbest.kartformat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Başka bir telefondaki Kart Format'ın WiFi paylaşımına bağlanır (host:port, kod). */
public final class WifiFs implements Vfs {

    private final FtpFs.Server s;
    private final VNode root;

    public WifiFs(FtpFs.Server s) {
        this.s = s;
        root = new VNode(this, null, s.name, true, 0, 0, "/", null);
    }

    private static String rel(String path) {
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return v;
        }
    }

    private HttpURLConnection conn(String what, String query) throws IOException {
        String host = s.host.trim().replaceFirst("^https?://", "");
        if (!host.contains(":")) host += ":" + (s.port > 0 ? s.port : 8080);
        URL u = new URL("http://" + host + "/" + what + "?k=" + enc(s.pass == null ? "" : s.pass) + query);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        return c;
    }

    private static void check(HttpURLConnection c, String what) throws IOException {
        int code = c.getResponseCode();
        if (code >= 200 && code < 400) return;
        String msg = code + "";
        try (InputStream e = c.getErrorStream()) {
            if (e != null) {
                byte[] b = new byte[300];
                int n = e.read(b);
                if (n > 0) msg = new String(b, 0, n, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        throw new IOException(what + ": " + msg);
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    @Override public String title() { return s.name; }

    @Override public VNode root() { return root; }

    @Override
    public List<VNode> list(VNode dir) throws IOException {
        HttpURLConnection c = conn("ls", "&p=" + enc(rel(dir.path)));
        check(c, "Listelenemedi");
        String type = c.getContentType();
        if (type != null && type.contains("html")) {
            c.disconnect();
            throw new IOException("Kod yanlış ya da karşı telefonda eski sürüm var");
        }
        List<VNode> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                boolean d = f[0].equals("d");
                long size = 0, t = 0;
                try {
                    size = Long.parseLong(f[2]);
                    t = Long.parseLong(f[3]);
                } catch (NumberFormatException ignored) {
                }
                out.add(new VNode(this, dir, f[1], d, size, t, join(dir.path, f[1]), null));
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        final HttpURLConnection c = conn("get", "&p=" + enc(rel(f.path)));
        check(c, "İndirilemedi");
        return new FilterInputStream(c.getInputStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    c.disconnect();
                }
            }
        };
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel cancel) throws IOException {
        name = CardVolume.checkName(name);
        HttpURLConnection c = conn("put", "&p=" + enc(rel(dir.path)) + "&n=" + enc(name));
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        if (size >= 0) c.setFixedLengthStreamingMode(size);
        try (OutputStream os = c.getOutputStream()) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
                os.write(buf, 0, r);
                done += r;
                if (p != null) p.onProgress(done, Math.max(done, size));
            }
        }
        check(c, "Gönderilemedi");
        String real = name;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String l = r.readLine();
            if (l != null && !l.isEmpty()) real = l.trim();
        } finally {
            c.disconnect();
        }
        return new VNode(this, dir, real, false, size, System.currentTimeMillis(), join(dir.path, real), null);
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        name = CardVolume.checkName(name);
        HttpURLConnection c = conn("mkdir", "&p=" + enc(rel(dir.path)) + "&n=" + enc(name));
        c.setInstanceFollowRedirects(false);
        check(c, "Klasör oluşturulamadı");
        c.disconnect();
        return new VNode(this, dir, name, true, 0, System.currentTimeMillis(), join(dir.path, name), null);
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel cancel) throws IOException {
        HttpURLConnection c = conn("rm", "&p=" + enc(rel(n.path)));
        check(c, "Silinemedi");
        c.disconnect();
    }

    private VNode mv(VNode n, VNode parent, String newName) throws IOException {
        String to = join(parent != null ? parent.path : "/", newName);
        HttpURLConnection c = conn("mv", "&p=" + enc(rel(n.path)) + "&to=" + enc(rel(to)));
        check(c, "Taşınamadı");
        c.disconnect();
        return new VNode(this, parent, newName, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        return mv(n, n.parent, CardVolume.checkName(newName));
    }

    @Override
    public VNode move(VNode n, VNode dir) {
        try {
            return mv(n, dir, n.name);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public boolean writable() { return true; }

    @Override public long freeBytes() { return -1; }

    @Override public long totalBytes() { return -1; }

    @Override public File localFile(VNode n) { return null; }

    @Override public void close() { }
}

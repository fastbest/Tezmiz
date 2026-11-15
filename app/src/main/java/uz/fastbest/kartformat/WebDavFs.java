package uz.fastbest.kartformat;

import android.util.Base64;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * WebDAV bulut depolama (Nextcloud, ownCloud, Yandex Disk, Koofr, NAS…).
 * Sunucu kaydında host tam adres olabilir: "https://webdav.yandex.ru".
 */
public final class WebDavFs implements Vfs {

    private final FtpFs.Server s;
    private final String base;   // "https://host:port" (sonunda / yok)
    private final VNode root;

    public WebDavFs(FtpFs.Server s) {
        this.s = s;
        String h = s.host.trim();
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = (s.port == 80 ? "http://" : "https://") + h
                    + (s.port > 0 && s.port != 80 && s.port != 443 ? ":" + s.port : "");
        }
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        // host içinde yol varsa ayır
        String path = s.path == null || s.path.isEmpty() ? "/" : s.path;
        try {
            URI u = new URI(h);
            if (u.getRawPath() != null && u.getRawPath().length() > 1) {
                path = URLDecoder.decode(u.getRawPath(), "UTF-8") + (path.equals("/") ? "" : path);
                h = u.getScheme() + "://" + u.getRawAuthority();
            }
        } catch (Exception ignored) {
        }
        if (!path.startsWith("/")) path = "/" + path;
        base = h;
        root = new VNode(this, null, s.name, true, 0, 0, path, null);
    }

    private static String enc(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (sb.length() > 0 || path.startsWith("/")) sb.append('/');
            try {
                sb.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(part);
            }
        }
        String r = sb.toString().replace("//", "/");
        return r.isEmpty() ? "/" : r;
    }

    private HttpURLConnection conn(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + enc(path)).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            // PROPFIND, MKCOL, MOVE: HttpURLConnection izin vermez → yansımayla ayarla
            if (!forceMethod(c, method)) {
                c.setRequestProperty("X-HTTP-Method-Override", method);
                c.setRequestMethod("POST");
            }
        }
        if (s.user != null && !s.user.isEmpty() && !s.user.equals("anonymous")) {
            String a = s.user + ":" + (s.pass == null ? "" : s.pass);
            c.setRequestProperty("Authorization", "Basic "
                    + Base64.encodeToString(a.getBytes("UTF-8"), Base64.NO_WRAP));
        }
        return c;
    }

    /** HTTPS bağlantıları içteki asıl bağlantıya ("delegate") devreder; ikisine de yazılır. */
    private static boolean forceMethod(HttpURLConnection c, String method) {
        boolean ok = false;
        try {
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(c, method);
            ok = true;
            for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field d = k.getDeclaredField("delegate");
                    d.setAccessible(true);
                    Object inner = d.get(c);
                    if (inner instanceof HttpURLConnection) f.set(inner, method);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return ok;
    }

    private static void check(HttpURLConnection c, String what) throws IOException {
        int code = c.getResponseCode();
        if (code >= 200 && code < 300) return;
        String msg = code == 401 ? "kullanıcı adı ya da parola yanlış" : code + " " + c.getResponseMessage();
        c.disconnect();
        throw new IOException(what + ": " + msg);
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    @Override public String title() { return s.name; }

    @Override public VNode root() { return root; }

    @Override
    public List<VNode> list(VNode dir) throws IOException {
        HttpURLConnection c = conn("PROPFIND", dir.path.endsWith("/") ? dir.path : dir.path + "/");
        c.setRequestProperty("Depth", "1");
        c.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
        c.setDoOutput(true);
        byte[] body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
                + "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>")
                .getBytes("UTF-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        check(c, "Listelenemedi");
        List<VNode> out = new ArrayList<>();
        try (InputStream in = c.getInputStream()) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(in);
            NodeList rs = doc.getElementsByTagNameNS("DAV:", "response");
            String self = trimSlash(dir.path);
            SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            for (int i = 0; i < rs.getLength(); i++) {
                Element r = (Element) rs.item(i);
                String href = text(r, "href");
                if (href == null) continue;
                try {
                    href = new URI(href).getRawPath();
                } catch (Exception ignored) {
                }
                String p = trimSlash(URLDecoder.decode(href.replace("+", "%2B"), "UTF-8"));
                if (p.equals(self) || p.isEmpty()) continue;
                String name = p.substring(p.lastIndexOf('/') + 1);
                boolean isDir = r.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
                long size = 0, t = 0;
                try {
                    String len = text(r, "getcontentlength");
                    if (len != null) size = Long.parseLong(len.trim());
                } catch (NumberFormatException ignored) {
                }
                try {
                    String lm = text(r, "getlastmodified");
                    if (lm != null) t = df.parse(lm.trim()).getTime();
                } catch (Exception ignored) {
                }
                out.add(new VNode(this, dir, name, isDir, isDir ? 0 : size, t, join(dir.path, name), null));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sunucu yanıtı okunamadı: " + e.getMessage(), e);
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static String trimSlash(String p) {
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String text(Element e, String local) {
        NodeList l = e.getElementsByTagNameNS("DAV:", local);
        if (l.getLength() == 0) return null;
        Node n = l.item(0);
        return n.getTextContent();
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        final HttpURLConnection c = conn("GET", f.path);
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

    private String unique(VNode dir, String name) throws IOException {
        Set<String> names = new HashSet<>();
        for (VNode k : list(dir)) names.add(k.name.toLowerCase(Locale.ROOT));
        if (!names.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String cand = b + " (" + i + ")" + ext;
            if (!names.contains(cand.toLowerCase(Locale.ROOT))) return cand;
        }
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel cancel) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        HttpURLConnection c = conn("PUT", path);
        c.setDoOutput(true);
        if (size >= 0) c.setFixedLengthStreamingMode(size);
        else c.setChunkedStreamingMode(1 << 16);
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
        check(c, "Yüklenemedi");
        c.disconnect();
        return new VNode(this, dir, name, false, size, System.currentTimeMillis(), path, null);
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        name = unique(dir, CardVolume.checkName(name));
        String path = join(dir.path, name);
        HttpURLConnection c = conn("MKCOL", path);
        check(c, "Klasör oluşturulamadı");
        c.disconnect();
        return new VNode(this, dir, name, true, 0, System.currentTimeMillis(), path, null);
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel cancel) throws IOException {
        HttpURLConnection c = conn("DELETE", n.dir ? n.path + "/" : n.path);
        check(c, "Silinemedi");
        c.disconnect();
    }

    private VNode moveTo(VNode n, VNode parent, String newName) throws IOException {
        String to = join(parent != null ? parent.path : "/", newName);
        HttpURLConnection c = conn("MOVE", n.path);
        c.setRequestProperty("Destination", base + enc(to));
        c.setRequestProperty("Overwrite", "F");
        check(c, "Taşınamadı");
        c.disconnect();
        return new VNode(this, parent, newName, n.dir, n.size, n.modified, to, null);
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        return moveTo(n, n.parent, CardVolume.checkName(newName));
    }

    @Override
    public VNode move(VNode n, VNode dir) throws IOException {
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

    @Override public void close() { }
}

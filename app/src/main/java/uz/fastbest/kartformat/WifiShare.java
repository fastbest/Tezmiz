package uz.fastbest.kartformat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Aynı WiFi'deki bir bilgisayardan tarayıcıyla dosya indirme / yükleme (NanoHTTPD). */
final class WifiShare extends NanoHTTPD {

    private final File root;
    private final String code;
    final int port;

    private WifiShare(int port, File root, String code) {
        super(port);
        this.port = port;
        this.root = root;
        this.code = code;
    }

    static WifiShare start(File root, String code) throws IOException {
        IOException last = null;
        for (int port = 8080; port < 8090; port++) {
            WifiShare s = new WifiShare(port, root, code);
            try {
                s.start(SOCKET_READ_TIMEOUT, false);
                return s;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Sunucu başlatılamadı");
    }

    static String address() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) {
                        return a.getHostAddress();
                    }
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private File resolve(String p) throws IOException {
        File rootCanon = root.getCanonicalFile();
        File f = new File(rootCanon, p == null ? "" : p).getCanonicalFile();
        String rp = rootCanon.getPath();
        if (!f.getPath().equals(rp) && !f.getPath().startsWith(rp + File.separator)) {
            throw new IOException("izin yok");
        }
        return f;
    }

    private String rel(File f) throws IOException {
        String rp = root.getCanonicalFile().getPath();
        String p = f.getCanonicalFile().getPath();
        return p.length() <= rp.length() ? "" : p.substring(rp.length() + 1);
    }

    private static Response html(String body) {
        String page = "<!DOCTYPE html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Hujjat/boss</title><style>"
                + "body{font-family:sans-serif;background:#1e1f22;color:#e8e8e8;margin:0;padding:16px}"
                + "a{color:#7cc4ff;text-decoration:none}table{width:100%;border-collapse:collapse}"
                + "td{padding:8px;border-bottom:1px solid #333}td.s{text-align:right;color:#aaa;white-space:nowrap}"
                + ".box{background:#2a2b2f;padding:12px;border-radius:10px;margin:12px 0}"
                + "input,button{font-size:15px;padding:6px;border-radius:6px;border:1px solid #555;"
                + "background:#333;color:#eee}</style></head><body>" + body + "</body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page);
    }

    @Override
    public Response serve(IHTTPSession s) {
        try {
            @SuppressWarnings("deprecation")
            Map<String, String> parms = s.getParms();
            String key = parms.get("k");
            if (key == null) key = s.getCookies().read("k");
            if (!code.equals(key)) {
                return html("<h2>Hujjat/boss</h2><form><p>Telefonda görünen kodu girin:</p>"
                        + "<input name=k inputmode=numeric autofocus> <button>Gir</button></form>");
            }
            String uri = s.getUri();
            Response r;
            if (uri.equals("/get")) {
                r = download(parms.get("p"));
            } else if (uri.equals("/up") && s.getMethod() == Method.POST) {
                r = upload(s, parms.get("p"));
            } else if (uri.equals("/mkdir")) {
                File d = resolve(parms.get("p"));
                String n = CardVolume.checkName(parms.get("n"));
                File made = new File(d, LocalFs.unique(d, n));
                if (!made.mkdir()) throw new IOException("Klasör oluşturulamadı");
                r = redirect("/?p=" + enc(rel(d)));
            } else if (uri.equals("/ls")) {
                // Başka bir Kart Format'ın okuyacağı sade liste: tür \t ad \t boyut \t zaman
                File d = resolve(parms.get("p"));
                File[] fs = d.listFiles();
                StringBuilder sb = new StringBuilder();
                if (fs != null) {
                    for (File f : fs) {
                        sb.append(f.isDirectory() ? 'd' : 'f').append('\t').append(f.getName()).append('\t')
                                .append(f.isDirectory() ? 0 : f.length()).append('\t')
                                .append(f.lastModified()).append('\n');
                    }
                }
                r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", sb.toString());
            } else if (uri.equals("/put") && s.getMethod() == Method.PUT) {
                File d = resolve(parms.get("p"));
                String n = CardVolume.checkName(parms.get("n"));
                Map<String, String> files = new HashMap<>();
                s.parseBody(files);
                String tmp = files.get("content");
                if (tmp == null) throw new IOException("veri gelmedi");
                File out = new File(d, LocalFs.unique(d, n));
                try (InputStream in = new FileInputStream(tmp);
                     OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024)) {
                    byte[] buf = new byte[1 << 16];
                    int k;
                    while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                }
                r = newFixedLengthResponse(Response.Status.OK, "text/plain", out.getName());
            } else if (uri.equals("/rm")) {
                File f = resolve(parms.get("p"));
                if (f.equals(root.getCanonicalFile()) || !deleteRec(f)) throw new IOException("silinemedi");
                r = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
            } else if (uri.equals("/mv")) {
                File f = resolve(parms.get("p"));
                File to = resolve(parms.get("to"));
                if (to.exists() || !f.renameTo(to)) throw new IOException("taşınamadı");
                r = newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
            } else {
                r = listing(parms.get("p"));
            }
            r.addHeader("Set-Cookie", "k=" + code + "; Path=/; HttpOnly");
            return r;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8",
                    "Hata: " + e.getMessage());
        }
    }

    private static boolean deleteRec(File f) {
        File[] k = f.isDirectory() ? f.listFiles() : null;
        if (k != null) for (File c : k) deleteRec(c);
        return f.delete();
    }

    private static Response redirect(String to) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", to);
        return r;
    }

    private Response listing(String p) throws IOException {
        File d = resolve(p);
        if (!d.isDirectory()) return download(p);
        File[] files = d.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1)
                : a.getName().compareToIgnoreCase(b.getName()));
        String here = rel(d);
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>📁 ").append(esc(here.isEmpty() ? "Dahili depolama" : here)).append("</h2>");
        if (!here.isEmpty()) {
            File up = d.getParentFile();
            sb.append("<p><a href='/?p=").append(enc(rel(up))).append("'>⬆ Üst klasör</a></p>");
        }
        sb.append("<div class=box><b>Yükle:</b> <input type=file id=f multiple> "
                + "<button onclick=up()>Gönder</button> <span id=st></span>"
                + "<form action=/mkdir style='margin-top:8px'><input type=hidden name=p value=\"")
                .append(esc(here)).append("\"><input name=n placeholder='Yeni klasör adı'> "
                + "<button>Oluştur</button></form></div>");
        sb.append("<script>async function up(){var fs=document.getElementById('f').files;"
                + "var st=document.getElementById('st');for(var i=0;i<fs.length;i++){"
                + "st.textContent=(i+1)+'/'+fs.length+': '+fs[i].name;var d=new FormData();"
                + "d.append('f',fs[i],fs[i].name);var r=await fetch('/up?p=")
                .append(enc(here)).append("',{method:'POST',body:d});"
                + "if(!r.ok){st.textContent='Hata: '+await r.text();return;}}location.reload();}</script>");
        sb.append("<table>");
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        for (File f : files) {
            String rp = rel(f);
            sb.append("<tr><td>");
            if (f.isDirectory()) {
                sb.append("📁 <a href='/?p=").append(enc(rp)).append("'>").append(esc(f.getName()))
                        .append("</a></td><td class=s></td>");
            } else {
                sb.append("📄 <a href='/get?p=").append(enc(rp)).append("'>").append(esc(f.getName()))
                        .append("</a></td><td class=s>").append(DiskOps.sizeText(f.length()))
                        .append("</td>");
            }
            sb.append("<td class=s>").append(df.format(new Date(f.lastModified())))
                    .append("</td></tr>");
        }
        sb.append("</table>");
        return html(sb.toString());
    }

    private Response download(String p) throws IOException {
        File f = resolve(p);
        if (!f.isFile()) throw new IOException("dosya yok");
        String mime = NanoHTTPD.getMimeTypeForFile(f.getName());
        Response r = newFixedLengthResponse(Response.Status.OK,
                mime == null ? "application/octet-stream" : mime, new FileInputStream(f), f.length());
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + enc(f.getName()));
        return r;
    }

    private Response upload(IHTTPSession s, String p) throws Exception {
        File d = resolve(p);
        Map<String, String> files = new HashMap<>();
        s.parseBody(files);
        String tmp = files.get("f");
        @SuppressWarnings("deprecation")
        String name = s.getParms().get("f");
        if (tmp == null || name == null) throw new IOException("dosya gelmedi");
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = CardVolume.checkName(name.substring(slash + 1));
        File out = new File(d, LocalFs.unique(d, name));
        try (InputStream in = new FileInputStream(tmp);
             OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok");
    }
}

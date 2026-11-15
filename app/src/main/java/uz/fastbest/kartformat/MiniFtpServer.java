package uz.fastbest.kartformat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telefonda çalışan küçük FTP sunucusu (yalnız pasif kip). Bilgisayardaki Windows Gezgini,
 * FileZilla vb. ile telefon belleğine bağlanmak için.
 */
public final class MiniFtpServer {

    public final int port;
    private final File root;
    private final String user;
    private final String pass;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    public MiniFtpServer(File root, int port, String user, String pass) {
        this.root = root;
        this.port = port;
        this.user = user;
        this.pass = pass;
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> new Session(s).run());
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, "ftp-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private final class Session {
        private final Socket sock;
        private Writer out;
        private String cwd = "/";
        private boolean userOk, authed;
        private ServerSocket pasv;
        private String renameFrom;
        private long restart;

        Session(Socket s) {
            sock = s;
        }

        void reply(String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
        }

        void run() {
            try {
                sock.setSoTimeout(300000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(),
                        StandardCharsets.UTF_8));
                out = new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8);
                reply("220 Kart Format FTP");
                String line;
                while ((line = in.readLine()) != null) {
                    String cmd = line, arg = "";
                    int sp = line.indexOf(' ');
                    if (sp > 0) {
                        cmd = line.substring(0, sp);
                        arg = line.substring(sp + 1);
                    }
                    cmd = cmd.toUpperCase(Locale.ROOT);
                    if (!handle(cmd, arg)) break;
                }
            } catch (IOException ignored) {
            } finally {
                closePasv();
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }

        private boolean handle(String cmd, String arg) throws IOException {
            switch (cmd) {
                case "USER":
                    userOk = arg.equals(user) || arg.equalsIgnoreCase("anonymous") && pass.isEmpty();
                    reply("331 Parola gerekli");
                    return true;
                case "PASS":
                    if (userOk && (pass.isEmpty() || arg.equals(pass))) {
                        authed = true;
                        reply("230 Giriş yapıldı");
                    } else {
                        reply("530 Kullanıcı adı ya da parola yanlış");
                    }
                    return true;
                case "QUIT":
                    reply("221 Güle güle");
                    return false;
                case "SYST":
                    reply("215 UNIX Type: L8");
                    return true;
                case "FEAT":
                    out.write("211-Özellikler\r\n UTF8\r\n MLSD\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n PASV\r\n");
                    reply("211 Son");
                    return true;
                case "OPTS":
                    reply("200 Tamam");
                    return true;
                case "NOOP":
                    reply("200 Tamam");
                    return true;
                default:
                    break;
            }
            if (!authed) {
                reply("530 Önce giriş yapın");
                return true;
            }
            switch (cmd) {
                case "PWD":
                case "XPWD":
                    reply("257 \"" + cwd.replace("\"", "\"\"") + "\"");
                    break;
                case "CWD":
                case "XCWD": {
                    String p = norm(arg);
                    File f = file(p);
                    if (f != null && f.isDirectory()) {
                        cwd = p;
                        reply("250 Tamam");
                    } else {
                        reply("550 Klasör yok");
                    }
                    break;
                }
                case "CDUP":
                    cwd = norm("..");
                    reply("250 Tamam");
                    break;
                case "TYPE":
                case "MODE":
                case "STRU":
                    reply("200 Tamam");
                    break;
                case "PASV": {
                    openPasv();
                    String ip = sock.getLocalAddress().getHostAddress();
                    if (ip.contains(":")) ip = "127.0.0.1";
                    int p = pasv.getLocalPort();
                    reply("227 Entering Passive Mode (" + ip.replace('.', ',') + "," + (p >> 8) + "," + (p & 255) + ")");
                    break;
                }
                case "EPSV":
                    openPasv();
                    reply("229 Entering Extended Passive Mode (|||" + pasv.getLocalPort() + "|)");
                    break;
                case "PORT":
                case "EPRT":
                    reply("502 Yalnız pasif kip desteklenir");
                    break;
                case "LIST":
                case "NLST":
                case "MLSD": {
                    String a = arg.startsWith("-") ? "" : arg;
                    File d = file(norm(a.isEmpty() ? "." : a));
                    if (d == null || !d.exists()) {
                        reply("550 Bulunamadı");
                        break;
                    }
                    File[] list = d.isDirectory() ? d.listFiles() : new File[]{d};
                    StringBuilder sb = new StringBuilder();
                    if (list != null) {
                        for (File f : list) {
                            if (cmd.equals("NLST")) sb.append(f.getName());
                            else if (cmd.equals("MLSD")) sb.append(mlsx(f));
                            else sb.append(unixLine(f));
                            sb.append("\r\n");
                        }
                    }
                    sendData(sb.toString().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "MLST": {
                    File f = file(norm(arg.isEmpty() ? "." : arg));
                    if (f == null || !f.exists()) {
                        reply("550 Bulunamadı");
                        break;
                    }
                    out.write("250-Bilgi\r\n " + mlsx(f) + "\r\n");
                    reply("250 Son");
                    break;
                }
                case "SIZE": {
                    File f = file(norm(arg));
                    if (f != null && f.isFile()) reply("213 " + f.length());
                    else reply("550 Bulunamadı");
                    break;
                }
                case "MDTM": {
                    File f = file(norm(arg));
                    if (f != null && f.exists()) reply("213 " + stamp(f.lastModified()));
                    else reply("550 Bulunamadı");
                    break;
                }
                case "REST":
                    try {
                        restart = Long.parseLong(arg.trim());
                        reply("350 Konum " + restart);
                    } catch (NumberFormatException e) {
                        reply("501 Geçersiz");
                    }
                    break;
                case "RETR": {
                    File f = file(norm(arg));
                    if (f == null || !f.isFile()) {
                        reply("550 Bulunamadı");
                        break;
                    }
                    Socket d = acceptData();
                    if (d == null) break;
                    try (InputStream in = new FileInputStream(f); OutputStream os = d.getOutputStream()) {
                        if (restart > 0) in.skip(restart);
                        copy(in, os);
                        reply("226 Tamam");
                    } catch (IOException e) {
                        reply("426 Aktarım kesildi");
                    } finally {
                        restart = 0;
                        d.close();
                    }
                    break;
                }
                case "STOR":
                case "APPE": {
                    File f = file(norm(arg));
                    if (f == null) {
                        reply("550 Geçersiz yol");
                        break;
                    }
                    Socket d = acceptData();
                    if (d == null) break;
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
                         InputStream in = d.getInputStream()) {
                        long start = cmd.equals("APPE") ? raf.length() : restart;
                        raf.setLength(start);
                        raf.seek(start);
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) raf.write(buf, 0, r);
                        reply("226 Tamam");
                    } catch (IOException e) {
                        reply("426 Aktarım kesildi");
                    } finally {
                        restart = 0;
                        d.close();
                    }
                    break;
                }
                case "MKD":
                case "XMKD": {
                    String p = norm(arg);
                    File f = file(p);
                    if (f != null && f.mkdir()) reply("257 \"" + p + "\" oluşturuldu");
                    else reply("550 Oluşturulamadı");
                    break;
                }
                case "RMD":
                case "XRMD":
                case "DELE": {
                    File f = file(norm(arg));
                    if (f != null && f.exists() && f.delete()) reply("250 Silindi");
                    else reply("550 Silinemedi");
                    break;
                }
                case "RNFR": {
                    File f = file(norm(arg));
                    if (f != null && f.exists()) {
                        renameFrom = norm(arg);
                        reply("350 Yeni adı bekleniyor");
                    } else {
                        reply("550 Bulunamadı");
                    }
                    break;
                }
                case "RNTO": {
                    File a = renameFrom == null ? null : file(renameFrom);
                    File b = file(norm(arg));
                    renameFrom = null;
                    if (a != null && b != null && a.renameTo(b)) reply("250 Tamam");
                    else reply("550 Yeniden adlandırılamadı");
                    break;
                }
                default:
                    reply("502 Desteklenmiyor");
            }
            return true;
        }

        private void openPasv() throws IOException {
            closePasv();
            pasv = new ServerSocket(0);
            pasv.setSoTimeout(20000);
        }

        private void closePasv() {
            try {
                if (pasv != null) pasv.close();
            } catch (IOException ignored) {
            }
            pasv = null;
        }

        private Socket acceptData() throws IOException {
            if (pasv == null) {
                reply("425 Önce PASV kullanın");
                return null;
            }
            reply("150 Veri bağlantısı açılıyor");
            try {
                return pasv.accept();
            } catch (IOException e) {
                reply("425 Veri bağlantısı kurulamadı");
                return null;
            } finally {
                closePasv();
            }
        }

        private void sendData(byte[] data) throws IOException {
            Socket d = acceptData();
            if (d == null) return;
            try (OutputStream os = d.getOutputStream()) {
                os.write(data);
                reply("226 Tamam");
            } catch (IOException e) {
                reply("426 Aktarım kesildi");
            } finally {
                d.close();
            }
        }

        /** Sanal yol (/a/b) üretir; kökün dışına çıkılamaz. */
        private String norm(String arg) {
            String p = arg.startsWith("/") ? arg : (cwd.endsWith("/") ? cwd : cwd + "/") + arg;
            java.util.ArrayDeque<String> st = new java.util.ArrayDeque<>();
            for (String part : p.split("/")) {
                if (part.isEmpty() || part.equals(".")) continue;
                if (part.equals("..")) {
                    if (!st.isEmpty()) st.removeLast();
                } else {
                    st.addLast(part);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String s : st) sb.append('/').append(s);
            return sb.length() == 0 ? "/" : sb.toString();
        }

        private File file(String virtual) {
            File f = virtual.equals("/") ? root : new File(root, virtual.substring(1));
            try {
                String rc = root.getCanonicalPath();
                String fc = f.getCanonicalPath();
                if (!fc.equals(rc) && !fc.startsWith(rc + "/")) return null;
            } catch (IOException e) {
                return null;
            }
            return f;
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
    }

    private static String stamp(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    private static String mlsx(File f) {
        return "type=" + (f.isDirectory() ? "dir" : "file") + ";size=" + (f.isDirectory() ? 0 : f.length())
                + ";modify=" + stamp(f.lastModified()) + "; " + f.getName();
    }

    private static String unixLine(File f) {
        long t = f.lastModified();
        boolean recent = System.currentTimeMillis() - t < 180L * 24 * 3600 * 1000;
        String date = new SimpleDateFormat(recent ? "MMM dd HH:mm" : "MMM dd  yyyy", Locale.US).format(new Date(t));
        return (f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--") + " 1 owner group "
                + String.format(Locale.ROOT, "%13d", f.isDirectory() ? 0 : f.length()) + " " + date + " " + f.getName();
    }

    /** Telefonun WiFi adresi (yoksa null). */
    public static String address() {
        return WifiShare.address();
    }

    @SuppressWarnings("unused")
    private static boolean isLocal(InetAddress a) {
        return a.isSiteLocalAddress();
    }
}

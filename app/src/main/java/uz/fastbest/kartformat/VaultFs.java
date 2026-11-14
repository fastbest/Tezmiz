package uz.fastbest.kartformat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Şifreli kasa: her dosya AES-256-CTR ile şifrelenir, HMAC-SHA256 ile doğrulanır.
 * Dosya adı da şifrelidir. Anahtar paroladan PBKDF2 ile türetilir.
 * Dosya biçimi: "KVLT1" + 16 bayt IV + şifreli [2 bayt ad uzunluğu, ad, veri] + 32 bayt HMAC.
 */
public final class VaultFs implements Vfs {

    private static final byte[] MAGIC = "KVLT1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEAD = 5 + 16;
    private static final int MAC_LEN = 32;
    static final int ITERATIONS = 120000;

    private final File dir;
    private final byte[] encKey;
    private final byte[] macKey;
    private final VNode root;

    private VaultFs(File dir, byte[] keys) {
        this.dir = dir;
        this.encKey = Arrays.copyOfRange(keys, 0, 32);
        this.macKey = Arrays.copyOfRange(keys, 32, 64);
        this.root = new VNode(this, null, "Kasa", true, 0, 0, "/", dir);
    }

    // ------------------------------------------------------------ anahtar

    static byte[] derive(String password, byte[] salt) throws IOException {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 512))
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("Anahtar türetilemedi: " + e.getMessage());
        }
    }

    static byte[] verifier(byte[] keys) throws IOException {
        return hmac(Arrays.copyOfRange(keys, 32, 64), "kasa-dogrulama".getBytes(StandardCharsets.US_ASCII));
    }

    /** Yeni kasa için tuz ve doğrulayıcı üretir: {tuz, doğrulayıcı}. */
    static byte[][] setup(String password) throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] keys = derive(password, salt);
        return new byte[][]{salt, verifier(keys)};
    }

    /** Parola doğruysa kasayı açar, yanlışsa null döner. */
    static VaultFs unlock(File dir, String password, byte[] salt, byte[] verifier)
            throws IOException {
        byte[] keys = derive(password, salt);
        if (!MessageDigest.isEqual(verifier(keys), verifier)) return null;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Kasa klasörü oluşturulamadı");
        return new VaultFs(dir, keys);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage());
        }
    }

    private Mac mac() throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return m;
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage());
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(mode, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ------------------------------------------------------------ dosyalar

    private static final class Info {
        File file;
        String name;
        long size;
        int nameLen;
    }

    private Info info(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < HEAD + 2 + MAC_LEN) throw new IOException("bozuk");
            byte[] head = new byte[HEAD];
            r.readFully(head);
            if (!Arrays.equals(Arrays.copyOf(head, 5), MAGIC)) throw new IOException("bozuk");
            byte[] iv = Arrays.copyOfRange(head, 5, HEAD);
            byte[] enc = new byte[(int) Math.min(2 + 1024, r.length() - HEAD - MAC_LEN)];
            r.readFully(enc);
            byte[] plain = cipher(Cipher.DECRYPT_MODE, iv).update(enc);
            int len = (plain[0] & 0xFF) | ((plain[1] & 0xFF) << 8);
            if (len <= 0 || 2 + len > plain.length) throw new IOException("bozuk");
            Info i = new Info();
            i.file = f;
            i.nameLen = len;
            i.name = new String(plain, 2, len, StandardCharsets.UTF_8);
            i.size = r.length() - HEAD - MAC_LEN - 2 - len;
            return i;
        }
    }

    @Override
    public String title() {
        return "Kasa";
    }

    @Override
    public VNode root() {
        return root;
    }

    @Override
    public List<VNode> list(VNode d) {
        List<VNode> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".kv")) continue;
            try {
                Info i = info(f);
                out.add(new VNode(this, root, i.name, false, i.size, f.lastModified(),
                        "/" + f.getName(), i));
            } catch (IOException ignored) {
                // başka paroladan ya da bozuk dosya: gösterilmez
            }
        }
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        Info i = (Info) f.handle;
        long total = i.file.length();
        // 1) Bütünlüğü doğrula
        Mac m = mac();
        byte[] expect = new byte[MAC_LEN];
        try (InputStream in = new BufferedInputStream(new FileInputStream(i.file), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            long left = total - MAC_LEN;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("Kasa dosyası eksik");
                m.update(buf, 0, n);
                left -= n;
            }
            new DataInputStream(in).readFully(expect);
        }
        if (!MessageDigest.isEqual(m.doFinal(), expect)) {
            throw new IOException("Kasa dosyası bozulmuş ya da değiştirilmiş: " + i.name);
        }
        // 2) Şifreyi çözerek oku
        final FileInputStream fin = new FileInputStream(i.file);
        byte[] head = new byte[HEAD];
        new DataInputStream(fin).readFully(head);
        final long dataLen = total - HEAD - MAC_LEN;
        InputStream limited = new InputStream() {
            long left = dataLen;

            @Override
            public int read() throws IOException {
                if (left <= 0) return -1;
                left--;
                return fin.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (left <= 0) return -1;
                int n = fin.read(b, off, (int) Math.min(len, left));
                if (n > 0) left -= n;
                return n;
            }

            @Override
            public void close() throws IOException {
                fin.close();
            }
        };
        InputStream plain = new CipherInputStream(limited,
                cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(head, 5, HEAD)));
        DataInputStream d = new DataInputStream(plain);
        d.readFully(new byte[2 + i.nameLen]);   // ad başlığını atla
        return new BufferedInputStream(plain, 256 * 1024);
    }

    @Override
    public VNode create(VNode d, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        name = CardVolume.checkName(name);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 1000) throw new IOException("Ad çok uzun");
        byte[] rnd = new byte[12];
        SecureRandom sr = new SecureRandom();
        sr.nextBytes(rnd);
        StringBuilder fn = new StringBuilder();
        for (byte b : rnd) fn.append(String.format("%02x", b));
        File target = new File(dir, fn + ".kv");
        byte[] iv = new byte[16];
        sr.nextBytes(iv);
        final Mac m = mac();
        boolean ok = false;
        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(target), 256 * 1024)) {
            raw.write(MAGIC);
            raw.write(iv);
            m.update(MAGIC);
            m.update(iv);
            OutputStream macTee = new FilterOutputStream(raw) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    m.update(b, off, len);
                    out.write(b, off, len);
                }

                @Override
                public void write(int b) throws IOException {
                    m.update((byte) b);
                    out.write(b);
                }

                @Override
                public void close() throws IOException {
                    flush();   // alttaki akış ayrıca kapatılır
                }
            };
            CipherOutputStream enc = new CipherOutputStream(macTee, cipher(Cipher.ENCRYPT_MODE, iv));
            enc.write(new byte[]{(byte) nameBytes.length, (byte) (nameBytes.length >>> 8)});
            enc.write(nameBytes);
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (c != null && c.isCancelled()) throw new DiskOps.CancelledException();
                enc.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, Math.max(done, size));
            }
            enc.close();
            raw.write(m.doFinal());
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
        Info i = info(target);
        return new VNode(this, root, i.name, false, i.size, target.lastModified(),
                "/" + target.getName(), i);
    }

    @Override
    public VNode mkdir(VNode d, String name) throws IOException {
        throw new IOException("Kasada klasör oluşturulamaz");
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        File f = ((Info) n.handle).file;
        // Silmeden önce üzerine rastgele veri yazılır
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            long len = Math.min(r.length(), 64L * 1024 * 1024);
            byte[] junk = new byte[64 * 1024];
            SecureRandom sr = new SecureRandom();
            for (long pos = 0; pos < len; pos += junk.length) {
                sr.nextBytes(junk);
                r.write(junk, 0, (int) Math.min(junk.length, len - pos));
            }
        } catch (IOException ignored) {
        }
        if (!f.delete()) throw new IOException("Silinemedi: " + n.name);
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        Info i = (Info) n.handle;
        VNode made;
        try (InputStream in = open(n)) {
            made = create(root, newName, in, i.size, null, null);
        }
        //noinspection ResultOfMethodCallIgnored
        i.file.delete();
        return made;
    }

    @Override
    public VNode move(VNode n, VNode d) {
        return null;
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public long freeBytes() {
        return dir.getUsableSpace();
    }

    @Override
    public long totalBytes() {
        return dir.getTotalSpace();
    }

    @Override
    public File localFile(VNode n) {
        return null;
    }

    @Override
    public void close() {
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(macKey, (byte) 0);
    }
}

package uz.fastbest.kartformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/** Kasa (parolalı ZIP): ekleme, klasör, ad değiştirme, taşıma, silme, parola. */
public class Zip4jFsTest {

    private static String read(Vfs fs, VNode n) throws IOException {
        try (InputStream in = fs.open(n)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static VNode find(Vfs fs, VNode dir, String name) throws IOException {
        for (VNode k : fs.list(dir)) if (k.name.equals(name)) return k;
        return null;
    }

    private static VNode add(Vfs fs, VNode dir, String name, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return fs.create(dir, name, new ByteArrayInputStream(b), b.length, null, null);
    }

    @Test
    public void vaultOperations() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "kasa-test-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File z = new File(dir, "Kasa.zip");
        Zip4jFs fs = Zip4jFs.create(z, "1234", "Kasa");
        VNode root = fs.root();
        assertEquals(0, fs.list(root).size());

        add(fs, root, "a.txt", "merhaba");
        add(fs, root, "ğüş çalışma.txt", "türkçe");
        VNode d = fs.mkdir(root, "Belgeler");
        assertNotNull(find(fs, fs.root(), "Belgeler"));
        add(fs, find(fs, fs.root(), "Belgeler"), "b.txt", "ikinci");
        assertEquals(3, fs.list(fs.root()).size());
        assertEquals("ikinci", read(fs, find(fs, find(fs, fs.root(), "Belgeler"), "b.txt")));

        // ad değiştir
        fs.rename(find(fs, fs.root(), "a.txt"), "c.txt");
        assertNotNull(find(fs, fs.root(), "c.txt"));
        assertEquals("merhaba", read(fs, find(fs, fs.root(), "c.txt")));

        // kasa içinde taşı
        VNode moved = fs.move(find(fs, fs.root(), "c.txt"), find(fs, fs.root(), "Belgeler"));
        assertNotNull(moved);
        assertEquals(null, find(fs, fs.root(), "c.txt"));
        assertEquals("merhaba", read(fs, find(fs, find(fs, fs.root(), "Belgeler"), "c.txt")));

        // dosya sil
        fs.delete(find(fs, fs.root(), "ğüş çalışma.txt"), null);
        assertEquals(null, find(fs, fs.root(), "ğüş çalışma.txt"));

        // klasör ad değiştir, sonra sil
        fs.rename(find(fs, fs.root(), "Belgeler"), "Evrak");
        VNode ev = find(fs, fs.root(), "Evrak");
        assertNotNull(ev);
        assertEquals(2, fs.list(ev).size());
        fs.delete(ev, null);
        assertEquals(0, fs.list(fs.root()).size());
        add(fs, fs.root(), "son.txt", "kalıcı");
        fs.close();

        // yeniden aç: doğru parola
        Zip4jFs again = Zip4jFs.open(z, "1234", "Kasa");
        List<VNode> l = again.list(again.root());
        assertEquals(1, l.size());
        assertEquals("kalıcı", read(again, l.get(0)));
        again.close();
        assertTrue(Zip4jFs.isEncrypted(z));

        // yanlış parola
        try {
            Zip4jFs.open(z, "9999", "Kasa");
            fail("yanlış parola kabul edildi");
        } catch (IOException e) {
            assertEquals("Parola yanlış", e.getMessage());
        }
    }
}

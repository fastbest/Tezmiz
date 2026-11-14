package uz.fastbest.kartformat;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Arşivleri (zip, tar, tar.gz/bz2/xz, 7z) ve ISO görüntülerini karttaki bir klasöre açar. */
final class Extractor {

    /** Açılan dosyaların yazılacağı yer (USB kart ya da herhangi bir Vfs). */
    interface Target {
        Object root();

        /** {ad, klasör mü, nesne} */
        List<Object[]> list(Object dir) throws IOException;

        Object mkdir(Object dir, String name) throws IOException;

        /** Oluşan dosyanın adını döndürür. */
        String create(Object dir, String name, InputStream in, long size,
                      Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException;

        String fold(String name);
    }

    /** USB karttaki bir klasör. */
    static Target card(final CardVolume vol, final CardVolume.Entry root) {
        return new Target() {
            @Override
            public Object root() {
                return root;
            }

            @Override
            public List<Object[]> list(Object dir) throws IOException {
                List<Object[]> out = new java.util.ArrayList<>();
                for (CardVolume.Entry k : vol.list((CardVolume.Entry) dir)) {
                    out.add(new Object[]{k.name, k.dir, k});
                }
                return out;
            }

            @Override
            public Object mkdir(Object dir, String name) throws IOException {
                return vol.mkdir((CardVolume.Entry) dir, name);
            }

            @Override
            public String create(Object dir, String name, InputStream in, long size,
                                 Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
                return vol.create((CardVolume.Entry) dir, name, in, size, p, c).name;
            }

            @Override
            public String fold(String name) {
                return vol.fold(name);
            }
        };
    }

    private final Target vol;
    private final Fat32Formatter.Progress progress;
    private final DiskOps.Cancel cancel;
    private final Map<Object, Map<String, Object[]>> dirCache = new HashMap<>();
    private int files;
    private Status status;

    interface Status {
        void show(String text);
    }

    Extractor(CardVolume vol, Fat32Formatter.Progress progress, DiskOps.Cancel cancel,
              Status status) {
        this(card(vol, vol.root()), progress, cancel, status);
    }

    Extractor(Target target, Fat32Formatter.Progress progress, DiskOps.Cancel cancel,
              Status status) {
        this.vol = target;
        this.progress = progress;
        this.cancel = cancel;
        this.status = status;
    }

    int files() {
        return files;
    }

    /** Dosyanın türünü baştaki baytlarına bakarak bulur. */
    static String detect(RandomSource src, String name) throws IOException {
        byte[] m = new byte[264];
        int n = (int) Math.min(m.length, src.size());
        src.readFully(0, m, 0, n);
        if (n >= 4 && m[0] == 'P' && m[1] == 'K' && (m[2] == 3 || m[2] == 5 || m[2] == 7)) return "zip";
        if (n >= 6 && m[0] == '7' && m[1] == 'z' && (m[2] & 0xFF) == 0xBC && (m[3] & 0xFF) == 0xAF)
            return "7z";
        if (n >= 2 && (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B) return "tar.gz";
        if (n >= 3 && m[0] == 'B' && m[1] == 'Z' && m[2] == 'h') return "tar.bz2";
        if (n >= 6 && (m[0] & 0xFF) == 0xFD && m[1] == '7' && m[2] == 'z' && m[3] == 'X') return "tar.xz";
        if (n >= 262 && m[257] == 'u' && m[258] == 's' && m[259] == 't' && m[260] == 'a'
                && m[261] == 'r') return "tar";
        if (n >= 4 && m[0] == 'R' && m[1] == 'a' && m[2] == 'r' && m[3] == '!') return "rar";
        if (src.size() > 17 * 2048) {
            byte[] v = new byte[6];
            src.readFully(16 * 2048, v, 0, 6);
            if (v[1] == 'C' && v[2] == 'D' && v[3] == '0' && v[4] == '0' && v[5] == '1') return "iso";
            if (src.size() > 257 * 2048) {
                byte[] a = new byte[2];
                src.readFully(256 * 2048, a, 0, 2);
                if (a[0] == 2 && a[1] == 0) return "iso";
            }
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".tar")) return "tar";
        return null;
    }

    /** Arşivi verilen klasörün içine açar. */
    void extract(String type, RandomSource src, FileChannel channel, Object target)
            throws IOException {
        if (target == null) target = vol.root();
        if (type.equals("iso")) {
            UdfReader.Volume v = IsoImage.open(src);
            long total = total(v.root);
            final long[] done = {0};
            extractIso(src, v.root, target, total, done, 0);
            return;
        }
        if (type.equals("7z")) {
            if (android.os.Build.VERSION.SDK_INT < 24) {
                throw new IOException("7z arşivleri Android 7.0 ve üstünde açılabilir");
            }
            try (SevenZFile z = new SevenZFile(channel)) {
                SevenZArchiveEntry e;
                final SevenZFile zf = z;
                InputStream in = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        return zf.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return zf.read(b, off, len);
                    }
                };
                while ((e = z.getNextEntry()) != null) {
                    check();
                    if (e.isDirectory()) {
                        dirFor(target, e.getName(), true);
                    } else if (e.hasStream()) {
                        put(target, e.getName(), in, e.getSize());
                    } else {
                        put(target, e.getName(), new java.io.ByteArrayInputStream(new byte[0]), 0);
                    }
                    report(channel);
                }
            }
            return;
        }
        if (type.equals("rar")) {
            throw new IOException("RAR arşivleri desteklenmiyor (zip, 7z ve tar desteklenir)");
        }
        InputStream raw = new BufferedInputStream(new IsoImage.Stream(src, 0, src.size()), 65536);
        ArchiveInputStream ais;
        switch (type) {
            case "zip":
                ais = new ZipArchiveInputStream(raw, "UTF-8", true, true);
                break;
            case "tar.gz":
                ais = new TarArchiveInputStream(new GzipCompressorInputStream(raw, true));
                break;
            case "tar.bz2":
                ais = new TarArchiveInputStream(new BZip2CompressorInputStream(raw, true));
                break;
            case "tar.xz":
                ais = new TarArchiveInputStream(new org.tukaani.xz.XZInputStream(raw, -1));
                break;
            default:
                ais = new TarArchiveInputStream(raw);
        }
        try {
            ArchiveEntry e;
            while ((e = ais.getNextEntry()) != null) {
                check();
                if (!ais.canReadEntryData(e)) {
                    throw new IOException(e.getName() + " açılamıyor (şifreli ya da desteklenmeyen "
                            + "sıkıştırma)");
                }
                if (e.isDirectory()) {
                    dirFor(target, e.getName(), true);
                } else {
                    put(target, e.getName(), new FilterInputStream(ais) {
                        @Override
                        public void close() {
                            // arşiv akışı açık kalmalı
                        }
                    }, e.getSize());
                }
                report(channel);
            }
        } finally {
            ais.close();
        }
    }

    private void report(FileChannel channel) {
        if (progress == null || channel == null) return;
        try {
            progress.onProgress(channel.position(), channel.size());
        } catch (IOException ignored) {
        }
    }

    private static long total(UdfReader.Node n) {
        long s = n.dir ? 0 : n.size;
        for (UdfReader.Node k : n.kids) s += total(k);
        return s;
    }

    private void extractIso(RandomSource src, UdfReader.Node node, Object dir,
                            long total, long[] done, int depth) throws IOException {
        if (depth > 32) throw new IOException("Klasör yapısı çok derin");
        for (UdfReader.Node k : node.kids) {
            check();
            if (k.dir) {
                extractIso(src, k, child(dir, k.name), total, done, depth + 1);
            } else {
                if (status != null) status.show(files + 1 + ": " + k.name);
                final long base = done[0];
                InputStream in = new IsoImage.Stream(new UdfReader.FileView(src, k), 0, k.size);
                vol.create(dir, k.name, in, k.size,
                        (d, t) -> {
                            if (progress != null) progress.onProgress(base + d, total);
                        }, cancel);
                done[0] += k.size;
                files++;
            }
        }
    }

    private void check() throws DiskOps.CancelledException {
        if (cancel != null && cancel.isCancelled()) throw new DiskOps.CancelledException();
    }

    private void put(Object root, String path, InputStream in, long size)
            throws IOException {
        String[] parts = split(path);
        if (parts.length == 0) return;
        Object dir = root;
        for (int i = 0; i < parts.length - 1; i++) dir = child(dir, parts[i]);
        String name = parts[parts.length - 1];
        if (status != null) status.show(files + 1 + ": " + name);
        String made = vol.create(dir, name, in, size, null, cancel);
        Map<String, Object[]> m = dirCache.get(dir);
        if (m != null) m.put(vol.fold(made), new Object[]{made, false, null});
        files++;
    }

    private Object dirFor(Object root, String path, boolean all) throws IOException {
        Object dir = root;
        for (String p : split(path)) dir = child(dir, p);
        return dir;
    }

    /** Klasörde aynı adlı klasör varsa onu, yoksa yenisini verir. */
    private Object child(Object dir, String name) throws IOException {
        Map<String, Object[]> m = dirCache.get(dir);
        if (m == null) {
            m = new HashMap<>();
            for (Object[] k : vol.list(dir)) m.put(vol.fold((String) k[0]), k);
            dirCache.put(dir, m);
        }
        String clean = CardVolume.checkName(name);
        Object[] e = m.get(vol.fold(clean));
        if (e != null && (Boolean) e[1] && e[2] != null) return e[2];
        Object made = vol.mkdir(dir, clean);
        m.put(vol.fold(clean), new Object[]{clean, true, made});
        return made;
    }

    /** "a/b/../c" gibi yolları güvenli parçalara ayırır (dışarı çıkan ".." atılır). */
    static String[] split(String path) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String p : path.replace('\\', '/').split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(p);
        }
        return out.toArray(new String[0]);
    }
}

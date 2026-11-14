package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** ISO 9660 (Joliet uzun adlarıyla) okuyucu; UDF'siz ISO'lar ve PS2 diskleri için. */
final class Iso9660Reader {

    private static final int S = 2048;
    private static final int MAX_NODES = 200000;

    private final RandomSource src;
    private boolean joliet;
    private int nodes;
    private final Set<Long> visited = new HashSet<>();

    private Iso9660Reader(RandomSource src) {
        this.src = src;
    }

    static UdfReader.Volume read(RandomSource src) throws IOException {
        return new Iso9660Reader(src).run();
    }

    private byte[] sector(long n) throws IOException {
        byte[] b = new byte[S];
        if ((n + 1) * S > src.size()) throw new IOException("ISO dosyası eksik ya da bozuk");
        src.readFully(n * S, b, 0, S);
        return b;
    }

    private UdfReader.Volume run() throws IOException {
        byte[] pvd = null;
        byte[] svd = null;
        for (long n = 16; n < 64; n++) {
            byte[] d = sector(n);
            if (!new String(d, 1, 5, StandardCharsets.US_ASCII).equals("CD001")) break;
            int type = d[0] & 0xFF;
            if (type == 1 && pvd == null) pvd = d;
            if (type == 2 && d[88] == 0x25 && d[89] == 0x2F
                    && (d[90] == 0x40 || d[90] == 0x43 || d[90] == 0x45)) svd = d;
            if (type == 255) break;
        }
        if (pvd == null) throw new IOException("Bu dosya ISO görüntüsü değil");
        byte[] use = svd != null ? svd : pvd;
        joliet = svd != null;
        UdfReader.Volume v = new UdfReader.Volume();
        v.label = joliet ? new String(use, 40, 32, StandardCharsets.UTF_16BE).trim()
                : new String(use, 40, 32, StandardCharsets.US_ASCII).trim();
        v.root = new UdfReader.Node();
        v.root.name = "";
        v.root.dir = true;
        long lba = UdfReader.u32(use, 156 + 2);
        long len = UdfReader.u32(use, 156 + 10);
        readDir(v.root, lba, len, 0);
        return v;
    }

    private void readDir(UdfReader.Node dir, long lba, long len, int depth) throws IOException {
        if (depth > 32 || !visited.add(lba)) throw new IOException("Bozuk ISO dizin yapısı");
        if (len > 16L * 1024 * 1024) throw new IOException("ISO dizini çok büyük");
        UdfReader.Node pending = null;
        for (long s = 0; s * S < len; s++) {
            byte[] d = sector(lba + s);
            int p = 0;
            while (p < S) {
                int rl = d[p] & 0xFF;
                if (rl == 0) break;                       // sektörün geri kalanı boş
                if (p + rl > S || rl < 34) throw new IOException("Bozuk ISO dizin kaydı");
                long ext = UdfReader.u32(d, p + 2);
                long size = UdfReader.u32(d, p + 10);
                int flags = d[p + 25] & 0xFF;
                int nl = d[p + 32] & 0xFF;
                if (33 + nl > rl) throw new IOException("Bozuk ISO dizin kaydı");
                boolean special = nl == 1 && (d[p + 33] == 0 || d[p + 33] == 1);
                if (!special) {
                    String name = joliet
                            ? new String(d, p + 33, nl & ~1, StandardCharsets.UTF_16BE)
                            : new String(d, p + 33, nl, StandardCharsets.ISO_8859_1);
                    int semi = name.indexOf(';');
                    if (semi >= 0) name = name.substring(0, semi);
                    if (!joliet && name.endsWith(".")) name = name.substring(0, name.length() - 1);
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")
                            || name.equals("..")) {
                        throw new IOException("ISO'da geçersiz dosya adı");
                    }
                    boolean isDir = (flags & 0x02) != 0;
                    if (++nodes > MAX_NODES) throw new IOException("ISO'da çok fazla dosya var");
                    if (pending != null && pending.name.equals(name) && !isDir) {
                        // çok parçalı dosyanın devamı
                        pending.extents.add(new long[]{ext * S, size});
                        pending.size += size;
                    } else {
                        UdfReader.Node n = new UdfReader.Node();
                        n.name = name;
                        n.dir = isDir;
                        if (isDir) {
                            readDir(n, ext, size, depth + 1);
                        } else {
                            n.size = size;
                            n.extents.add(new long[]{ext * S, size});
                        }
                        dir.kids.add(n);
                        pending = isDir ? null : n;
                    }
                    if ((flags & 0x80) == 0) pending = null;
                }
                p += rl;
            }
        }
    }
}

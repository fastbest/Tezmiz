package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ISO dosyasındaki UDF dosya sistemini okur (Windows ISO'ları UDF 1.02 kullanır). */
final class UdfReader {

    static final int S = 2048;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 200000;

    static final class Node {
        String name;
        boolean dir;
        long size;
        byte[] embedded;
        /** {görüntüdeki bayt konumu (-1 = boş/sıfır), uzunluk} */
        final List<long[]> extents = new ArrayList<>();
        final List<Node> kids = new ArrayList<>();

        Node child(String n) {
            for (Node k : kids) {
                if (k.name.equalsIgnoreCase(n)) return k;
            }
            return null;
        }

        Node path(String... parts) {
            Node cur = this;
            for (String p : parts) {
                if (cur == null) return null;
                cur = cur.child(p);
            }
            return cur;
        }
    }

    static final class Volume {
        Node root;
        String label;
    }

    /** Bir UDF dosyasının içeriğini rastgele okunabilir hale getirir. */
    static final class FileView implements RandomSource {
        private final RandomSource img;
        private final Node node;

        FileView(RandomSource img, Node node) {
            this.img = img;
            this.node = node;
        }

        @Override
        public long size() {
            return node.size;
        }

        @Override
        public void readFully(long pos, byte[] b, int off, int len) throws IOException {
            if (pos < 0 || pos + len > node.size) throw new IOException("Dosya sınırı aşıldı");
            if (node.embedded != null) {
                System.arraycopy(node.embedded, (int) pos, b, off, len);
                return;
            }
            long base = 0;
            for (long[] e : node.extents) {
                if (len == 0) return;
                long end = base + e[1];
                if (pos < end) {
                    int n = (int) Math.min(len, end - pos);
                    if (e[0] < 0) {
                        java.util.Arrays.fill(b, off, off + n, (byte) 0);
                    } else {
                        img.readFully(e[0] + (pos - base), b, off, n);
                    }
                    pos += n;
                    off += n;
                    len -= n;
                }
                base = end;
            }
            if (len > 0) throw new IOException("Dosya verisi eksik");
        }
    }

    private final RandomSource src;
    private long partStart;
    private int nodes;
    private final Set<Long> visited = new HashSet<>();

    private UdfReader(RandomSource src) {
        this.src = src;
    }

    static Volume read(RandomSource src) throws IOException {
        return new UdfReader(src).run();
    }

    private byte[] sector(long n) throws IOException {
        byte[] b = new byte[S];
        if ((n + 1) * S > src.size()) throw new IOException("ISO dosyası eksik ya da bozuk");
        src.readFully(n * S, b, 0, S);
        return b;
    }

    private byte[] block(long lbn) throws IOException {
        return sector(partStart + lbn);
    }

    private Volume run() throws IOException {
        long last = src.size() / S - 1;
        byte[] avdp = null;
        for (long cand : new long[]{256, last - 256, last}) {
            if (cand < 0 || cand > last) continue;
            byte[] b = sector(cand);
            if (u16(b, 0) == 2) {
                avdp = b;
                break;
            }
        }
        if (avdp == null) {
            throw new IOException("Bu ISO'da UDF dosya sistemi yok (Windows ISO'su değil)");
        }
        long vdsLen = u32(avdp, 16);
        long vdsLoc = u32(avdp, 20);

        Volume vol = new Volume();
        int mapPartition = -1;
        long fsdLbn = -1;
        List<long[]> partitions = new ArrayList<>(); // {numara, başlangıç}
        for (long i = 0; i < Math.min(vdsLen / S, 64); i++) {
            byte[] d = sector(vdsLoc + i);
            int tag = u16(d, 0);
            if (tag == 5) {
                partitions.add(new long[]{u16(d, 22), u32(d, 188)});
            } else if (tag == 6) {
                if (u32(d, 212) != S) throw new IOException("Desteklenmeyen UDF blok boyutu");
                vol.label = dstring(d, 84, d[84 + 127] & 0xFF);
                fsdLbn = u32(d, 252);
                long maps = u32(d, 268);
                if (maps < 1 || (d[440] & 0xFF) != 1) {
                    throw new IOException("Desteklenmeyen UDF sürümü");
                }
                mapPartition = u16(d, 444);
            } else if (tag == 8) {
                break;
            }
        }
        boolean found = false;
        for (long[] p : partitions) {
            if (p[0] == mapPartition || !found) {
                partStart = p[1];
                found = true;
                if (p[0] == mapPartition) break;
            }
        }
        if (!found || fsdLbn < 0) throw new IOException("UDF bölümü bulunamadı");

        byte[] fsd = block(fsdLbn);
        if (u16(fsd, 0) != 256) throw new IOException("UDF dosya kümesi bulunamadı");
        vol.root = readNode(u32(fsd, 404), "", 0);
        if (!vol.root.dir) throw new IOException("UDF kök dizini bozuk");
        return vol;
    }

    private Node readNode(long lbn, String name, int depth) throws IOException {
        if (++nodes > MAX_NODES) throw new IOException("ISO'da çok fazla dosya var");
        byte[] e = block(lbn);
        int tag = u16(e, 0);
        Node n = new Node();
        n.name = name;
        int lea;
        int lad;
        int adOff;
        if (tag == 261) {
            lea = (int) u32(e, 168);
            lad = (int) u32(e, 172);
            adOff = 176 + lea;
        } else if (tag == 266) {
            lea = (int) u32(e, 208);
            lad = (int) u32(e, 212);
            adOff = 216 + lea;
        } else {
            throw new IOException("Bozuk UDF dosya girişi: " + name);
        }
        int fileType = e[27] & 0xFF;
        int allocType = u16(e, 34) & 7;
        n.dir = fileType == 4;
        n.size = u64(e, 56);
        if (lea < 0 || lad < 0 || adOff + lad > S) {
            throw new IOException("Bozuk UDF dosya girişi: " + name);
        }

        if (allocType == 3) {
            if (n.size > lad) throw new IOException("Bozuk UDF dosya girişi: " + name);
            n.embedded = new byte[(int) n.size];
            System.arraycopy(e, adOff, n.embedded, 0, (int) n.size);
        } else if (allocType == 0 || allocType == 1) {
            int adSize = allocType == 0 ? 8 : 16;
            byte[] ads = e;
            int pos = adOff;
            int end = adOff + lad;
            long covered = 0;
            int hops = 0;
            while (pos + adSize <= end && covered < n.size) {
                long raw = u32(ads, pos);
                long len = raw & 0x3FFFFFFFL;
                int type = (int) (raw >>> 30);
                long at = u32(ads, pos + 4);
                if (len == 0) break;
                if (type == 3) { // sonraki tanımlayıcı bloğu
                    if (++hops > 1000) throw new IOException("Bozuk UDF: " + name);
                    ads = block(at);
                    if (u16(ads, 0) != 258) throw new IOException("Bozuk UDF: " + name);
                    pos = 24;
                    end = (int) Math.min(S, 24 + u32(ads, 20));
                    continue;
                }
                long take = Math.min(len, n.size - covered);
                n.extents.add(new long[]{type == 0 ? (partStart + at) * S : -1, take});
                covered += take;
                pos += adSize;
            }
            if (covered < n.size) throw new IOException("UDF dosyası eksik: " + name);
        } else {
            throw new IOException("Desteklenmeyen UDF tanımlayıcısı: " + name);
        }

        if (n.dir) {
            if (depth > MAX_DEPTH || !visited.add(lbn)) {
                throw new IOException("Bozuk UDF dizin yapısı");
            }
            if (n.size > 16L * 1024 * 1024) throw new IOException("UDF dizini çok büyük");
            byte[] d = new byte[(int) n.size];
            new FileView(src, n).readFully(0, d, 0, d.length);
            parseDir(n, d, depth);
        }
        return n;
    }

    private void parseDir(Node dir, byte[] d, int depth) throws IOException {
        int p = 0;
        while (p + 38 <= d.length) {
            if (u16(d, p) != 257) break;
            int chars = d[p + 18] & 0xFF;
            int lfi = d[p + 19] & 0xFF;
            long icb = u32(d, p + 24);
            int liu = u16(d, p + 36);
            int nameOff = p + 38 + liu;
            if (nameOff + lfi > d.length) break;
            if ((chars & 0x0C) == 0 && lfi > 0) {
                String name = dstring(d, nameOff, lfi);
                if (name.isEmpty() || name.contains("/") || name.contains("\\")
                        || name.equals(".") || name.equals("..")) {
                    throw new IOException("ISO'da geçersiz dosya adı");
                }
                dir.kids.add(readNode(icb, name, depth + 1));
            }
            p += (38 + liu + lfi + 3) & ~3;
        }
    }

    /** OSTA sıkıştırılmış Unicode. */
    static String dstring(byte[] b, int off, int len) {
        if (len <= 1) return "";
        int comp = b[off] & 0xFF;
        if (comp == 8) {
            return new String(b, off + 1, len - 1, StandardCharsets.ISO_8859_1);
        }
        if (comp == 16) {
            return new String(b, off + 1, (len - 1) & ~1, StandardCharsets.UTF_16BE);
        }
        return "";
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static long u32(byte[] b, int o) {
        return (u16(b, o) & 0xFFFFL) | ((long) u16(b, o + 2) << 16);
    }

    static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }
}

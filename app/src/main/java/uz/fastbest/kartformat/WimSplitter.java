package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static uz.fastbest.kartformat.DiskFormatter.putInt;
import static uz.fastbest.kartformat.DiskFormatter.putLong;

/**
 * install.wim dosyasını FAT32'ye sığan install.swm, install2.swm ... parçalarına böler
 * ("DISM /Split-Image" ile aynı biçim). Kaynaklar açılmadan, olduğu gibi kopyalanır.
 */
final class WimSplitter {

    static final int HDR = 208;
    private static final int ENTRY = 50;
    private static final int RES_METADATA = 0x02;
    private static final int RES_COMPRESSED = 0x04;
    private static final int RES_SOLID = 0x10;
    private static final int HDR_SPANNED = 0x08;

    private static final class Entry implements Comparable<Entry> {
        byte[] raw;
        long size;
        int flags;
        long offset;
        long newOffset;

        boolean metadata() {
            return (flags & RES_METADATA) != 0;
        }

        @Override
        public int compareTo(Entry o) {
            return Long.compare(offset, o.offset);
        }
    }

    private WimSplitter() {
    }

    /** WIM dosyasını inceler ve parçalarını hazırlar; hiçbir şey yazmaz. */
    static List<Content> plan(final RandomSource wim, long limit) throws IOException {
        if (wim.size() < HDR) throw new IOException("install.wim bozuk");
        final byte[] h = new byte[HDR];
        wim.readFully(0, h, 0, HDR);
        String magic = new String(h, 0, 8, StandardCharsets.ISO_8859_1);
        if (!magic.equals("MSWIM\0\0\0")) throw new IOException("install.wim tanınmadı");
        if (UdfReader.u32(h, 8) != HDR) throw new IOException("install.wim sürümü desteklenmiyor");
        if (UdfReader.u16(h, 42) != 1) throw new IOException("install.wim zaten bölünmüş");

        long tSize = reshdrSize(h, 48);
        int tFlags = h[48 + 7] & 0xFF;
        long tOff = UdfReader.u64(h, 56);
        if ((tFlags & RES_COMPRESSED) != 0 || tSize % ENTRY != 0 || tSize > 64L * 1024 * 1024
                || tOff + tSize > wim.size()) {
            throw new IOException("install.wim kaynak tablosu okunamadı");
        }
        byte[] table = new byte[(int) tSize];
        wim.readFully(tOff, table, 0, table.length);

        long xSize = reshdrSize(h, 72);
        long xOff = UdfReader.u64(h, 80);
        if (xSize > 64L * 1024 * 1024 || xOff + xSize > wim.size()) {
            throw new IOException("install.wim XML verisi okunamadı");
        }
        final byte[] xml = new byte[(int) xSize];
        wim.readFully(xOff, xml, 0, xml.length);
        final long bootOff = reshdrSize(h, 96) > 0 ? UdfReader.u64(h, 104) : -1;

        List<Entry> meta = new ArrayList<>();
        List<Entry> blobs = new ArrayList<>();
        for (int i = 0; i < table.length; i += ENTRY) {
            Entry e = new Entry();
            e.raw = new byte[ENTRY];
            System.arraycopy(table, i, e.raw, 0, ENTRY);
            e.size = reshdrSize(e.raw, 0);
            e.flags = e.raw[7] & 0xFF;
            e.offset = UdfReader.u64(e.raw, 8);
            if ((e.flags & RES_SOLID) != 0) {
                throw new IOException("Bu install.wim \"solid\" sıkıştırma kullanıyor, bölünemiyor");
            }
            if (e.offset + e.size > wim.size()) throw new IOException("install.wim bozuk");
            (e.metadata() ? meta : blobs).add(e);
        }
        Collections.sort(meta);
        Collections.sort(blobs);

        // Açgözlü gruplama: meta veriler hep 1. parçada
        final List<List<Entry>> parts = new ArrayList<>();
        List<Entry> cur = new ArrayList<>();
        long curSize = HDR + xml.length;
        List<Entry> all = new ArrayList<>(meta);
        all.addAll(blobs);
        for (Entry e : all) {
            long grown = curSize + e.size + ENTRY;
            if (grown > limit) {
                if (e.metadata() || cur.isEmpty()) {
                    throw new IOException("install.wim içindeki bir parça FAT32 sınırından büyük");
                }
                parts.add(cur);
                cur = new ArrayList<>();
                curSize = HDR + xml.length;
                grown = curSize + e.size + ENTRY;
                if (grown > limit) {
                    throw new IOException("install.wim içindeki bir parça FAT32 sınırından büyük");
                }
            }
            cur.add(e);
            curSize = grown;
        }
        parts.add(cur);

        final int total = parts.size();
        List<Content> out = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final List<Entry> list = parts.get(i);
            final int number = i + 1;
            long pos = HDR;
            for (Entry e : list) {
                e.newOffset = pos;
                pos += e.size;
            }
            final long tableOff = pos;
            pos += (long) list.size() * ENTRY;
            final long xmlOff = pos;
            pos += xml.length;
            final long size = pos;

            final byte[] header = h.clone();
            putInt(header, 16, (int) (UdfReader.u32(h, 16) | HDR_SPANNED));
            header[40] = (byte) number;
            header[41] = (byte) (number >>> 8);
            header[42] = (byte) total;
            header[43] = (byte) (total >>> 8);
            long tLen = (long) list.size() * ENTRY;
            putReshdr(header, 48, tLen, tFlags, tableOff, tLen);
            putReshdr(header, 72, xml.length, h[72 + 7] & 0xFF, xmlOff, UdfReader.u64(h, 88));
            java.util.Arrays.fill(header, 96, 120, (byte) 0);
            if (number == 1 && bootOff >= 0) {
                for (Entry e : list) {
                    if (e.offset == bootOff) {
                        System.arraycopy(h, 96, header, 96, 24);
                        putLong(header, 104, e.newOffset);
                        break;
                    }
                }
            }
            java.util.Arrays.fill(header, 124, HDR, (byte) 0); // bütünlük tablosu yok

            final byte[] newTable = new byte[(int) tLen];
            for (int k = 0; k < list.size(); k++) {
                Entry e = list.get(k);
                System.arraycopy(e.raw, 0, newTable, k * ENTRY, ENTRY);
                putLong(newTable, k * ENTRY + 8, e.newOffset);
                newTable[k * ENTRY + 24] = (byte) number;
                newTable[k * ENTRY + 25] = (byte) (number >>> 8);
            }

            out.add(new Content() {
                @Override
                public long size() {
                    return size;
                }

                @Override
                public void writeTo(Sink sink) throws IOException {
                    sink.write(header, 0, HDR);
                    for (Entry e : list) Content.copy(wim, e.offset, e.size, sink);
                    sink.write(newTable, 0, newTable.length);
                    sink.write(xml, 0, xml.length);
                }
            });
        }
        return out;
    }

    private static long reshdrSize(byte[] b, int o) {
        return UdfReader.u64(b, o) & 0x00FFFFFFFFFFFFFFL;
    }

    private static void putReshdr(byte[] b, int o, long size, int flags, long offset, long orig) {
        putLong(b, o, size | ((long) flags << 56));
        putLong(b, o + 8, offset);
        putLong(b, o + 16, orig);
    }
}

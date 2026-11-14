package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Karttaki bölüm tablosunu ve dosya sistemlerini okuyup metin olarak anlatır. */
public final class DiskInfo {

    private DiskInfo() {
    }

    public static String describe(BlockDevice dev) throws IOException {
        int bs = dev.blockSize();
        StringBuilder sb = new StringBuilder();
        sb.append("Boyut: ").append(DiskOps.sizeText(dev.blockCount() * bs))
                .append(" (").append(dev.blockCount()).append(" sektör × ").append(bs)
                .append(" bayt)\n");

        byte[] s0 = new byte[bs];
        dev.read(0, s0, 1);
        boolean sig = (s0[510] & 0xFF) == 0x55 && (s0[511] & 0xFF) == 0xAA;

        String whole = sig ? bootSectorFs(s0) : null;
        if (whole != null) {
            sb.append("Bölüm tablosu: yok (kartın tamamı tek dosya sistemi)\n");
            sb.append("Dosya sistemi: ").append(whole).append('\n');
            return sb.toString();
        }
        if (!sig) {
            sb.append("Bölüm tablosu: yok (kart boş ya da tanınmayan biçimde)\n");
            return sb.toString();
        }

        if ((s0[450] & 0xFF) == 0xEE) {
            sb.append("Bölüm tablosu: GPT\n");
            describeGpt(dev, sb);
            return sb.toString();
        }

        sb.append("Bölüm tablosu: MBR\n");
        int found = 0;
        for (int i = 0; i < 4; i++) {
            int e = 446 + i * 16;
            int type = s0[e + 4] & 0xFF;
            long start = le32(s0, e + 8);
            long size = le32(s0, e + 12);
            if (type == 0 || size == 0) continue;
            found++;
            sb.append("Bölüm ").append(i + 1).append(": ")
                    .append(DiskOps.sizeText(size * bs)).append(", ");
            if (type == 0x05 || type == 0x0F) {
                sb.append("genişletilmiş bölüm\n");
            } else {
                sb.append(detectFs(dev, start)).append('\n');
            }
        }
        if (found == 0) sb.append("Bölüm yok\n");
        return sb.toString();
    }

    private static void describeGpt(BlockDevice dev, StringBuilder sb) throws IOException {
        int bs = dev.blockSize();
        byte[] h = new byte[bs];
        dev.read(1, h, 1);
        if (!"EFI PART".equals(new String(h, 0, 8, StandardCharsets.US_ASCII))) {
            sb.append("GPT başlığı bozuk\n");
            return;
        }
        long entryLba = le64(h, 72);
        int entries = (int) Math.min(le32(h, 80), 128);
        int entrySize = (int) le32(h, 84);
        if (entrySize < 128 || entrySize > bs || entryLba <= 1) {
            sb.append("GPT başlığı bozuk\n");
            return;
        }
        int perSector = bs / entrySize;
        byte[] sec = new byte[bs];
        int found = 0;
        for (int i = 0; i < entries; i++) {
            if (i % perSector == 0) dev.read(entryLba + i / perSector, sec, 1);
            int off = (i % perSector) * entrySize;
            boolean empty = true;
            for (int k = 0; k < 16; k++) {
                if (sec[off + k] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) continue;
            long first = le64(sec, off + 32);
            long last = le64(sec, off + 40);
            if (first <= 0 || last < first || last >= dev.blockCount()) continue;
            found++;
            sb.append("Bölüm ").append(found).append(": ")
                    .append(DiskOps.sizeText((last - first + 1) * bs)).append(", ")
                    .append(detectFs(dev, first)).append('\n');
        }
        if (found == 0) sb.append("Bölüm yok\n");
    }

    private static String detectFs(BlockDevice dev, long start) throws IOException {
        int bs = dev.blockSize();
        int blocks = Math.max(1, 4096 / bs);
        if (start < 0 || start + blocks > dev.blockCount()) return "geçersiz bölüm";
        byte[] b = new byte[blocks * bs];
        dev.read(start, b, blocks);
        String fs = bootSectorFs(b);
        if (fs != null) return fs;
        if (b.length >= 1082 && (b[1080] & 0xFF) == 0x53 && (b[1081] & 0xFF) == 0xEF) {
            return "ext2/3/4 (Linux)";
        }
        if (b.length >= 1028 && le32(b, 1024) == 0xF2F52010L) return "F2FS";
        return "bilinmeyen dosya sistemi";
    }

    /** Önyükleme sektöründen dosya sistemini tanır; tanıyamazsa null döner. */
    private static String bootSectorFs(byte[] b) {
        String oem = new String(b, 3, 8, StandardCharsets.US_ASCII);
        if (oem.equals("NTFS    ")) return "NTFS";
        if (oem.equals("EXFAT   ")) return "exFAT";
        if (new String(b, 82, 5, StandardCharsets.US_ASCII).equals("FAT32")) return "FAT32";
        String f = new String(b, 54, 5, StandardCharsets.US_ASCII);
        if (f.equals("FAT16")) return "FAT16";
        if (f.equals("FAT12")) return "FAT12";
        return null;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int off) {
        return le32(b, off) | (le32(b, off + 4) << 32);
    }
}

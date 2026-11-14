package uz.fastbest.kartformat;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Eski BIOS'lu bilgisayarlarda açılış için MBR ve FAT32 açılış kodları (Rufus / ms-sys) ve
 * FreeDOS dosyaları. Derleme sırasında indirilir; yoksa ilgili özellik kapalıdır.
 */
final class BootCode {

    static byte[] mbr;          // Windows 7 MBR kodu (ilk 440 bayt kullanılır)
    static byte[] jump;         // FAT32 açılış sektörünün ilk baytları
    static byte[] nt52;
    static byte[] nt3f0;
    static byte[] nt1800;
    static byte[] fd52;
    static byte[] fd3f0;
    static byte[] kernelSys;
    static byte[] commandCom;

    private BootCode() {
    }

    static void load(AssetManager assets) {
        mbr = read(assets, "mbr_win7.bin", 440, 512);
        jump = read(assets, "br_fat32_0x0.bin", 3, 11);
        // 0x52'den 0x3E8'e (FSInfo boş küme sayacına) ve 0x3F0'dan 0x600'e kadar
        nt52 = read(assets, "br_fat32nt_0x52.bin", 8, 0x3E8 - 0x52);
        nt3f0 = read(assets, "br_fat32nt_0x3f0.bin", 2, 0x600 - 0x3F0);
        nt1800 = read(assets, "br_fat32nt_0x1800.bin", 16, 512);
        fd52 = read(assets, "br_fat32fd_0x52.bin", 8, 0x3E8 - 0x52);
        fd3f0 = read(assets, "br_fat32fd_0x3f0.bin", 2, 0x600 - 0x3F0);
        kernelSys = read(assets, "KERNEL.SYS", 1000, 4 * 1024 * 1024);
        commandCom = read(assets, "COMMAND.COM", 1000, 4 * 1024 * 1024);
    }

    static boolean windowsBios() {
        return mbr != null && jump != null && nt52 != null && nt3f0 != null && nt1800 != null;
    }

    static boolean freeDos() {
        return mbr != null && jump != null && fd52 != null && fd3f0 != null && kernelSys != null
                && commandCom != null;
    }

    private static byte[] read(AssetManager assets, String name, int min, int max) {
        try (InputStream in = assets.open("boot/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) return null;
            }
            byte[] d = out.toByteArray();
            return d.length >= min ? d : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Kartın ilk (MBR) bölümünü BIOS ile açılır yapar: MBR kodu, etkin bölüm işareti ve FAT32
     * açılış kodu. freeDos=false ise Windows (bootmgr), true ise FreeDOS (KERNEL.SYS) yükler.
     */
    static void install(BlockDevice dev, boolean freeDos) throws IOException {
        byte[] s0 = new byte[512];
        dev.read(0, s0, 1);
        if ((s0[510] & 0xFF) != 0x55 || (s0[511] & 0xFF) != 0xAA || (s0[450] & 0xFF) == 0xEE) {
            throw new IOException("BIOS açılışı yalnızca MBR bölüm tablosunda yapılabilir");
        }
        long start = UdfReader.u32(s0, 454);
        System.arraycopy(mbr, 0, s0, 0, Math.min(440, mbr.length));
        for (int i = 0; i < 4; i++) s0[446 + i * 16] = 0;
        s0[446] = (byte) 0x80;                            // ilk bölüm etkin

        byte[] code52 = freeDos ? fd52 : nt52;
        byte[] code3f0 = freeDos ? fd3f0 : nt3f0;
        for (long copy : new long[]{0, 6}) {              // ana ve yedek açılış bölgesi
            byte[] b = new byte[1536];                    // sektör 0, 1 (FSInfo), 2
            dev.read(start + copy, b, 3);
            if (!new String(b, 82, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("FAT32")) {
                throw new IOException("Bölüm FAT32 değil");
            }
            byte[] keep = java.util.Arrays.copyOfRange(b, 0x3E4, 0x3F0); // FSInfo imza + sayaçlar
            byte[] lead = java.util.Arrays.copyOfRange(b, 0x200, 0x204);
            System.arraycopy(jump, 0, b, 0, Math.min(11, jump.length));   // atlama + OEM adı
            System.arraycopy(code52, 0, b, 0x52, Math.min(code52.length, 0x3E8 - 0x52));
            System.arraycopy(code3f0, 0, b, 0x3F0, Math.min(code3f0.length, 0x600 - 0x3F0));
            System.arraycopy(keep, 0, b, 0x3E4, keep.length);
            System.arraycopy(lead, 0, b, 0x200, 4);
            for (int sec = 0; sec < 3; sec++) {
                b[sec * 512 + 510] = 0x55;
                b[sec * 512 + 511] = (byte) 0xAA;
            }
            dev.write(start + copy, b, 3);
        }
        if (!freeDos) {
            byte[] s12 = new byte[512];
            System.arraycopy(nt1800, 0, s12, 0, Math.min(512, nt1800.length));
            dev.write(start + 12, s12, 1);
        }
        dev.write(0, s0, 1);
        dev.flush();
    }

    /** FAT32 kartı FreeDOS ile açılır yapar. */
    static String makeFreeDos(BlockDevice dev, String label, Fat32Formatter.Progress p)
            throws IOException {
        DiskFormatter.format(dev, DiskFormatter.Fs.FAT32, false,
                label == null || label.trim().isEmpty() ? "FREEDOS" : label, p);
        CardVolume vol = CardVolume.open(dev);
        vol.create(vol.root(), "KERNEL.SYS", new java.io.ByteArrayInputStream(kernelSys),
                kernelSys.length, null, null);
        vol.create(vol.root(), "COMMAND.COM", new java.io.ByteArrayInputStream(commandCom),
                commandCom.length, null, null);
        install(dev, true);
        return "Tamamlandı. FreeDOS açılış kartı hazır.\n\nBIOS güncelleme dosyalarını "
                + "\"Dosyalar\" ekranından karta ekleyebilirsiniz. Bilgisayarı karttan açmak için "
                + "açılış menüsünde USB'yi (UEFI olmayan seçeneği) seçin.";
    }
}

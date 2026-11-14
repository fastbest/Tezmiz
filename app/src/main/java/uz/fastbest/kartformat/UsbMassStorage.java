package uz.fastbest.kartformat;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * USB yığın depolama aygıtına (kart okuyucu, flaş bellek) root'suz ham erişim.
 * Android USB Host API üzerinden "Bulk-Only Transport" + SCSI komutları kullanır.
 */
public final class UsbMassStorage implements BlockDevice {

    private static final int TIMEOUT = 20000;
    private static final int MAX_TRANSFER = 16384;

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private int lun;
    private int tag = 1;
    private int blockSize;
    private long blockCount;
    private String name = "";
    private boolean closed;

    private UsbMassStorage(UsbDeviceConnection conn, UsbInterface iface,
                           UsbEndpoint epIn, UsbEndpoint epOut) {
        this.conn = conn;
        this.iface = iface;
        this.epIn = epIn;
        this.epOut = epOut;
    }

    public static UsbInterface findInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface f = device.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && f.getInterfaceSubclass() == 0x06
                    && f.getInterfaceProtocol() == 0x50) {
                return f;
            }
        }
        return null;
    }

    public static UsbMassStorage open(UsbManager manager, UsbDevice device) throws IOException {
        UsbInterface f = findInterface(device);
        if (f == null) throw new IOException("Bu aygıt bir depolama aygıtı değil");
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < f.getEndpointCount(); i++) {
            UsbEndpoint e = f.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) {
                if (in == null) in = e;
            } else if (out == null) {
                out = e;
            }
        }
        if (in == null || out == null) throw new IOException("USB uç noktaları bulunamadı");

        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) throw new IOException("Aygıt açılamadı");
        if (!c.claimInterface(f, true)) {
            c.close();
            throw new IOException("Aygıt başka bir uygulama tarafından kullanılıyor");
        }
        UsbMassStorage s = new UsbMassStorage(c, f, in, out);
        try {
            s.init();
        } catch (IOException e) {
            s.close();
            throw e;
        }
        return s;
    }

    private void init() throws IOException {
        int maxLun = 0;
        byte[] one = new byte[1];
        int r = conn.controlTransfer(0xA1, 0xFE, 0, iface.getId(), one, 1, 2000);
        if (r == 1 && one[0] > 0 && one[0] < 16) maxLun = one[0];

        boolean found = false;
        for (int attempt = 0; attempt < 6 && !found; attempt++) {
            for (int l = 0; l <= maxLun && !found; l++) {
                lun = l;
                if (testUnitReady()) found = true;
            }
            if (!found) sleep(500);
        }
        if (!found) throw new IOException("Okuyucuda kart yok ya da kart yanıt vermiyor");

        byte[] inq = new byte[36];
        byte[] cb = new byte[6];
        cb[0] = 0x12;
        cb[4] = 36;
        if (command(cb, inq, 36, true) == 0) {
            String vendor = new String(inq, 8, 8, StandardCharsets.US_ASCII).trim();
            String product = new String(inq, 16, 16, StandardCharsets.US_ASCII).trim();
            name = (vendor + " " + product).trim();
        }

        byte[] cap = new byte[8];
        cb = new byte[10];
        cb[0] = 0x25;
        if (command(cb, cap, 8, true) != 0) {
            throw new IOException("Kart boyutu okunamadı" + senseText());
        }
        long lastLba = be32(cap, 0);
        blockSize = (int) be32(cap, 4);
        if (lastLba == 0xFFFFFFFFL) throw new IOException("2 TB üstü kartlar desteklenmiyor");
        blockCount = lastLba + 1;
    }

    public String name() {
        return name;
    }

    /** USB tanımlayıcılarının özeti (sürüm, güç, uç noktalar). */
    public String describeUsb() {
        StringBuilder sb = new StringBuilder();
        byte[] raw = null;
        try {
            raw = conn.getRawDescriptors();
        } catch (RuntimeException ignored) {
        }
        if (raw != null && raw.length >= 18 && raw[1] == 1) {
            int bcd = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
            sb.append(String.format(java.util.Locale.ROOT, "USB sürümü: %x.%02x", bcd >> 8, bcd & 0xFF));
            if (bcd >= 0x300) sb.append(" (USB 3 aygıt; hız telefonun portuna bağlı)");
            sb.append('\n');
            int bcdDev = (raw[12] & 0xFF) | ((raw[13] & 0xFF) << 8);
            sb.append(String.format(java.util.Locale.ROOT, "Aygıt sürümü: %x.%02x\n",
                    bcdDev >> 8, bcdDev & 0xFF));
            int p = raw[0] & 0xFF;
            while (p + 2 <= raw.length) {
                int len = raw[p] & 0xFF;
                int type = raw[p + 1] & 0xFF;
                if (len < 2 || p + len > raw.length) break;
                if (type == 2 && len >= 9) {
                    sb.append("En çok güç: ").append((raw[p + 8] & 0xFF) * (bcd >= 0x300 ? 8 : 2))
                            .append(" mA\n");
                } else if (type == 4 && len >= 9) {
                    sb.append(String.format(java.util.Locale.ROOT,
                            "Arayüz %d: sınıf %02X/%02X/%02X\n", raw[p + 2] & 0xFF,
                            raw[p + 5] & 0xFF, raw[p + 6] & 0xFF, raw[p + 7] & 0xFF));
                } else if (type == 5 && len >= 7) {
                    int addr = raw[p + 2] & 0xFF;
                    int attr = raw[p + 3] & 3;
                    int max = (raw[p + 4] & 0xFF) | ((raw[p + 5] & 0x07) << 8);
                    String[] kinds = {"kontrol", "eşzamanlı", "toplu", "kesme"};
                    sb.append(String.format(java.util.Locale.ROOT,
                            "  Uç nokta %02X: %s, %s, paket %d bayt\n", addr,
                            (addr & 0x80) != 0 ? "giriş" : "çıkış", kinds[attr], max));
                }
                p += len;
            }
        }
        sb.append("Mantıksal birim (LUN): ").append(lun).append('\n');
        sb.append("Sektör boyutu: ").append(blockSize).append(" bayt\n");
        return sb.toString();
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public long blockCount() {
        return blockCount;
    }

    @Override
    public void read(long lba, byte[] buf, int blocks) throws IOException {
        byte[] cb = rw10(0x28, lba, blocks);
        if (command(cb, buf, blocks * blockSize, true) != 0) {
            throw new IOException("Okuma hatası" + senseText());
        }
    }

    @Override
    public void write(long lba, byte[] buf, int blocks) throws IOException {
        byte[] cb = rw10(0x2A, lba, blocks);
        if (command(cb, buf, blocks * blockSize, false) != 0) {
            throw new IOException("Yazma hatası" + senseText());
        }
    }

    @Override
    public void flush() {
        try {
            byte[] cb = new byte[10];
            cb[0] = 0x35; // SYNCHRONIZE CACHE; desteklemeyen okuyucular olabilir
            if (command(cb, null, 0, false) != 0) requestSense();
        } catch (IOException ignored) {
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            conn.releaseInterface(iface);
        } catch (RuntimeException ignored) {
        }
        conn.close();
    }

    private static byte[] rw10(int op, long lba, int blocks) {
        byte[] cb = new byte[10];
        cb[0] = (byte) op;
        cb[2] = (byte) (lba >>> 24);
        cb[3] = (byte) (lba >>> 16);
        cb[4] = (byte) (lba >>> 8);
        cb[5] = (byte) lba;
        cb[7] = (byte) (blocks >>> 8);
        cb[8] = (byte) blocks;
        return cb;
    }

    private boolean testUnitReady() {
        try {
            if (command(new byte[6], null, 0, false) == 0) return true;
            requestSense();
        } catch (IOException ignored) {
        }
        return false;
    }

    /** Son hatanın SCSI "sense" bilgisini okur; yoksa null döner. */
    private byte[] requestSense() throws IOException {
        byte[] sense = new byte[18];
        byte[] cb = new byte[6];
        cb[0] = 0x03;
        cb[4] = 18;
        return command(cb, sense, 18, true) == 0 ? sense : null;
    }

    private String senseText() {
        try {
            byte[] s = requestSense();
            if (s == null) return "";
            int key = s[2] & 0x0F;
            int asc = s[12] & 0xFF;
            if (key == 7) return ": kart yazmaya karşı korumalı";
            if (key == 2 && asc == 0x3A) return ": okuyucuda kart yok";
            if (key == 3) return ": kart bozuk olabilir (ortam hatası)";
            return String.format(" (kod %X/%02X)", key, asc);
        } catch (IOException e) {
            return "";
        }
    }

    /** Bir SCSI komutu yollar; CSW durum baytını döndürür (0 = başarılı). */
    private int command(byte[] cb, byte[] data, int len, boolean dirIn) throws IOException {
        if (closed) throw new IOException("Bağlantı kapalı");
        int myTag = tag++;
        byte[] cbw = new byte[31];
        le32(cbw, 0, 0x43425355);
        le32(cbw, 4, myTag);
        le32(cbw, 8, len);
        cbw[12] = (byte) (dirIn ? 0x80 : 0x00);
        cbw[13] = (byte) lun;
        cbw[14] = (byte) cb.length;
        System.arraycopy(cb, 0, cbw, 15, cb.length);
        if (conn.bulkTransfer(epOut, cbw, cbw.length, TIMEOUT) != cbw.length) {
            throw new IOException("USB bağlantısı koptu");
        }

        int pos = 0;
        while (pos < len) {
            int n = Math.min(MAX_TRANSFER, len - pos);
            int r = conn.bulkTransfer(dirIn ? epIn : epOut, data, pos, n, TIMEOUT);
            if (r < 0) {
                // Aygıt komutu reddetti; takılan uç noktayı açıp durumu oku
                clearHalt(dirIn ? epIn : epOut);
                break;
            }
            pos += r;
            if (dirIn && r < n) break; // kısa yanıt
        }

        byte[] csw = new byte[13];
        int r = conn.bulkTransfer(epIn, csw, csw.length, TIMEOUT);
        if (r < 0) {
            clearHalt(epIn);
            r = conn.bulkTransfer(epIn, csw, csw.length, TIMEOUT);
        }
        if (r != 13 || (int) lsb32(csw, 0) != 0x53425355 || (int) lsb32(csw, 4) != myTag) {
            throw new IOException("USB bağlantısı koptu ya da aygıt yanıt vermiyor");
        }
        int status = csw[12] & 0xFF;
        if (status == 2) throw new IOException("USB aygıt hatası; kartı çıkarıp yeniden takın");
        return status;
    }

    private void clearHalt(UsbEndpoint ep) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        conn.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, 2000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long be32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static long lsb32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}

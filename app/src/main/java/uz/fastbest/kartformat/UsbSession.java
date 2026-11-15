package uz.fastbest.kartformat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Gezginin USB kart bağlantısı (izin, açma, bölümler). */
final class UsbSession {

    static final String ACTION_PERMISSION = "uz.fastbest.kartformat.EXPLORER_USB_PERMISSION";

    private final Context ctx;
    private final UsbManager usb;
    private UsbMassStorage storage;
    private final List<CardFs> volumes = new ArrayList<>();
    private String error;
    private byte[] sector0;
    /** Her yeni kart açılışında artar; gezgin bunu düğüm anahtarlarına katar. */
    int generation;

    UsbSession(Context ctx) {
        this.ctx = ctx;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    UsbDevice device() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (UsbMassStorage.findInterface(d) != null) return d;
        }
        return null;
    }

    boolean isOpen() {
        return storage != null;
    }

    List<CardFs> volumes() {
        return volumes;
    }

    String error() {
        return error;
    }

    String readerName() {
        return storage == null || storage.name().isEmpty() ? "USB kart" : storage.name();
    }

    /** İzin yoksa ister ve false döner (izin gelince yayın alınır). */
    boolean ensurePermission() {
        UsbDevice d = device();
        if (d == null) return false;
        if (usb.hasPermission(d)) return true;
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent i = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 1, i, flags));
        return false;
    }

    /** İş parçacığında çağrılır: kartı ve bölümlerini açar. */
    void open() throws IOException {
        close();
        UsbDevice d = device();
        if (d == null) throw new IOException("Kart okuyucu bulunamadı");
        storage = UsbMassStorage.open(usb, d);
        generation++;
        sector0 = readSector0();
        try {
            List<CardVolume> vols = CardVolume.openAll(storage);
            for (CardVolume v : vols) {
                String title = vols.size() == 1 ? "USB kart" : "USB kart – " + v.name;
                volumes.add(new CardFs(v, title, null));
            }
            error = null;
        } catch (IOException e) {
            error = e.getMessage();
            // Kart açık kalır; araçlar (biçimlendirme) için okuyucu kullanılabilir
        }
    }

    private byte[] readSector0() {
        try {
            byte[] b = new byte[storage.blockSize()];
            storage.read(0, b, 1);
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * İş parçacığında çağrılır. Okuyucudaki kart değiştiyse kartı yeniden açar ve true döner.
     */
    synchronized boolean checkMedia() {
        if (storage == null) return false;
        boolean changed = storage.mediaChanged();
        if (!changed) {
            byte[] now = readSector0();
            changed = now == null || sector0 == null || !java.util.Arrays.equals(now, sector0);
        }
        if (!changed) return false;
        try {
            open();
        } catch (IOException e) {
            close();
            error = e.getMessage();
        }
        return true;
    }

    void close() {
        volumes.clear();
        if (storage != null) storage.close();
        storage = null;
    }
}

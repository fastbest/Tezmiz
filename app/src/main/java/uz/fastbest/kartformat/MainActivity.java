package uz.fastbest.kartformat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "uz.fastbest.kartformat.USB_PERMISSION";
    private static final int REQ_OPEN_IMAGE = 1;
    private static final int REQ_CREATE_BACKUP = 2;

    /** Uzun süren bir kart işlemi; sonuç metnini döndürür. */
    private interface Op {
        String run(UsbMassStorage s, Fat32Formatter.Progress p, DiskOps.Cancel c)
                throws IOException;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private UsbManager usb;
    private UsbMassStorage storage;
    private UsbDevice device;
    private boolean busy;
    private boolean rescanAfter;
    private boolean receiverRegistered;

    private TextView status;
    private TextView result;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button cancelButton;
    private Button refreshButton;
    private Button[] tools;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (!busy) scan();
                } else {
                    status.setText("USB izni verilmedi. \"Yenile\"ye basıp izin verin.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (busy) {
                    rescanAfter = true;
                } else {
                    scan();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        status = findViewById(R.id.status);
        result = findViewById(R.id.result);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        cancelButton = findViewById(R.id.cancelButton);
        refreshButton = findViewById(R.id.refreshButton);

        Button format = findViewById(R.id.formatButton);
        Button image = findViewById(R.id.imageButton);
        Button backup = findViewById(R.id.backupButton);
        Button wipe = findViewById(R.id.wipeButton);
        Button bench = findViewById(R.id.benchButton);
        Button info = findViewById(R.id.infoButton);
        tools = new Button[]{format, image, backup, wipe, bench, info};

        format.setOnClickListener(v -> askFormat());
        image.setOnClickListener(v -> pickImage());
        backup.setOnClickListener(v -> pickBackupTarget());
        wipe.setOnClickListener(v -> askWipe());
        bench.setOnClickListener(v -> runOp("Hız ölçülüyor", (s, p, c) -> DiskOps.benchmark(s, p, c)));
        info.setOnClickListener(v -> showInfo());
        cancelButton.setOnClickListener(v -> {
            cancelled.set(true);
            cancelButton.setEnabled(false);
        });
        refreshButton.setOnClickListener(v -> {
            if (!busy) scan();
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        scan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!busy) scan();
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        if (receiverRegistered) unregisterReceiver(receiver);
        final UsbMassStorage s = storage;
        storage = null;
        worker.execute(() -> {
            if (s != null) s.close();
        });
        worker.shutdown();
        super.onDestroy();
    }

    private void setToolsEnabled(boolean enabled) {
        for (Button b : tools) {
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void closeAsync(final UsbMassStorage s) {
        if (s == null || worker.isShutdown()) return;
        worker.execute(s::close);
    }

    /** Takılı kart okuyucuyu bulur, gerekirse izin ister ve kartı açar. */
    private void scan() {
        rescanAfter = false;
        setToolsEnabled(false);
        progressBar.setProgress(0);
        progressText.setText("");

        final UsbMassStorage old = storage;
        storage = null;
        device = null;

        UsbDevice found = null;
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (UsbMassStorage.findInterface(d) != null) {
                    found = d;
                    break;
                }
            }
        }
        if (found == null) {
            closeAsync(old);
            status.setText("Kart okuyucu bulunamadı.\n\nKartı OTG kart okuyucuya takıp "
                    + "okuyucuyu telefona bağlayın.");
            return;
        }
        if (!usb.hasPermission(found)) {
            closeAsync(old);
            status.setText("USB izni bekleniyor…");
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            usb.requestPermission(found, PendingIntent.getBroadcast(this, 0, intent, flags));
            return;
        }

        status.setText("Kart okunuyor…");
        busy = true;
        final UsbDevice target = found;
        worker.execute(() -> {
            if (old != null) old.close();
            try {
                final UsbMassStorage s = UsbMassStorage.open(usb, target);
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) {
                        closeAsync(s);
                        return;
                    }
                    storage = s;
                    device = target;
                    String name = s.name().isEmpty() ? "USB depolama" : s.name();
                    status.setText("Bulunan kart: " + name + "\nBoyut: "
                            + DiskOps.sizeText(s.blockCount() * s.blockSize()));
                    setToolsEnabled(true);
                    if (rescanAfter) scan();
                });
            } catch (final IOException e) {
                main.post(() -> {
                    busy = false;
                    status.setText("Hata: " + e.getMessage());
                    if (rescanAfter) scan();
                });
            }
        });
    }

    // ---------------------------------------------------------------- işlem çalıştırıcı

    private void runOp(final String title, final Op op) {
        final UsbMassStorage s = storage;
        if (s == null || busy) return;
        busy = true;
        cancelled.set(false);
        setToolsEnabled(false);
        refreshButton.setEnabled(false);
        cancelButton.setEnabled(true);
        cancelButton.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressText.setText("");
        result.setText(title + "… Kartı çıkarmayın, uygulamayı kapatmayın.");

        final Fat32Formatter.Progress progress = new Fat32Formatter.Progress() {
            private long last;

            @Override
            public void onProgress(final long done, final long total) {
                long now = SystemClock.elapsedRealtime();
                if (now - last < 150 && done != total) return;
                last = now;
                main.post(() -> showProgress(done, total));
            }
        };

        worker.execute(() -> {
            String message;
            try {
                message = op.run(s, progress, cancelled::get);
            } catch (DiskOps.CancelledException e) {
                message = "İptal edildi. Yazma işlemi yarıda kaldıysa kartı kullanmadan önce "
                        + "biçimlendirin.";
            } catch (IOException e) {
                message = "Hata: " + e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                message = "Beklenmeyen hata: " + e;
            }
            final String text = message;
            main.post(() -> finishOp(text));
        });
    }

    private void showProgress(long done, long total) {
        if (!busy) return;
        if (total > 0) {
            int pct = (int) Math.min(100, done * 100 / total);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(pct);
            progressText.setText("%" + pct + "  •  " + DiskOps.sizeText(done) + " / "
                    + DiskOps.sizeText(total));
        } else {
            progressBar.setIndeterminate(true);
            progressText.setText(DiskOps.sizeText(done));
        }
    }

    private void finishOp(String message) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cancelButton.setVisibility(View.GONE);
        refreshButton.setEnabled(true);
        progressBar.setIndeterminate(false);
        result.setText(message);
        if (rescanAfter) {
            scan();
        } else if (storage != null) {
            setToolsEnabled(true);
        }
    }

    private void setResultLater(final String text) {
        main.post(() -> {
            if (busy) result.setText(text);
        });
    }

    private String cardSize() {
        UsbMassStorage s = storage;
        return s == null ? "" : DiskOps.sizeText(s.blockCount() * s.blockSize());
    }

    // ---------------------------------------------------------------- biçimlendirme

    private void askFormat() {
        final UsbMassStorage card = storage;
        if (card == null || busy) return;
        long bytes = card.blockCount() * card.blockSize();
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        box.addView(caption("Dosya sistemi"));
        final RadioGroup fsGroup = new RadioGroup(this);
        final RadioButton fat32 = radio("FAT32 (her cihaz okur, tek dosya en çok 4 GB)");
        final RadioButton exfat = radio("exFAT (4 GB'tan büyük dosyalar, 64 GB+ kartlar)");
        fsGroup.addView(fat32);
        fsGroup.addView(exfat);
        fsGroup.check(bytes > 32L * 1000 * 1000 * 1000 ? exfat.getId() : fat32.getId());
        box.addView(fsGroup);

        box.addView(caption("Bölüm tablosu"));
        final RadioGroup tableGroup = new RadioGroup(this);
        final RadioButton mbr = radio("MBR (önerilen; telefonlar ve kameralar için)");
        final RadioButton gpt = radio("GPT (yeni bilgisayarlar için)");
        tableGroup.addView(mbr);
        tableGroup.addView(gpt);
        tableGroup.check(mbr.getId());
        box.addView(tableGroup);

        final EditText input = new EditText(this);
        input.setHint("Kart adı (isteğe bağlı)");
        input.setSingleLine(true);
        box.addView(input);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Biçimlendir  •  " + DiskOps.sizeText(bytes))
                .setView(scroll)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Devam", (d, w) -> confirmFormat(
                        exfat.isChecked() ? DiskFormatter.Fs.EXFAT : DiskFormatter.Fs.FAT32,
                        gpt.isChecked(), input.getText().toString()))
                .show();
    }

    private TextView caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private RadioButton radio(String text) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(text);
        return r;
    }

    private void confirmFormat(final DiskFormatter.Fs fs, final boolean gpt, final String label) {
        if (storage == null || busy) return;
        final String fsName = fs == DiskFormatter.Fs.EXFAT ? "exFAT" : "FAT32";
        new AlertDialog.Builder(this)
                .setTitle(fsName + " olarak biçimlendirilsin mi?")
                .setMessage("Karttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.\n\n"
                        + "Kart: " + cardSize() + "\nBölüm tablosu: " + (gpt ? "GPT" : "MBR"))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve biçimlendir", (d, w) ->
                        runOp("Biçimlendiriliyor", (s, p, c) -> {
                            final long bs = s.blockSize();
                            DiskFormatter.format(s, fs, gpt, label,
                                    (done, total) -> p.onProgress(done * bs, total * bs));
                            byte[] check = new byte[s.blockSize()];
                            s.read(0, check, 1);
                            if ((check[510] & 0xFF) != 0x55 || (check[511] & 0xFF) != 0xAA) {
                                throw new IOException("Yazılan veri doğrulanamadı; kart bozuk ya "
                                        + "da sahte olabilir");
                            }
                            return "Tamamlandı. Kart " + fsName + " olarak biçimlendirildi.\n\n"
                                    + "Okuyucuyu çıkarıp kartı telefonun kart yuvasına "
                                    + "takabilirsiniz.";
                        }))
                .show();
    }

    // ---------------------------------------------------------------- imaj yazma

    private void pickImage() {
        if (storage == null || busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_OPEN_IMAGE);
        } catch (RuntimeException e) {
            result.setText("Dosya seçici açılamadı.");
        }
    }

    private void pickBackupTarget() {
        if (storage == null || busy) return;
        String date = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gzip");
        intent.putExtra(Intent.EXTRA_TITLE, "kart-yedek-" + date + ".img.gz");
        try {
            startActivityForResult(intent, REQ_CREATE_BACKUP);
        } catch (RuntimeException e) {
            result.setText("Dosya kaydetme ekranı açılamadı.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN_IMAGE) {
            inspectImage(uri);
        } else if (requestCode == REQ_CREATE_BACKUP) {
            startBackup(uri);
        }
    }

    /** Seçilen dosyaya bakar (ad, boyut, sıkıştırma, önyükleme imzası) ve onay ister. */
    private void inspectImage(final Uri uri) {
        if (storage == null || busy) return;
        busy = true;
        setToolsEnabled(false);
        result.setText("Dosya inceleniyor…");
        final ContentResolver cr = getContentResolver();
        worker.execute(() -> {
            String name = "imaj";
            long size = -1;
            try (Cursor cur = cr.query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = cur.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                    if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
                }
            } catch (RuntimeException ignored) {
            }

            String error = null;
            boolean compressed = false;
            boolean bootable = false;
            try {
                byte[] magic = new byte[6];
                try (InputStream raw = open(cr, uri)) {
                    compressed = DiskOps.isCompressed(magic, DiskOps.readFully(raw, magic, 0, 6));
                }
                byte[] head = new byte[512];
                try (InputStream img = DiskOps.openImage(open(cr, uri))) {
                    bootable = DiskOps.looksBootable(head, DiskOps.readFully(img, head, 0, 512));
                }
            } catch (IOException | RuntimeException e) {
                error = "Dosya açılamadı: " + e.getMessage();
            }

            final String fName = name;
            final long fSize = size;
            final long total = compressed || size <= 0 ? -1 : size;
            final boolean fBootable = bootable;
            final String fError = error;
            main.post(() -> {
                busy = false;
                if (storage != null) setToolsEnabled(true);
                if (rescanAfter) {
                    scan();
                    return;
                }
                if (fError != null) {
                    result.setText(fError);
                    return;
                }
                result.setText("");
                confirmImage(uri, fName, fSize, total, fBootable);
            });
        });
    }

    private void confirmImage(final Uri uri, String name, long size, final long total,
                              boolean bootable) {
        if (storage == null || busy || isFinishing()) return;
        StringBuilder msg = new StringBuilder();
        msg.append("Dosya: ").append(name);
        if (size > 0) msg.append(" (").append(DiskOps.sizeText(size)).append(")");
        msg.append("\nKart: ").append(cardSize());
        msg.append("\n\nKarttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.");
        if (!bootable) {
            msg.append("\n\nUyarı: bu dosya doğrudan yazılmaya uygun bir disk imajına "
                    + "benzemiyor (örneğin Windows kurulum ISO'ları bu yöntemle çalışmaz). "
                    + "Yazılırsa bilgisayar bu karttan açılmayabilir.");
        }
        new AlertDialog.Builder(this)
                .setTitle("İmaj karta yazılsın mı?")
                .setMessage(msg.toString())
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve yaz", (d, w) -> startImageWrite(uri, total))
                .show();
    }

    private void startImageWrite(final Uri uri, final long total) {
        final ContentResolver cr = getContentResolver();
        runOp("İmaj yazılıyor", (s, p, c) -> {
            long written;
            try (InputStream in = DiskOps.openImage(open(cr, uri))) {
                written = DiskOps.writeImage(s, in, total, p, c);
            }
            setResultLater("Yazılan veri doğrulanıyor… Kartı çıkarmayın.");
            try (InputStream in = DiskOps.openImage(open(cr, uri))) {
                DiskOps.verifyImage(s, in, written, p, c);
            }
            return "Tamamlandı. " + DiskOps.sizeText(written)
                    + " yazıldı ve doğrulandı.\n\nOkuyucuyu çıkarabilirsiniz.";
        });
    }

    private static InputStream open(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("dosya okunamıyor");
        return in;
    }

    // ---------------------------------------------------------------- yedekleme

    private void startBackup(final Uri uri) {
        final ContentResolver cr = getContentResolver();
        if (storage == null || busy) {
            deleteQuietly(cr, uri);
            return;
        }
        runOp("Kart yedekleniyor", (s, p, c) -> {
            boolean ok = false;
            try {
                OutputStream raw = cr.openOutputStream(uri);
                if (raw == null) throw new IOException("Yedek dosyası oluşturulamadı");
                long bytes;
                try (GZIPOutputStream gz = new GZIPOutputStream(
                        new BufferedOutputStream(raw, 65536), 65536)) {
                    bytes = DiskOps.backup(s, gz, p, c);
                }
                ok = true;
                return "Yedek alındı (" + DiskOps.sizeText(bytes) + " kart, sıkıştırılmış "
                        + ".img.gz dosyası).\n\nGeri yüklemek için \"İmaj yaz\" ile bu dosyayı "
                        + "seçin.";
            } finally {
                if (!ok) deleteQuietly(cr, uri);
            }
        });
    }

    private static void deleteQuietly(ContentResolver cr, Uri uri) {
        try {
            DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- silme

    private void askWipe() {
        if (storage == null || busy) return;
        String[] items = {
                "Hızlı sil (birkaç saniye; bölüm tablosunu ve dosya sistemini siler)",
                "Tam sil (kartın tamamını sıfırla doldurur; uzun sürer)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Kartı sil")
                .setItems(items, (d, which) -> confirmWipe(which == 1))
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void confirmWipe(final boolean full) {
        if (storage == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle(full ? "Tam silme" : "Hızlı silme")
                .setMessage("Karttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.\n\n"
                        + "Kart: " + cardSize() + "\n\nSildikten sonra kartı kullanmak için "
                        + "biçimlendirmeniz gerekir.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> runOp("Kart siliniyor", (s, p, c) -> {
                    DiskOps.wipe(s, full, p, c);
                    return "Kart silindi. Kullanmak için \"Biçimlendir\"e basın.";
                }))
                .show();
    }

    // ---------------------------------------------------------------- bilgi

    private void showInfo() {
        final String usbInfo = usbInfo();
        runOp("Kart bilgisi okunuyor", (s, p, c) -> {
            String name = s.name().isEmpty() ? "USB depolama" : s.name();
            return "Kart okuyucu: " + name + "\n" + usbInfo + "\n" + DiskInfo.describe(s);
        });
    }

    private String usbInfo() {
        UsbDevice d = device;
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            if (d.getManufacturerName() != null) {
                sb.append("Üretici: ").append(d.getManufacturerName()).append('\n');
            }
            if (d.getProductName() != null) {
                sb.append("Ürün: ").append(d.getProductName()).append('\n');
            }
            sb.append(String.format(Locale.ROOT, "USB kimliği: %04X:%04X\n",
                    d.getVendorId(), d.getProductId()));
            String serial = d.getSerialNumber();
            if (serial != null) sb.append("Seri no: ").append(serial).append('\n');
        } catch (RuntimeException ignored) {
        }
        return sb.toString();
    }
}

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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "uz.fastbest.kartformat.USB_PERMISSION";
    private static final int REQ_OPEN_IMAGE = 1;
    private static final int REQ_CREATE_BACKUP = 2;
    private static final int REQ_OPEN_ISO = 3;
    private static final int REQ_FILES = 4;

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
        NtfsFormatter.tool = new java.io.File(getApplicationInfo().nativeLibraryDir, "libmkntfs.so");
        NtfsFormatter.workDir = getCacheDir();
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
        Button windows = findViewById(R.id.windowsButton);
        Button files = findViewById(R.id.filesButton);
        Button parts = findViewById(R.id.partsButton);
        Button ps2 = findViewById(R.id.ps2Button);
        tools = new Button[]{files, format, image, backup, wipe, bench, info, windows, parts, ps2};

        format.setOnClickListener(v -> askFormat());
        image.setOnClickListener(v -> pickImage());
        backup.setOnClickListener(v -> pickBackupTarget());
        wipe.setOnClickListener(v -> askWipe());
        bench.setOnClickListener(v -> runOp("Hız ölçülüyor", (s, p, c) -> DiskOps.benchmark(s, p, c)));
        info.setOnClickListener(v -> showInfo());
        windows.setOnClickListener(v -> pickIso());
        files.setOnClickListener(v -> openFiles());
        parts.setOnClickListener(v -> askPartitions());
        ps2.setOnClickListener(v -> pickPs2());
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

    private static final String[] CLUSTER_NAMES = {
            "Otomatik", "4 KB", "8 KB", "16 KB", "32 KB", "64 KB", "128 KB"};
    private static final int[] CLUSTER_BYTES = {0, 4096, 8192, 16384, 32768, 65536, 131072};

    private Spinner spinner(String[] items, int selected) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(selected);
        return sp;
    }

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
        final RadioButton ntfs = radio("NTFS (Windows bilgisayarlar için)");
        final RadioButton ext4 = radio("ext4 (Linux, Raspberry Pi, bazı Android cihazlar)");
        fsGroup.addView(fat32);
        fsGroup.addView(exfat);
        fsGroup.addView(ntfs);
        fsGroup.addView(ext4);
        fsGroup.check(bytes > 32L * 1000 * 1000 * 1000 ? exfat.getId() : fat32.getId());
        box.addView(fsGroup);

        box.addView(caption("Küme boyutu (FAT32 / exFAT / NTFS)"));
        final Spinner cluster = spinner(CLUSTER_NAMES, 0);
        box.addView(cluster);

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
                .setPositiveButton("Devam", (d, w) -> {
                    DiskFormatter.Fs fs = exfat.isChecked() ? DiskFormatter.Fs.EXFAT
                            : ntfs.isChecked() ? DiskFormatter.Fs.NTFS
                            : ext4.isChecked() ? DiskFormatter.Fs.EXT4 : DiskFormatter.Fs.FAT32;
                    int cb = fs == DiskFormatter.Fs.EXT4 ? 0
                            : CLUSTER_BYTES[cluster.getSelectedItemPosition()];
                    confirmFormat(fs, gpt.isChecked(), input.getText().toString(), cb);
                })
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

    private void confirmFormat(final DiskFormatter.Fs fs, final boolean gpt, final String label,
                               final int clusterBytes) {
        if (storage == null || busy) return;
        final String fsName = fs.title();
        new AlertDialog.Builder(this)
                .setTitle(fsName + " olarak biçimlendirilsin mi?")
                .setMessage("Karttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.\n\n"
                        + "Kart: " + cardSize() + "\nBölüm tablosu: " + (gpt ? "GPT" : "MBR")
                        + (clusterBytes > 0 ? "\nKüme: " + clusterBytes / 1024 + " KB" : ""))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve biçimlendir", (d, w) ->
                        runOp("Biçimlendiriliyor", (s, p, c) -> {
                            final long bs = s.blockSize();
                            DiskFormatter.format(s, fs, gpt, label, clusterBytes,
                                    (done, total) -> p.onProgress(done * bs, total * bs));
                            checkTable(s);
                            return "Tamamlandı. Kart " + fsName + " olarak biçimlendirildi.\n\n"
                                    + "Okuyucuyu çıkarıp kartı kullanabilirsiniz.";
                        }))
                .show();
    }

    private static void checkTable(UsbMassStorage s) throws IOException {
        byte[] check = new byte[s.blockSize()];
        s.read(0, check, 1);
        if ((check[510] & 0xFF) != 0x55 || (check[511] & 0xFF) != 0xAA) {
            throw new IOException("Yazılan veri doğrulanamadı; kart bozuk ya da sahte olabilir");
        }
    }

    // ---------------------------------------------------------------- bölüm sihirbazı

    private static final String[] PART_FS = {"FAT32", "exFAT", "NTFS", "ext4"};
    private static final DiskFormatter.Fs[] PART_FS_VALUES = {DiskFormatter.Fs.FAT32,
            DiskFormatter.Fs.EXFAT, DiskFormatter.Fs.NTFS, DiskFormatter.Fs.EXT4};

    private void askPartitions() {
        final UsbMassStorage card = storage;
        if (card == null || busy) return;
        final long bytes = card.blockCount() * card.blockSize();
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        box.addView(caption("Bölüm sayısı"));
        final RadioGroup count = new RadioGroup(this);
        count.setOrientation(LinearLayout.HORIZONTAL);
        final RadioButton[] counts = new RadioButton[4];
        for (int i = 0; i < 4; i++) {
            counts[i] = radio(String.valueOf(i + 1));
            count.addView(counts[i]);
        }
        box.addView(count);

        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows);
        final List<Spinner> fsViews = new ArrayList<>();
        final List<EditText> sizeViews = new ArrayList<>();

        count.setOnCheckedChangeListener((g, id) -> {
            int n = 1;
            for (int i = 0; i < 4; i++) if (counts[i].getId() == id) n = i + 1;
            rows.removeAllViews();
            fsViews.clear();
            sizeViews.clear();
            for (int i = 0; i < n; i++) {
                rows.addView(caption("Bölüm " + (i + 1)));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                Spinner fs = spinner(PART_FS, i == 0 ? 0 : 1);
                EditText size = new EditText(this);
                size.setSingleLine(true);
                size.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                if (i == n - 1) {
                    size.setText("");
                    size.setHint("kalan");
                    size.setEnabled(false);
                } else {
                    size.setHint("GB");
                    size.setText(String.valueOf(Math.max(1, bytes / 1000000000L / n)));
                }
                row.addView(fs, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                row.addView(size, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                rows.addView(row);
                fsViews.add(fs);
                sizeViews.add(size);
            }
        });
        count.check(counts[1].getId());

        box.addView(caption("Bölüm tablosu"));
        final RadioGroup tableGroup = new RadioGroup(this);
        final RadioButton mbr = radio("MBR");
        final RadioButton gpt = radio("GPT");
        tableGroup.addView(mbr);
        tableGroup.addView(gpt);
        tableGroup.check(mbr.getId());
        box.addView(tableGroup);

        TextView note = new TextView(this);
        note.setText("Not: telefonlar ve kameralar çoğunlukla yalnızca ilk bölümü görür.");
        note.setTextSize(13);
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Bölüm sihirbazı  •  " + DiskOps.sizeText(bytes))
                .setView(scroll)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Devam", (d, w) -> {
                    List<DiskFormatter.Part> parts = new ArrayList<>();
                    StringBuilder sum = new StringBuilder();
                    for (int i = 0; i < fsViews.size(); i++) {
                        DiskFormatter.Fs fs = PART_FS_VALUES[fsViews.get(i).getSelectedItemPosition()];
                        long sectors = 0;
                        boolean last = i == fsViews.size() - 1;
                        if (!last) {
                            double gb;
                            try {
                                gb = Double.parseDouble(sizeViews.get(i).getText().toString()
                                        .replace(',', '.'));
                            } catch (NumberFormatException e) {
                                gb = 0;
                            }
                            if (gb <= 0) {
                                result.setText("Bölüm " + (i + 1) + " için boyut girin.");
                                return;
                            }
                            sectors = (long) (gb * 1e9 / 512);
                        }
                        parts.add(new DiskFormatter.Part(fs, sectors, "BOLUM" + (i + 1), 0));
                        sum.append("Bölüm ").append(i + 1).append(": ").append(fs.title())
                                .append(", ").append(last ? "kalan alan"
                                        : DiskOps.sizeText(sectors * 512)).append('\n');
                    }
                    confirmPartitions(parts, gpt.isChecked(), sum.toString());
                })
                .show();
    }

    private void confirmPartitions(final List<DiskFormatter.Part> parts, final boolean gpt,
                                   String summary) {
        if (storage == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Bölümler oluşturulsun mu?")
                .setMessage(summary + "\nKarttaki BÜTÜN dosyalar silinecek ve geri "
                        + "getirilemeyecek.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve oluştur", (d, w) ->
                        runOp("Bölümler oluşturuluyor", (s, p, c) -> {
                            final long bs = s.blockSize();
                            DiskFormatter.partition(s, gpt, parts,
                                    (done, total) -> p.onProgress(done * bs, total * bs));
                            checkTable(s);
                            return "Tamamlandı. " + parts.size() + " bölüm oluşturuldu.\n\n"
                                    + DiskInfo.describe(s);
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
        if (requestCode == REQ_FILES) {
            filesClosed();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN_IMAGE) {
            inspectImage(uri);
        } else if (requestCode == REQ_CREATE_BACKUP) {
            startBackup(uri);
        } else if (requestCode == REQ_OPEN_ISO) {
            inspectIso(uri);
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

    // ---------------------------------------------------------------- PS2

    private void pickPs2() {
        result.setText("PS2 oyun ekleme hazırlanıyor.");
    }

    // ---------------------------------------------------------------- dosyalar

    private void openFiles() {
        if (storage == null || busy) return;
        busy = true;
        setToolsEnabled(false);
        FilesActivity.storage = storage;
        FilesActivity.worker = worker;
        try {
            startActivityForResult(new Intent(this, FilesActivity.class), REQ_FILES);
        } catch (RuntimeException e) {
            filesClosed();
            result.setText("Dosya ekranı açılamadı.");
        }
    }

    private void filesClosed() {
        FilesActivity.storage = null;
        FilesActivity.worker = null;
        busy = false;
        if (rescanAfter) {
            scan();
        } else if (storage != null) {
            setToolsEnabled(true);
        }
    }

    // ---------------------------------------------------------------- Windows USB

    private void pickIso() {
        if (storage == null || busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_OPEN_ISO);
        } catch (RuntimeException e) {
            result.setText("Dosya seçici açılamadı.");
        }
    }

    /** Açık bir ISO dosyası; kapatılınca dosya tanımlayıcısı da kapanır. */
    private static final class IsoFile implements java.io.Closeable {
        final ParcelFileDescriptor pfd;
        final FileInputStream in;
        final RandomSource source;

        IsoFile(ContentResolver cr, Uri uri) throws IOException {
            pfd = cr.openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("dosya açılamadı");
            in = new FileInputStream(pfd.getFileDescriptor());
            source = new RandomSource.Channel(in.getChannel());
            try {
                source.size();
            } catch (IOException e) {
                close();
                throw new IOException("Bu dosya doğrudan okunamıyor; ISO'yu önce telefon "
                        + "belleğine (İndirilenler) kopyalayın");
            }
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void inspectIso(final Uri uri) {
        if (storage == null || busy) return;
        busy = true;
        setToolsEnabled(false);
        progressBar.setIndeterminate(true);
        result.setText("ISO dosyası inceleniyor…");
        final ContentResolver cr = getContentResolver();
        worker.execute(() -> {
            WindowsUsb.Info info = null;
            String error = null;
            try (IsoFile iso = new IsoFile(cr, uri)) {
                info = WindowsUsb.inspect(iso.source);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                error = "ISO okunamadı: " + e;
            }
            final WindowsUsb.Info fInfo = info;
            final String fError = error;
            main.post(() -> {
                busy = false;
                progressBar.setIndeterminate(false);
                if (storage != null) setToolsEnabled(true);
                if (rescanAfter) {
                    scan();
                    return;
                }
                if (fError != null) {
                    result.setText("Hata: " + fError);
                    return;
                }
                result.setText("");
                askWindowsOptions(uri, fInfo);
            });
        });
    }

    private void askWindowsOptions(final Uri uri, final WindowsUsb.Info info) {
        if (storage == null || busy || isFinishing()) return;
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        StringBuilder about = new StringBuilder();
        about.append("ISO: ").append(info.label == null ? "Windows" : info.label)
                .append(" (").append(info.arch).append(")\n")
                .append("Dosyalar: ").append(DiskOps.sizeText(info.contentBytes))
                .append("\nKart: ").append(cardSize());
        if (info.split) {
            about.append("\n\ninstall.wim 4 GB'tan büyük; ").append(info.parts)
                    .append(" parçaya bölünecek.");
        }
        about.append("\n\nKart yalnızca UEFI ile açılır (2012 sonrası bilgisayarların çoğu). "
                + "Eski BIOS'lu bilgisayarlarda açılmaz.");
        TextView text = new TextView(this);
        text.setText(about);
        box.addView(text);

        final CheckBox bypass = new CheckBox(this);
        bypass.setText("Windows 11 denetimlerini atla (TPM, Güvenli Önyükleme, RAM)");
        bypass.setChecked(true);
        final CheckBox local = new CheckBox(this);
        local.setText("Microsoft hesabı zorunluluğunu atla (yerel hesap)");
        if (!info.hasUnattend) {
            box.addView(bypass);
            box.addView(local);
        }

        box.addView(caption("Bölüm tablosu"));
        final RadioGroup tableGroup = new RadioGroup(this);
        final RadioButton gpt = radio("GPT (Windows 11 ve yeni bilgisayarlar)");
        final RadioButton mbr = radio("MBR (açılmazsa bunu deneyin)");
        tableGroup.addView(gpt);
        tableGroup.addView(mbr);
        tableGroup.check(gpt.getId());
        box.addView(tableGroup);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Windows kurulum kartı")
                .setView(scroll)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Devam", (d, w) -> confirmWindows(uri, info, gpt.isChecked(),
                        !info.hasUnattend && bypass.isChecked(),
                        !info.hasUnattend && local.isChecked()))
                .show();
    }

    private void confirmWindows(final Uri uri, final WindowsUsb.Info info, final boolean gpt,
                                final boolean bypass, final boolean local) {
        if (storage == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle("Windows kartı hazırlansın mı?")
                .setMessage("Karttaki BÜTÜN dosyalar silinecek ve geri getirilemeyecek.\n\n"
                        + "Kart: " + cardSize() + "\n\nİşlem kartın hızına göre 10-40 dakika "
                        + "sürebilir. Telefonu şarja takmanız önerilir.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil ve hazırla", (d, w) -> {
                    final ContentResolver cr = getContentResolver();
                    runOp("Windows kartı hazırlanıyor", (s, p, c) -> {
                        try (IsoFile iso = new IsoFile(cr, uri)) {
                            WindowsUsb.Info fresh = WindowsUsb.inspect(iso.source);
                            final long bs = s.blockSize();
                            return WindowsUsb.write(s, iso.source, fresh, gpt, bypass, local,
                                    (done, total) -> p.onProgress(done * bs, total * bs), c);
                        }
                    });
                })
                .show();
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

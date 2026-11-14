package uz.fastbest.kartformat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Karttaki dosyalara göz atma, telefona kaydetme, karta ekleme, silme. */
public class FilesActivity extends Activity {

    /** Ana ekranın açtığı kart ve iş parçacığı (ikisi aynı anda kullanılmaz). */
    static UsbMassStorage storage;
    static ExecutorService worker;

    private static final int REQ_IMPORT = 1;
    private static final int REQ_EXPORT_FILE = 2;
    private static final int REQ_EXPORT_DIR = 3;

    private interface Job {
        String run(Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private CardVolume volume;
    private CardVolume.Entry current;
    private final List<CardVolume.Entry> items = new ArrayList<>();
    private CardVolume.Entry pending;
    private boolean busy;

    private TextView pathText;
    private TextView infoText;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button cancelButton;
    private Button upButton;
    private Button addButton;
    private Button folderButton;
    private ArrayAdapter<CardVolume.Entry> adapter;

    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cancelled.set(true);
            infoText.setText("Kart okuyucu çıkarıldı.");
            setResult(RESULT_OK);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        pathText = findViewById(R.id.pathText);
        infoText = findViewById(R.id.infoText);
        progressText = findViewById(R.id.filesProgressText);
        progressBar = findViewById(R.id.filesProgress);
        cancelButton = findViewById(R.id.filesCancel);
        upButton = findViewById(R.id.upButton);
        addButton = findViewById(R.id.addButton);
        folderButton = findViewById(R.id.folderButton);
        ListView list = findViewById(R.id.fileList);

        adapter = new ArrayAdapter<CardVolume.Entry>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                CardVolume.Entry e = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText((e.dir ? "📁  " : "📄  ") + e.name);
                t2.setText(e.dir ? "Klasör" : DiskOps.sizeText(e.size));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (busy) return;
            CardVolume.Entry e = items.get(pos);
            if (e.dir) open(e); else fileMenu(e);
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (busy) return true;
            CardVolume.Entry e = items.get(pos);
            if (e.dir) dirMenu(e); else fileMenu(e);
            return true;
        });
        upButton.setOnClickListener(v -> goUp());
        addButton.setOnClickListener(v -> pickImport());
        folderButton.setOnClickListener(v -> askFolderName());
        cancelButton.setOnClickListener(v -> {
            cancelled.set(true);
            cancelButton.setEnabled(false);
        });

        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(detach, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(detach, f);
        }

        if (storage == null || worker == null || worker.isShutdown()) {
            finish();
            return;
        }
        setButtons(false);
        infoText.setText("Kart açılıyor…");
        final UsbMassStorage s = storage;
        worker.execute(() -> {
            try {
                final CardVolume v = CardVolume.open(s);
                final long free = v.freeBytes();
                final List<CardVolume.Entry> kids = sorted(v.list(v.root()));
                main.post(() -> {
                    volume = v;
                    current = v.root();
                    showList(kids);
                    showInfo(free);
                });
            } catch (final IOException | RuntimeException e) {
                main.post(() -> infoText.setText("Hata: " + e.getMessage()));
            }
        });
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        try {
            unregisterReceiver(detach);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        if (current != null && current.parent != null) {
            goUp();
        } else {
            setResult(RESULT_OK);
            super.onBackPressed();
        }
    }

    private void setButtons(boolean on) {
        boolean write = on && volume != null && volume.writable();
        upButton.setEnabled(on && current != null && current.parent != null);
        addButton.setEnabled(write);
        folderButton.setEnabled(write);
        upButton.setAlpha(upButton.isEnabled() ? 1f : 0.5f);
        addButton.setAlpha(write ? 1f : 0.5f);
        folderButton.setAlpha(write ? 1f : 0.5f);
    }

    private static List<CardVolume.Entry> sorted(List<CardVolume.Entry> in) {
        List<CardVolume.Entry> out = new ArrayList<>(in);
        Collections.sort(out, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private void showList(List<CardVolume.Entry> kids) {
        items.clear();
        items.addAll(kids);
        adapter.notifyDataSetChanged();
        pathText.setText(current.path());
        setButtons(true);
        if (kids.isEmpty()) progressText.setText("Bu klasör boş.");
        else progressText.setText(kids.size() + " öğe");
    }

    private void showInfo(long free) {
        String s = volume.type() + "  •  " + DiskOps.sizeText(free) + " boş / "
                + DiskOps.sizeText(volume.totalBytes());
        if (!volume.writable()) s += "\nBu bölüm salt okunur açıldı.";
        infoText.setText(s);
    }

    private void open(final CardVolume.Entry dir) {
        run(null, (p, c) -> {
            final List<CardVolume.Entry> kids = sorted(volume.list(dir));
            main.post(() -> {
                current = dir;
                showList(kids);
            });
            return null;
        });
    }

    private void goUp() {
        if (current != null && current.parent != null) open(current.parent);
    }

    /** Arka planda iş yapar; bitince listeyi ve boş alanı yeniler. */
    private void run(final String title, final Job job) {
        if (busy || volume == null) return;
        busy = true;
        cancelled.set(false);
        setButtons(false);
        if (title != null) {
            progressText.setText(title + "…");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            cancelButton.setVisibility(View.VISIBLE);
            cancelButton.setEnabled(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        final Fat32Formatter.Progress progress = new Fat32Formatter.Progress() {
            private long last;

            @Override
            public void onProgress(final long done, final long total) {
                long now = SystemClock.elapsedRealtime();
                if (now - last < 150 && done != total) return;
                last = now;
                main.post(() -> {
                    if (total > 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgress((int) Math.min(100, done * 100 / total));
                    }
                });
            }
        };
        final CardVolume.Entry dir = current;
        worker.execute(() -> {
            String msg;
            try {
                msg = job.run(progress, cancelled::get);
            } catch (DiskOps.CancelledException e) {
                msg = "İptal edildi.";
            } catch (IOException e) {
                msg = "Hata: " + e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                msg = "Beklenmeyen hata: " + e;
            }
            List<CardVolume.Entry> kids = null;
            long free = -1;
            if (title != null) {
                try {
                    kids = sorted(volume.list(dir));
                    free = volume.freeBytes();
                } catch (IOException | RuntimeException ignored) {
                }
            }
            final String fMsg = msg;
            final List<CardVolume.Entry> fKids = kids;
            final long fFree = free;
            main.post(() -> {
                busy = false;
                progressBar.setVisibility(View.GONE);
                cancelButton.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing()) return;
                if (fKids != null && current == dir) showList(fKids); else setButtons(true);
                if (fFree >= 0) showInfo(fFree);
                if (fMsg != null) progressText.setText(fMsg);
            });
        });
    }

    private void setProgressLater(final String text) {
        main.post(() -> {
            if (busy) progressText.setText(text);
        });
    }

    // ---------------------------------------------------------------- menüler

    private void fileMenu(final CardVolume.Entry e) {
        boolean w = volume.writable();
        String[] opts = w ? new String[]{"Telefona kaydet", "Sil"} : new String[]{"Telefona kaydet"};
        new AlertDialog.Builder(this)
                .setTitle(e.name + "  (" + DiskOps.sizeText(e.size) + ")")
                .setItems(opts, (d, which) -> {
                    if (which == 0) exportFile(e); else confirmDelete(e);
                })
                .show();
    }

    private void dirMenu(final CardVolume.Entry e) {
        boolean w = volume.writable();
        String[] opts = w ? new String[]{"Aç", "Telefona kaydet", "Sil"}
                : new String[]{"Aç", "Telefona kaydet"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(opts, (d, which) -> {
                    if (which == 0) open(e);
                    else if (which == 1) exportDir(e);
                    else confirmDelete(e);
                })
                .show();
    }

    private void confirmDelete(final CardVolume.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage(e.name + (e.dir ? "\n\nKlasörün içindeki her şey de silinecek." : "")
                        + "\n\nSilinen dosyalar geri getirilemez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> run("Siliniyor", (p, c) -> {
                    volume.delete(e, c);
                    return e.name + " silindi.";
                }))
                .show();
    }

    private void askFolderName() {
        if (busy || volume == null) return;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Klasör adı");
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Yeni klasör")
                .setView(box)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Oluştur", (d, w) -> {
                    final String name = input.getText().toString();
                    final CardVolume.Entry dir = current;
                    run("Klasör oluşturuluyor", (p, c) -> {
                        CardVolume.Entry e = volume.mkdir(dir, name);
                        return e.name + " oluşturuldu.";
                    });
                })
                .show();
    }

    // ---------------------------------------------------------------- karta ekleme

    private void pickImport() {
        if (busy || volume == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (RuntimeException e) {
            progressText.setText("Dosya seçici açılamadı.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_IMPORT) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importFiles(uris);
        } else if (requestCode == REQ_EXPORT_FILE && data.getData() != null && pending != null) {
            saveFile(pending, data.getData());
        } else if (requestCode == REQ_EXPORT_DIR && data.getData() != null && pending != null) {
            saveDir(pending, data.getData());
        }
    }

    private void importFiles(final List<Uri> uris) {
        final ContentResolver cr = getContentResolver();
        final CardVolume.Entry dir = current;
        run("Karta kopyalanıyor", (p, c) -> {
            int ok = 0;
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                String name = "dosya";
                long size = -1;
                try (Cursor cur = cr.query(u, null, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int si = cur.getColumnIndex(OpenableColumns.SIZE);
                        if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                        if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
                    }
                } catch (RuntimeException ignored) {
                }
                setProgressLater((i + 1) + "/" + uris.size() + ": " + name);
                InputStream in = cr.openInputStream(u);
                if (in == null) throw new IOException(name + " açılamadı");
                try {
                    volume.create(dir, name, in, size, p, c);
                } finally {
                    in.close();
                }
                ok++;
            }
            return ok + " dosya karta kopyalandı.";
        });
    }

    // ---------------------------------------------------------------- telefona kaydetme

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private void exportFile(CardVolume.Entry e) {
        pending = e;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime(e.name));
        intent.putExtra(Intent.EXTRA_TITLE, e.name);
        try {
            startActivityForResult(intent, REQ_EXPORT_FILE);
        } catch (RuntimeException ex) {
            progressText.setText("Dosya kaydetme ekranı açılamadı.");
        }
    }

    private void exportDir(CardVolume.Entry e) {
        pending = e;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_DIR);
        } catch (RuntimeException ex) {
            progressText.setText("Klasör seçme ekranı açılamadı.");
        }
    }

    private void copyOut(ContentResolver cr, CardVolume.Entry e, Uri target,
                         Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        OutputStream raw = cr.openOutputStream(target);
        if (raw == null) throw new IOException("Hedef dosya açılamadı");
        try (OutputStream out = new BufferedOutputStream(raw, 1024 * 1024)) {
            volume.read(e, out::write, p, c);
        }
    }

    private void saveFile(final CardVolume.Entry e, final Uri target) {
        final ContentResolver cr = getContentResolver();
        run("Telefona kaydediliyor", (p, c) -> {
            boolean ok = false;
            try {
                copyOut(cr, e, target, p, c);
                ok = true;
                return e.name + " telefona kaydedildi.";
            } finally {
                if (!ok) deleteQuietly(cr, target);
            }
        });
    }

    private void saveDir(final CardVolume.Entry dir, final Uri tree) {
        final ContentResolver cr = getContentResolver();
        run("Klasör telefona kaydediliyor", (p, c) -> {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            int[] count = {0};
            saveDirRec(cr, dir, parent, p, c, count, 0);
            return dir.name + " klasörü kaydedildi (" + count[0] + " dosya).";
        });
    }

    private void saveDirRec(ContentResolver cr, CardVolume.Entry dir, Uri parent,
                            Fat32Formatter.Progress p, DiskOps.Cancel c, int[] count, int depth)
            throws IOException {
        if (depth > 64) throw new IOException("Klasör yapısı çok derin");
        Uri made = DocumentsContract.createDocument(cr, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, dir.name);
        if (made == null) throw new IOException(dir.name + " klasörü oluşturulamadı");
        for (CardVolume.Entry k : volume.list(dir)) {
            if (c.isCancelled()) throw new DiskOps.CancelledException();
            if (k.dir) {
                saveDirRec(cr, k, made, p, c, count, depth + 1);
            } else {
                setProgressLater("Kaydediliyor: " + k.name);
                Uri f = DocumentsContract.createDocument(cr, made, mime(k.name), k.name);
                if (f == null) throw new IOException(k.name + " oluşturulamadı");
                copyOut(cr, k, f, p, c);
                count[0]++;
            }
        }
    }

    private static void deleteQuietly(ContentResolver cr, Uri uri) {
        try {
            DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignored) {
        }
    }
}

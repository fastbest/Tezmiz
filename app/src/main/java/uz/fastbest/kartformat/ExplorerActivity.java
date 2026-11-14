package uz.fastbest.kartformat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** X-plore benzeri iki panelli dosya yöneticisi. */
public class ExplorerActivity extends Activity {

    private static final int BG = 0xFF1E1F22;
    private static final int BG_PANE = 0xFF26272B;
    private static final int BG_ACTIVE = 0xFF2E3036;
    private static final int TEXT = 0xFFECECEC;
    private static final int MUTED = 0xFF9DA3AD;
    private static final int ACCENT = 0xFF4FC3F7;

    private static final Set<String> IMAGES = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
            "heif");
    private static final Set<String> TEXTS = set("txt", "log", "md", "json", "xml", "html", "htm",
            "css", "js", "java", "kt", "c", "cpp", "h", "py", "sh", "ini", "cfg", "conf",
            "properties", "csv", "srt", "sub", "yml", "yaml", "gradle", "bat", "cmd", "nfo", "m3u");
    private static final Set<String> AUDIO = set("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
            "amr", "wma");
    private static final Set<String> VIDEO = set("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts",
            "m4v", "wmv", "flv");
    private static final Set<String> ARCHIVES = set("zip", "7z", "tar", "gz", "tgz", "xz", "txz",
            "bz2", "tbz2", "iso", "rar", "jar");

    private static Set<String> set(String... s) {
        Set<String> out = new HashSet<>();
        Collections.addAll(out, s);
        return out;
    }

    // ---------------------------------------------------------------- durum

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private SharedPreferences prefs;
    private LocalFs internal;
    private final List<LocalFs> otherVolumes = new ArrayList<>();
    private UsbSession usb;
    private VaultFs vault;
    private WifiShare wifi;
    private String wifiCode;
    private final Pane[] panes = new Pane[2];
    private int active;
    private boolean busy;

    private LinearLayout paneRow;
    private LinearLayout statusBar;
    private ProgressBar statusProgress;
    private TextView statusText;
    private Button statusCancel;
    private TextView titleView;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (UsbSession.ACTION_PERMISSION.equals(a)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openUsb(null);
                } else {
                    toast("USB izni verilmedi");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                cancelled.set(true);
                io.execute(() -> usb.close());
                for (Pane p : panes) {
                    if (p.fs instanceof CardFs) p.showHome();
                }
                refreshHome();
                toast("Kart okuyucu çıkarıldı");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                refreshHome();
            }
        }
    };

    // ---------------------------------------------------------------- yaşam döngüsü

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gezgin", MODE_PRIVATE);
        internal = new LocalFs("Dahili depolama", Environment.getExternalStorageDirectory());
        findOtherVolumes();
        usb = new UsbSession(this);
        NtfsFormatter.tool = new File(getApplicationInfo().nativeLibraryDir, "libmkntfs.so");
        NtfsFormatter.workDir = getCacheDir();
        BootCode.load(getAssets());
        buildUi();

        IntentFilter f = new IntentFilter(UsbSession.ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        panes[0].showHome();
        panes[1].showHome();
        setActive(0);
        if (!hasStorageAccess()) askStorageAccess();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refreshHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHome();
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        try {
            unregisterReceiver(usbReceiver);
        } catch (RuntimeException ignored) {
        }
        if (wifi != null) wifi.stop();
        if (vault != null) vault.close();
        io.execute(() -> usb.close());
        io.shutdown();
        thumbs.shutdownNow();
        clearOpenCache();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutPanes();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        Pane p = panes[active];
        if (!p.selected.isEmpty()) {
            p.selected.clear();
            p.adapter.notifyDataSetChanged();
            return;
        }
        if (!p.goBack()) super.onBackPressed();
    }

    private void findOtherVolumes() {
        otherVolumes.clear();
        File[] dirs = getExternalFilesDirs(null);
        if (dirs == null) return;
        for (int i = 1; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            String p = dirs[i].getAbsolutePath();
            int a = p.indexOf("/Android/data/");
            if (a <= 0) continue;
            File root = new File(p.substring(0, a));
            otherVolumes.add(new LocalFs("SD kart (" + root.getName() + ")", root));
        }
    }

    // ---------------------------------------------------------------- izinler

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Dosya erişim izni")
                    .setMessage("Dosya yöneticisinin telefondaki dosyaları gösterebilmesi için "
                            + "\"Tüm dosyalara erişim\" izni gerekiyor. Açılan ekranda izni verin.")
                    .setNegativeButton("Sonra", null)
                    .setPositiveButton("İzin ver", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .show();
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshHome();
    }

    // ---------------------------------------------------------------- arayüz

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(0xFF111214);
        getWindow().setNavigationBarColor(0xFF111214);

        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(BG);
        rootView.setFitsSystemWindows(true);

        // Üst çubuk
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF111214);
        top.setPadding(dp(14), dp(6), dp(4), dp(6));
        titleView = new TextView(this);
        titleView.setText("Kart Format");
        titleView.setTextColor(TEXT);
        titleView.setTextSize(21);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(topButton("🔍", v -> askSearch()));
        top.addView(topButton("⇅", v -> askSort()));
        top.addView(topButton("⋮", v -> showMenu()));
        rootView.addView(top);

        // Paneller + araç çubuğu
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        paneRow = new LinearLayout(this);
        paneRow.setOrientation(LinearLayout.HORIZONTAL);
        panes[0] = new Pane(0);
        panes[1] = new Pane(1);
        paneRow.addView(panes[0].box);
        paneRow.addView(panes[1].box);
        body.addView(paneRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        body.addView(buildToolbar(), new LinearLayout.LayoutParams(dp(78),
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootView.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Alt durum çubuğu
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.VERTICAL);
        statusBar.setBackgroundColor(0xFF111214);
        statusBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBar.setVisibility(View.GONE);
        statusProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        statusProgress.setMax(1000);
        statusBar.addView(statusProgress);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusText = label("", 13, MUTED);
        statusText.setSingleLine(true);
        row.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusCancel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        statusCancel.setText("İptal");
        statusCancel.setTextColor(0xFFFF6E6E);
        statusCancel.setAllCaps(false);
        statusCancel.setOnClickListener(v -> {
            cancelled.set(true);
            statusCancel.setEnabled(false);
        });
        row.addView(statusCancel);
        statusBar.addView(row);
        rootView.addView(statusBar);

        setContentView(rootView);
        layoutPanes();
    }

    private TextView topButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(22);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private View buildToolbar() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF18191C);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        Object[][] tools = {
                {"⬆", "Üst klasör", (Runnable) () -> panes[active].goBack()},
                {"📂", "Yeni klasör", (Runnable) this::askNewFolder},
                {"✏️", "Yeniden adlandır", (Runnable) this::askRename},
                {"📋", "Kopyala", (Runnable) () -> copySelected(false)},
                {"➡️", "Taşı", (Runnable) () -> copySelected(true)},
                {"🗑", "Sil", (Runnable) this::askDelete},
                {"🗜", "ZIP oluştur", (Runnable) this::askZip},
                {"📦", "Arşivi aç", (Runnable) this::extractSelected},
                {"↗️", "Paylaş", (Runnable) this::shareSelected},
                {"🧭", "Birlikte aç", (Runnable) this::openWithSelected},
                {"ℹ️", "Özellikler", (Runnable) this::showProperties},
                {"☑️", "Tümünü seç", (Runnable) this::selectAll},
                {"📊", "Disk analizi", (Runnable) this::analyzeCurrent},
                {"⭐", "Sık kullanılan", (Runnable) this::addFavorite},
                {"⟳", "Yenile", (Runnable) () -> panes[active].reload()},
        };
        for (Object[] t : tools) {
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(2), dp(8), dp(2), dp(8));
            TextView icon = label((String) t[0], 24, TEXT);
            icon.setGravity(Gravity.CENTER);
            TextView name = label((String) t[1], 11, MUTED);
            name.setGravity(Gravity.CENTER);
            b.addView(icon);
            b.addView(name);
            final Runnable r = (Runnable) t[2];
            b.setOnClickListener(v -> {
                if (busy) {
                    toast("Bir işlem sürüyor");
                    return;
                }
                r.run();
            });
            col.addView(b);
        }
        sv.addView(col);
        return sv;
    }

    private void layoutPanes() {
        if (panes[0] == null) return;
        boolean land = landscape();
        for (int i = 0; i < 2; i++) {
            Pane p = panes[i];
            boolean wide = land || i == active;
            p.box.setLayoutParams(wide
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    : new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
            p.content.setVisibility(wide ? View.VISIBLE : View.GONE);
            p.strip.setVisibility(wide ? View.GONE : View.VISIBLE);
            p.header.setBackgroundColor(i == active ? 0xFF1565C0 : 0xFF33353B);
            p.box.setBackgroundColor(i == active ? BG_ACTIVE : BG_PANE);
        }
        // Dar panel her zaman kenarda: etkin panel 0 ise dar panel sağda, değilse solda
        paneRow.removeAllViews();
        paneRow.addView(panes[0].box);
        paneRow.addView(panes[1].box);
    }

    private void setActive(int i) {
        active = i;
        layoutPanes();
        updateTitle();
    }

    private void updateTitle() {
        Pane p = panes[active];
        titleView.setText(p.title == null ? "Kart Format" : p.title);
    }

    private void toast(String s) {
        Toast.makeText(this, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- liste öğeleri

    static final class Item {
        String title;
        String sub;
        String icon;
        VNode node;
        float bar = -1;
        Runnable click;
        Runnable longClick;
        Drawable drawable;
        ApplicationInfo app;
    }

    private String nodeSub(VNode n) {
        String date = n.modified > 0
                ? new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date(n.modified))
                : "";
        if (n.dir) return date;
        return DiskOps.sizeText(Math.max(0, n.size)) + (date.isEmpty() ? "" : "   " + date);
    }

    private String iconFor(VNode n) {
        if (n.dir) return "📁";
        String e = n.ext();
        if (IMAGES.contains(e)) return "🖼";
        if (VIDEO.contains(e)) return "🎬";
        if (AUDIO.contains(e)) return "🎵";
        if (e.equals("apk")) return "🤖";
        if (ARCHIVES.contains(e)) return "🗜";
        if (e.equals("pdf")) return "📕";
        if (TEXTS.contains(e)) return "📝";
        if (e.equals("doc") || e.equals("docx")) return "📘";
        if (e.equals("xls") || e.equals("xlsx")) return "📗";
        return "📄";
    }

    // ---------------------------------------------------------------- panel

    final class Pane {
        final int index;
        final LinearLayout box;
        final LinearLayout content;
        final TextView header;
        final TextView strip;
        final ListView list;
        final ItemAdapter adapter;
        final List<Item> items = new ArrayList<>();
        final Set<Item> selected = new LinkedHashSet<>();
        String mode = "home";
        String title;
        Vfs fs;
        VNode dir;
        VNode analysisDir;
        int loadToken;

        Pane(int index) {
            this.index = index;
            box = new LinearLayout(ExplorerActivity.this);
            box.setOrientation(LinearLayout.HORIZONTAL);

            content = new LinearLayout(ExplorerActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            header = label("", 14, TEXT);
            header.setPadding(dp(10), dp(6), dp(10), dp(6));
            header.setSingleLine(true);
            header.setEllipsize(android.text.TextUtils.TruncateAt.START);
            header.setOnClickListener(v -> setActive(index));
            content.addView(header);
            list = new ListView(ExplorerActivity.this);
            list.setDivider(null);
            list.setFastScrollEnabled(true);
            adapter = new ItemAdapter(this);
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, pos, id) -> {
                setActive(index);
                if (busy) return;
                Item it = items.get(pos);
                if (!selected.isEmpty() && it.node != null) {
                    toggle(it);
                } else if (it.click != null) {
                    it.click.run();
                }
            });
            list.setOnItemLongClickListener((parent, view, pos, id) -> {
                setActive(index);
                if (busy) return true;
                Item it = items.get(pos);
                if (it.longClick != null) {
                    it.longClick.run();
                } else if (it.node != null) {
                    toggle(it);
                }
                return true;
            });
            content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            box.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

            strip = label("", 15, MUTED);
            strip.setGravity(Gravity.CENTER_HORIZONTAL);
            strip.setPadding(dp(4), dp(12), dp(4), dp(12));
            strip.setOnClickListener(v -> setActive(index));
            box.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void toggle(Item it) {
            if (!selected.remove(it)) selected.add(it);
            adapter.notifyDataSetChanged();
        }

        void setHeader(String path, String t) {
            title = t;
            header.setText(path);
            StringBuilder v = new StringBuilder();
            String s = t == null ? "" : t;
            for (int i = 0; i < Math.min(14, s.length()); i++) v.append(s.charAt(i)).append('\n');
            strip.setText("➜\n\n" + v);
            if (index == active) updateTitle();
        }

        void show(List<Item> list2) {
            items.clear();
            items.addAll(list2);
            selected.clear();
            adapter.notifyDataSetChanged();
            list.setSelection(0);
        }

        // ---- ekranlar

        void showHome() {
            mode = "home";
            fs = null;
            dir = null;
            setHeader("Ana sayfa", "Kart Format");
            show(homeItems(this));
        }

        void openDir(final VNode d) {
            final int token = ++loadToken;
            header.setText("Yükleniyor…  " + d.path);
            io.execute(() -> {
                try {
                    final List<VNode> kids = d.fs.list(d);
                    main.post(() -> {
                        if (token != loadToken) return;
                        mode = "dir";
                        fs = d.fs;
                        dir = d;
                        setHeader(d.fs.title() + (d.parent == null ? "" : "  " + relPath(d)),
                                d.parent == null ? d.fs.title() : d.name);
                        show(dirItems(this, kids));
                    });
                } catch (final IOException | RuntimeException e) {
                    main.post(() -> {
                        if (token != loadToken) return;
                        header.setText(dir != null ? relPath(dir) : "Ana sayfa");
                        toast("Açılamadı: " + e.getMessage());
                    });
                }
            });
        }

        void reload() {
            switch (mode) {
                case "dir":
                    openDir(dir);
                    break;
                case "home":
                    showHome();
                    break;
                case "apps":
                    showApps(this, prefs.getBoolean("systemApps", false));
                    break;
                case "recent":
                    showRecent(this);
                    break;
                case "ftp":
                    showFtpList(this);
                    break;
                case "analysis":
                    analyze(this, analysisDir);
                    break;
                default:
                    break;
            }
        }

        boolean goBack() {
            switch (mode) {
                case "dir":
                    if (dir.parent != null) {
                        openDir(dir.parent);
                    } else {
                        if (dir.fs instanceof ZipFs) {
                            dir.fs.close();
                        }
                        showHome();
                    }
                    return true;
                case "analysis":
                    if (analysisDir != null && analysisDir.parent != null) {
                        analyze(this, analysisDir.parent);
                    } else if (analysisDir != null) {
                        openDir(analysisDir);
                    } else {
                        showHome();
                    }
                    return true;
                case "home":
                    return false;
                default:
                    showHome();
                    return true;
            }
        }

        List<VNode> selectedNodes() {
            List<VNode> out = new ArrayList<>();
            for (Item it : selected) if (it.node != null) out.add(it.node);
            return out;
        }
    }

    private String relPath(VNode d) {
        if (d.fs instanceof LocalFs) {
            String root = ((LocalFs) d.fs).rootFile().getAbsolutePath();
            return d.path.startsWith(root) ? d.path.substring(root.length()) : d.path;
        }
        return d.path;
    }

    final class ItemAdapter extends BaseAdapter {
        private final Pane pane;

        ItemAdapter(Pane pane) {
            this.pane = pane;
        }

        @Override
        public int getCount() {
            return pane.items.size();
        }

        @Override
        public Object getItem(int position) {
            return pane.items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView == null ? new Row() : (Row) convertView.getTag();
            Item it = pane.items.get(position);
            boolean sel = pane.selected.contains(it);
            row.root.setBackgroundColor(sel ? 0xFF1E4A6E : Color.TRANSPARENT);
            row.check.setVisibility(pane.selected.isEmpty() || it.node == null ? View.GONE : View.VISIBLE);
            row.check.setText(sel ? "☑" : "☐");
            row.title.setText(it.title);
            row.sub.setText(it.sub == null ? "" : it.sub);
            row.sub.setVisibility(it.sub == null || it.sub.isEmpty() ? View.GONE : View.VISIBLE);
            if (it.bar >= 0) {
                row.bar.setVisibility(View.VISIBLE);
                row.bar.setProgress((int) (it.bar * 1000));
            } else {
                row.bar.setVisibility(View.GONE);
            }
            row.image.setTag(null);
            if (it.drawable != null) {
                row.image.setImageDrawable(it.drawable);
                row.image.setVisibility(View.VISIBLE);
                row.icon.setVisibility(View.GONE);
            } else {
                row.image.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setText(it.icon);
                if (it.node != null && !it.node.dir && IMAGES.contains(it.node.ext())) {
                    File f = it.node.fs.localFile(it.node);
                    if (f != null) loadThumb(row, f);
                }
            }
            return row.root;
        }
    }

    final class Row {
        final LinearLayout root;
        final TextView check;
        final TextView icon;
        final ImageView image;
        final TextView title;
        final TextView sub;
        final ProgressBar bar;

        Row() {
            root = new LinearLayout(ExplorerActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(8), dp(7), dp(8), dp(7));
            root.setMinimumHeight(dp(56));
            check = label("☐", 20, ACCENT);
            check.setPadding(0, 0, dp(6), 0);
            root.addView(check);
            icon = label("", 26, TEXT);
            icon.setGravity(Gravity.CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            image = new ImageView(ExplorerActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(image, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout texts = new LinearLayout(ExplorerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, 0, 0);
            title = label("", 16, TEXT);
            title.setMaxLines(2);
            sub = label("", 12.5f, MUTED);
            sub.setSingleLine(true);
            bar = new ProgressBar(ExplorerActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            texts.addView(title);
            texts.addView(sub);
            texts.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
            root.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.setTag(this);
        }
    }

    private void loadThumb(final Row row, final File f) {
        final String key = f.getAbsolutePath() + ":" + f.lastModified();
        Bitmap b = thumbCache.get(key);
        if (b != null) {
            row.image.setImageBitmap(b);
            row.image.setVisibility(View.VISIBLE);
            row.icon.setVisibility(View.GONE);
            return;
        }
        row.image.setTag(key);
        try {
            thumbs.execute(() -> {
                Bitmap bm = decodeSampled(f, dp(48));
                if (bm == null) return;
                thumbCache.put(key, bm);
                main.post(() -> {
                    if (key.equals(row.image.getTag())) {
                        row.image.setImageBitmap(bm);
                        row.image.setVisibility(View.VISIBLE);
                        row.icon.setVisibility(View.GONE);
                    }
                });
            });
        } catch (RuntimeException ignored) {
        }
    }

    static Bitmap decodeSampled(File f, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- ana sayfa

    private void refreshHome() {
        for (Pane p : panes) {
            if (p != null && "home".equals(p.mode)) p.show(homeItems(p));
        }
    }

    private Item item(String icon, String title, String sub, Runnable click) {
        Item it = new Item();
        it.icon = icon;
        it.title = title;
        it.sub = sub;
        it.click = click;
        return it;
    }

    private Item volumeItem(final Pane p, final Vfs fs, String icon) {
        long total = fs.totalBytes();
        long free = fs.freeBytes();
        Item it = item(icon, fs.title(), total > 0 && free >= 0
                ? "Boş " + DiskOps.sizeText(free) + " / " + DiskOps.sizeText(total) : null,
                () -> p.openDir(fs.root()));
        if (total > 0 && free >= 0) it.bar = 1f - (float) free / total;
        return it;
    }

    private List<Item> homeItems(final Pane p) {
        List<Item> out = new ArrayList<>();
        if (!hasStorageAccess()) {
            out.add(item("⚠️", "Dosya erişim izni ver", "Telefondaki dosyaları görmek için gerekli",
                    this::askStorageAccess));
        }
        out.add(volumeItem(p, internal, "📱"));
        for (LocalFs v : otherVolumes) out.add(volumeItem(p, v, "💾"));

        if (usb.isOpen() && !usb.volumes().isEmpty()) {
            for (CardFs c : usb.volumes()) out.add(volumeItem(p, c, "🔌"));
        } else if (usb.device() != null) {
            String sub = usb.isOpen() && usb.error() != null ? usb.error() : "Açmak için dokunun";
            out.add(item("🔌", "USB kart (OTG)", sub, () -> openUsb(p)));
        }

        for (final String fav : favorites()) {
            final File f = new File(fav);
            out.add(item("⭐", f.getName(), fav, () -> openLocal(p, f)));
        }
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        out.add(item("📥", "İndirilenler", null, () -> openLocal(p, dl)));
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        out.add(item("📷", "Kamera (DCIM)", null, () -> openLocal(p, dcim)));
        out.add(item("🕘", "Son dosyalar", null, () -> showRecent(p)));
        out.add(item("🤖", "Uygulama yöneticisi", null,
                () -> showApps(p, prefs.getBoolean("systemApps", false))));
        out.add(item("🌐", "FTP sunucuları", null, () -> showFtpList(p)));
        out.add(item("📶", "WiFi dosya paylaşımı", wifi == null ? "Kapalı"
                : "http://" + WifiShare.address() + ":" + wifi.port + "   kod: " + wifiCode,
                this::toggleWifi));
        if (vault == null) {
            out.add(item("🔒", "Kasa", "Şifreli dosyalar", () -> openVault(p)));
        } else {
            out.add(item("🔓", "Kasa (açık)", "Dokun: aç  •  Uzun bas: kilitle",
                    () -> p.openDir(vault.root())));
            out.get(out.size() - 1).longClick = this::lockVault;
        }
        out.add(item("🛠", "USB kart araçları", "Biçimlendir, imaj yaz, Windows/FreeDOS kartı, PS2…",
                this::openTools));
        return out;
    }

    private void openLocal(Pane p, File f) {
        if (!f.isDirectory()) {
            toast("Klasör bulunamadı: " + f.getName());
            return;
        }
        LocalFs fs = internal;
        for (LocalFs v : otherVolumes) {
            if (f.getAbsolutePath().startsWith(v.rootFile().getAbsolutePath())) fs = v;
        }
        if (!f.getAbsolutePath().startsWith(fs.rootFile().getAbsolutePath())) {
            fs = new LocalFs(f.getName(), f);
        }
        p.openDir(fs.node(f));
    }

    private List<String> favorites() {
        String s = prefs.getString("favorites", "");
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.trim().isEmpty()) out.add(x);
        return out;
    }

    private void addFavorite() {
        Pane p = panes[active];
        if (!"dir".equals(p.mode) || !(p.fs instanceof LocalFs)) {
            toast("Telefon belleğinde bir klasör açın");
            return;
        }
        List<String> favs = favorites();
        String path = p.dir.path;
        if (favs.contains(path)) {
            favs.remove(path);
            toast("Sık kullanılanlardan çıkarıldı");
        } else {
            favs.add(path);
            toast("Sık kullanılanlara eklendi");
        }
        StringBuilder sb = new StringBuilder();
        for (String f : favs) sb.append(f).append('\n');
        prefs.edit().putString("favorites", sb.toString()).apply();
        refreshHome();
    }

    // ---------------------------------------------------------------- USB

    private void openUsb(final Pane target) {
        if (!usb.ensurePermission()) {
            if (usb.device() == null) toast("Kart okuyucu bulunamadı");
            return;
        }
        runOp("USB kart açılıyor", (pr, c) -> {
            usb.open();
            return usb.volumes().isEmpty() ? (usb.error() != null ? usb.error()
                    : "Kartta açılabilen bölüm yok") : null;
        }, () -> {
            refreshHome();
            if (target != null && usb.volumes().size() == 1) {
                target.openDir(usb.volumes().get(0).root());
            }
        });
    }

    private void openTools() {
        // Araçlar kartı kendisi açar; gezgin bağlantısını bırakır
        for (Pane p : panes) {
            if (p.fs instanceof CardFs) p.showHome();
        }
        try {
            io.submit(() -> usb.close()).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // bağlantı kapanmadıysa araçlar ekranı "Yenile" ile yeniden dener
        }
        startActivity(new Intent(this, MainActivity.class));
    }

    // ---------------------------------------------------------------- dizin listesi

    private Comparator<VNode> comparator() {
        final String sort = prefs.getString("sort", "name");
        final boolean desc = prefs.getBoolean("desc", false);
        return (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int r;
            switch (sort) {
                case "size":
                    r = Long.compare(a.size, b.size);
                    break;
                case "date":
                    r = Long.compare(a.modified, b.modified);
                    break;
                case "type":
                    r = a.ext().compareTo(b.ext());
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                    break;
                default:
                    r = a.name.compareToIgnoreCase(b.name);
            }
            return desc ? -r : r;
        };
    }

    private List<Item> dirItems(final Pane p, List<VNode> kids) {
        boolean hidden = prefs.getBoolean("hidden", false);
        List<VNode> sorted = new ArrayList<>();
        for (VNode k : kids) if (hidden || !k.hidden()) sorted.add(k);
        Collections.sort(sorted, comparator());
        List<Item> out = new ArrayList<>();
        for (final VNode k : sorted) out.add(nodeItem(p, k, null));
        if (out.isEmpty()) {
            Item e = item("·", "(boş klasör)", null, null);
            out.add(e);
        }
        return out;
    }

    private Item nodeItem(final Pane p, final VNode k, String subOverride) {
        Item it = item(iconFor(k), k.name, subOverride != null ? subOverride : nodeSub(k), null);
        it.node = k;
        it.click = () -> {
            if (k.dir) p.openDir(k);
            else openFile(p, k);
        };
        return it;
    }

    // ---------------------------------------------------------------- dosya açma

    private void openFile(final Pane p, final VNode f) {
        String e = f.ext();
        File local = f.fs.localFile(f);
        if ((e.equals("zip") || e.equals("jar")) && local != null) {
            try {
                ZipFs z = new ZipFs(local);
                p.openDir(z.root());
            } catch (IOException | RuntimeException ex) {
                // Ör. Türkçe Windows'ta UTF-8 olmadan sıkıştırılmış adlar: "Arşivi aç" ile çıkarılabilir
                new AlertDialog.Builder(this).setTitle(f.name)
                        .setMessage("ZIP içine göz atılamadı (" + ex.getMessage() + "). "
                                + "Arşivi bir klasöre çıkarmayı deneyin.")
                        .setNegativeButton("Vazgeç", null)
                        .setPositiveButton("Buraya çıkar", (d, w) -> extract(f, p.dir))
                        .show();
            }
            return;
        }
        if (ARCHIVES.contains(e) && !e.equals("zip")) {
            new AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setItems(new String[]{"Diğer panele çıkar", "Bu klasöre çıkar",
                            "Başka uygulamayla aç"}, (d, w) -> {
                        if (w == 2) withLocal(f, file -> openExternal(file, null));
                        else extract(f, w == 0 ? other().dir : p.dir);
                    }).show();
            return;
        }
        if (IMAGES.contains(e)) {
            if (local != null) {
                ArrayList<String> paths = new ArrayList<>();
                int index = 0;
                for (Item it : p.items) {
                    if (it.node != null && !it.node.dir && IMAGES.contains(it.node.ext())) {
                        if (it.node.same(f)) index = paths.size();
                        paths.add(it.node.fs.localFile(it.node).getAbsolutePath());
                    }
                }
                startActivity(new Intent(this, ImageViewerActivity.class)
                        .putStringArrayListExtra("paths", paths).putExtra("index", index));
            } else {
                withLocal(f, file -> {
                    ArrayList<String> one = new ArrayList<>();
                    one.add(file.getAbsolutePath());
                    startActivity(new Intent(this, ImageViewerActivity.class)
                            .putStringArrayListExtra("paths", one));
                });
            }
            return;
        }
        if (TEXTS.contains(e)) {
            openText(f);
            return;
        }
        withLocal(f, file -> openExternal(file, null));
    }

    private void openText(VNode f) {
        final File local = f.fs.localFile(f);
        withLocal(f, file -> startActivity(new Intent(this, TextEditorActivity.class)
                .putExtra("path", file.getAbsolutePath())
                .putExtra("readOnly", local == null)
                .putExtra("title", f.name)));
    }

    private interface FileAction {
        void run(File f);
    }

    /** Dosya telefonda değilse önbelleğe kopyalar, sonra verilen işi yapar. */
    private void withLocal(final VNode f, final FileAction action) {
        File local = f.fs.localFile(f);
        if (local != null) {
            action.run(local);
            return;
        }
        final File[] out = new File[1];
        runOp("Dosya hazırlanıyor", (pr, c) -> {
            out[0] = new FileOps(pr, c, null).toCache(f, getCacheDir());
            return null;
        }, () -> {
            if (out[0] != null) action.run(out[0]);
        });
    }

    private Uri uriFor(File f) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "*/*";
    }

    private void openExternal(File f, String forcedMime) {
        try {
            Uri uri = uriFor(f);
            String type = forcedMime != null ? forcedMime : mime(f.getName());
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                type = "application/vnd.android.package-archive";
            }
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, f.getName()));
        } catch (RuntimeException e) {
            toast("Bu dosyayı açacak uygulama yok");
        }
    }

    private void openWithSelected() {
        final List<VNode> sel = panes[active].selectedNodes();
        if (sel.size() != 1 || sel.get(0).dir) {
            toast("Bir dosya seçin (uzun basın)");
            return;
        }
        final VNode f = sel.get(0);
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new String[]{"Başka uygulamayla aç", "Metin olarak", "Hex görüntüleyici",
                        "Resim olarak", "Video olarak", "Ses olarak"}, (d, w) -> {
                    switch (w) {
                        case 0:
                            withLocal(f, file -> openExternal(file, null));
                            break;
                        case 1:
                            openText(f);
                            break;
                        case 2:
                            withLocal(f, file -> startActivity(new Intent(this, HexViewerActivity.class)
                                    .putExtra("path", file.getAbsolutePath())));
                            break;
                        case 3:
                            withLocal(f, file -> openExternal(file, "image/*"));
                            break;
                        case 4:
                            withLocal(f, file -> openExternal(file, "video/*"));
                            break;
                        default:
                            withLocal(f, file -> openExternal(file, "audio/*"));
                    }
                }).show();
    }

    private void clearOpenCache() {
        File d = new File(getCacheDir(), "acilan");
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---------------------------------------------------------------- işlem çalıştırıcı

    interface Job {
        String run(Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException;
    }

    private void runOp(String title, Job job) {
        runOp(title, job, null);
    }

    private void runOp(final String title, final Job job, final Runnable after) {
        if (busy) {
            toast("Bir işlem sürüyor");
            return;
        }
        busy = true;
        cancelled.set(false);
        statusBar.setVisibility(View.VISIBLE);
        statusProgress.setIndeterminate(true);
        statusText.setText(title + "…");
        statusCancel.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Fat32Formatter.Progress progress = new Fat32Formatter.Progress() {
            private long last;

            @Override
            public void onProgress(final long done, final long total) {
                long now = SystemClock.elapsedRealtime();
                if (now - last < 120 && done != total) return;
                last = now;
                main.post(() -> {
                    if (!busy || total <= 0) return;
                    statusProgress.setIndeterminate(false);
                    statusProgress.setProgress((int) Math.min(1000, done * 1000 / total));
                });
            }
        };
        io.execute(() -> {
            String msg;
            try {
                msg = job.run(progress, cancelled::get);
            } catch (DiskOps.CancelledException e) {
                msg = "İptal edildi";
            } catch (IOException e) {
                msg = "Hata: " + e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                msg = "Beklenmeyen hata: " + e;
            }
            final String m = msg;
            main.post(() -> {
                busy = false;
                statusBar.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing()) return;
                if (m != null) toast(m);
                if (after != null) after.run();
            });
        });
    }

    private void status(final String s) {
        main.post(() -> {
            if (busy) statusText.setText(s);
        });
    }

    private Pane other() {
        return panes[1 - active];
    }

    private void reloadDirs() {
        for (Pane p : panes) {
            if ("dir".equals(p.mode)) p.reload();
        }
        refreshHome();
    }

    // ---------------------------------------------------------------- dosya işlemleri

    private List<VNode> needSelection() {
        List<VNode> sel = panes[active].selectedNodes();
        if (sel.isEmpty()) toast("Önce öğeleri seçin (uzun basın)");
        return sel;
    }

    private boolean needWritableDir(Pane p) {
        if (!"dir".equals(p.mode)) {
            toast("Önce bir klasör açın");
            return false;
        }
        if (!p.fs.writable()) {
            toast("Bu konum salt okunur");
            return false;
        }
        return true;
    }

    private EditText input(String text, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(text);
        e.setHint(hint);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v);
        return box;
    }

    private void askNewFolder() {
        final Pane p = panes[active];
        if (!needWritableDir(p)) return;
        final EditText e = input("", "Klasör adı");
        new AlertDialog.Builder(this).setTitle("Yeni klasör").setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Oluştur", (d, w) -> {
                    final String name = e.getText().toString();
                    final VNode dir = p.dir;
                    runOp("Klasör oluşturuluyor", (pr, c) -> {
                        dir.fs.mkdir(dir, name);
                        return null;
                    }, this::reloadDirs);
                }).show();
    }

    private void askRename() {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        if (sel.size() != 1) {
            toast("Yeniden adlandırmak için tek öğe seçin");
            return;
        }
        final VNode n = sel.get(0);
        if (!n.fs.writable()) {
            toast("Bu konum salt okunur");
            return;
        }
        final EditText e = input(n.name, "Yeni ad");
        new AlertDialog.Builder(this).setTitle("Yeniden adlandır").setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Tamam", (d, w) -> {
                    final String name = e.getText().toString();
                    runOp("Yeniden adlandırılıyor", (pr, c) -> {
                        n.fs.rename(n, name);
                        return null;
                    }, this::reloadDirs);
                }).show();
    }

    private void copySelected(final boolean move) {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        final Pane target = other();
        if (!"dir".equals(target.mode)) {
            toast("Diğer panelde hedef klasörü açın");
            setActive(target.index);
            return;
        }
        if (!target.fs.writable()) {
            toast("Hedef salt okunur");
            return;
        }
        if (move && !sel.get(0).fs.writable()) {
            toast("Kaynak salt okunur; taşınamaz, kopyalayın");
            return;
        }
        final VNode dest = target.dir;
        new AlertDialog.Builder(this)
                .setTitle(move ? "Taşı" : "Kopyala")
                .setMessage(sel.size() + " öğe →\n" + dest.fs.title() + "  " + relPath(dest))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton(move ? "Taşı" : "Kopyala", (d, w) ->
                        runOp(move ? "Taşınıyor" : "Kopyalanıyor",
                                (pr, c) -> new FileOps(pr, c, this::status).copy(sel, dest, move),
                                this::reloadDirs))
                .show();
    }

    private void askDelete() {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        if (!sel.get(0).fs.writable()) {
            toast("Bu konum salt okunur");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(5, sel.size()); i++) names.append("• ").append(sel.get(i).name).append('\n');
        if (sel.size() > 5) names.append("… ve ").append(sel.size() - 5).append(" öğe daha\n");
        new AlertDialog.Builder(this)
                .setTitle(sel.size() + " öğe silinsin mi?")
                .setMessage(names + "\nSilinen dosyalar geri getirilemez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> runOp("Siliniyor", (pr, c) -> {
                    FileOps ops = new FileOps(pr, c, this::status);
                    ops.delete(sel);
                    return ops.files() + " öğe silindi";
                }, this::reloadDirs))
                .show();
    }

    private void askZip() {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        final Pane p = panes[active];
        final VNode dest = "dir".equals(p.mode) && p.fs.writable() ? p.dir
                : "dir".equals(other().mode) && other().fs.writable() ? other().dir : null;
        if (dest == null) {
            toast("ZIP'in kaydedileceği klasörü açın");
            return;
        }
        final EditText e = input((sel.size() == 1 ? sel.get(0).name : "arsiv") + ".zip", "Dosya adı");
        new AlertDialog.Builder(this).setTitle("ZIP oluştur").setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Oluştur", (d, w) -> {
                    String n = e.getText().toString().trim();
                    final String name = n.toLowerCase(Locale.ROOT).endsWith(".zip") ? n : n + ".zip";
                    runOp("Sıkıştırılıyor", (pr, c) -> {
                        FileOps ops = new FileOps(pr, c, this::status);
                        VNode z = ops.zip(sel, dest, name, getCacheDir());
                        return z.name + " oluşturuldu (" + DiskOps.sizeText(z.size) + ")";
                    }, this::reloadDirs);
                }).show();
    }

    private void extractSelected() {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        if (sel.size() != 1 || sel.get(0).dir) {
            toast("Bir arşiv dosyası seçin");
            return;
        }
        Pane o = other();
        VNode target = "dir".equals(o.mode) && o.fs.writable() ? o.dir : panes[active].dir;
        extract(sel.get(0), target);
    }

    private void extract(final VNode archive, final VNode target) {
        if (target == null || !target.fs.writable()) {
            toast("Çıkarılacak yazılabilir bir klasör açın");
            return;
        }
        runOp("Arşiv açılıyor", (pr, c) -> {
            FileOps ops = new FileOps(pr, c, this::status);
            VNode d = ops.extract(archive, target, getCacheDir());
            return ops.files() + " dosya \"" + d.name + "\" klasörüne açıldı";
        }, this::reloadDirs);
    }

    private void shareSelected() {
        final List<VNode> sel = needSelection();
        if (sel.isEmpty()) return;
        for (VNode n : sel) {
            if (n.dir) {
                toast("Klasörler paylaşılamaz; önce ZIP yapın");
                return;
            }
        }
        final ArrayList<File> files = new ArrayList<>();
        runOp("Paylaşım hazırlanıyor", (pr, c) -> {
            FileOps ops = new FileOps(pr, c, null);
            for (VNode n : sel) files.add(ops.toCache(n, getCacheDir()));
            return null;
        }, () -> {
            if (files.isEmpty()) return;
            try {
                Intent i;
                if (files.size() == 1) {
                    i = new Intent(Intent.ACTION_SEND).setType(mime(files.get(0).getName()))
                            .putExtra(Intent.EXTRA_STREAM, uriFor(files.get(0)));
                } else {
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (File f : files) uris.add(uriFor(f));
                    i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    ClipData clip = ClipData.newRawUri("", uris.get(0));
                    for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
                    i.setClipData(clip);
                }
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Paylaş"));
            } catch (RuntimeException e) {
                toast("Paylaşılamadı: " + e.getMessage());
            }
        });
    }

    private void showProperties() {
        Pane p = panes[active];
        List<VNode> sel = p.selectedNodes();
        if (sel.isEmpty() && "dir".equals(p.mode)) sel = Collections.singletonList(p.dir);
        if (sel.isEmpty()) {
            toast("Önce öğeleri seçin (uzun basın)");
            return;
        }
        final List<VNode> nodes = sel;
        final long[][] r = new long[1][];
        runOp("Hesaplanıyor", (pr, c) -> {
            r[0] = new FileOps(pr, c, this::status).measure(nodes);
            return null;
        }, () -> {
            if (r[0] == null) return;
            StringBuilder sb = new StringBuilder();
            if (nodes.size() == 1) {
                VNode n = nodes.get(0);
                sb.append("Ad: ").append(n.name).append('\n');
                sb.append("Yer: ").append(n.fs.title()).append("  ").append(relPath(n)).append('\n');
                if (n.modified > 0) {
                    sb.append("Değiştirilme: ").append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",
                            Locale.ROOT).format(new Date(n.modified))).append('\n');
                }
                File f = n.fs.localFile(n);
                if (f != null) {
                    sb.append("İzinler: ").append(f.canRead() ? "okunur " : "")
                            .append(f.canWrite() ? "yazılır " : "").append(f.isHidden() ? "gizli" : "")
                            .append('\n');
                }
            } else {
                sb.append(nodes.size()).append(" öğe seçili\n");
            }
            sb.append("Boyut: ").append(DiskOps.sizeText(r[0][0])).append(" (")
                    .append(String.format(Locale.ROOT, "%,d", r[0][0])).append(" bayt)\n");
            sb.append("İçerik: ").append(r[0][1]).append(" dosya, ")
                    .append(Math.max(0, r[0][2] - countDirs(nodes))).append(" klasör");
            new AlertDialog.Builder(this).setTitle("Özellikler").setMessage(sb)
                    .setPositiveButton("Tamam", null).show();
        });
    }

    private static int countDirs(List<VNode> nodes) {
        int n = 0;
        for (VNode v : nodes) if (v.dir) n++;
        return n;
    }

    private void selectAll() {
        Pane p = panes[active];
        boolean all = true;
        for (Item it : p.items) if (it.node != null && !p.selected.contains(it)) all = false;
        p.selected.clear();
        if (!all) for (Item it : p.items) if (it.node != null) p.selected.add(it);
        p.adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- arama / sıralama / menü

    private void askSearch() {
        final Pane p = panes[active];
        final VNode where = "dir".equals(p.mode) ? p.dir : internal.root();
        final EditText e = input("", "Dosya adı (bir kısmı)");
        new AlertDialog.Builder(this).setTitle("Ara: " + where.name).setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Ara", (d, w) -> {
                    final String q = e.getText().toString().trim();
                    if (q.isEmpty()) return;
                    final List<VNode> found = new ArrayList<>();
                    runOp("Aranıyor", (pr, c) -> {
                        found.addAll(new FileOps(pr, c, this::status).search(where, q, 1000));
                        return found.isEmpty() ? "Bulunamadı" : null;
                    }, () -> {
                        if (found.isEmpty()) return;
                        p.mode = "search";
                        p.setHeader("Arama: \"" + q + "\"  (" + found.size() + ")", "Arama");
                        List<Item> items = new ArrayList<>();
                        for (VNode n : found) {
                            items.add(nodeItem(p, n, (n.dir ? "" : DiskOps.sizeText(n.size) + "  ")
                                    + (n.parent != null ? relPath(n.parent) : "")));
                        }
                        p.show(items);
                    });
                }).show();
    }

    private void askSort() {
        final String[] keys = {"name", "size", "date", "type"};
        String[] names = {"Ada göre", "Boyuta göre", "Tarihe göre", "Türe göre"};
        String cur = prefs.getString("sort", "name");
        int sel = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(cur)) sel = i;
        final boolean desc = prefs.getBoolean("desc", false);
        new AlertDialog.Builder(this).setTitle("Sıralama")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    prefs.edit().putString("sort", keys[w]).apply();
                    d.dismiss();
                    reloadDirs();
                })
                .setNeutralButton(desc ? "Artan yap" : "Azalan yap", (d, w) -> {
                    prefs.edit().putBoolean("desc", !desc).apply();
                    reloadDirs();
                })
                .show();
    }

    private void showMenu() {
        final boolean hidden = prefs.getBoolean("hidden", false);
        String[] opts = {
                (hidden ? "✓ " : "") + "Gizli dosyaları göster",
                "Panelleri değiştir",
                "Diğer panelde aynı klasörü aç",
                "WiFi dosya paylaşımı",
                "USB kart araçları",
                "Önbelleği temizle",
                "Hakkında"
        };
        new AlertDialog.Builder(this).setItems(opts, (d, w) -> {
            switch (w) {
                case 0:
                    prefs.edit().putBoolean("hidden", !hidden).apply();
                    reloadDirs();
                    break;
                case 1:
                    setActive(1 - active);
                    break;
                case 2:
                    Pane p = panes[active];
                    if ("dir".equals(p.mode)) other().openDir(p.dir);
                    break;
                case 3:
                    toggleWifi();
                    break;
                case 4:
                    openTools();
                    break;
                case 5:
                    clearOpenCache();
                    thumbCache.evictAll();
                    toast("Önbellek temizlendi");
                    break;
                default:
                    new AlertDialog.Builder(this).setTitle("Kart Format")
                            .setMessage("İki panelli dosya yöneticisi ve USB kart araçları.\n\n"
                                    + "Açık kaynak bileşenler: ntfs-3g (GPL-2.0), Rufus/ms-sys "
                                    + "(GPL), Apache Commons Compress ve Net (Apache-2.0), "
                                    + "XZ for Java (0BSD), NanoHTTPD (BSD), AndroidX (Apache-2.0).")
                            .setPositiveButton("Tamam", null).show();
            }
        }).show();
    }

    // ---------------------------------------------------------------- son dosyalar

    private void showRecent(final Pane p) {
        if (!hasStorageAccess()) {
            askStorageAccess();
            return;
        }
        final List<VNode> found = new ArrayList<>();
        runOp("Son dosyalar yükleniyor", (pr, c) -> {
            String[] proj = {MediaStore.Files.FileColumns.DATA};
            try (Cursor cur = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    proj, null, null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
                if (cur == null) return "Liste alınamadı";
                String root = internal.rootFile().getAbsolutePath();
                while (cur.moveToNext() && found.size() < 300) {
                    String path = cur.getString(0);
                    if (path == null || !path.startsWith(root)) continue;
                    File f = new File(path);
                    if (!f.isFile() || f.getName().startsWith(".")) continue;
                    found.add(internal.node(f));
                }
            }
            return null;
        }, () -> {
            p.mode = "recent";
            p.setHeader("Son dosyalar (" + found.size() + ")", "Son dosyalar");
            List<Item> items = new ArrayList<>();
            for (VNode n : found) {
                items.add(nodeItem(p, n, nodeSub(n) + "   " + relPath(n.parent)));
            }
            p.show(items);
        });
    }

    // ---------------------------------------------------------------- disk analizi

    private void analyzeCurrent() {
        Pane p = panes[active];
        if (!"dir".equals(p.mode)) {
            toast("Önce bir klasör açın");
            return;
        }
        analyze(p, p.dir);
    }

    private void analyze(final Pane p, final VNode dir) {
        final List<Object[]> res = new ArrayList<>();
        runOp("Disk analizi", (pr, c) -> {
            res.addAll(new FileOps(pr, c, this::status).analyze(dir));
            return null;
        }, () -> {
            if (res.isEmpty() && !"analysis".equals(p.mode)) {
                toast("Klasör boş");
                return;
            }
            Collections.sort(res, (a, b) -> Long.compare((Long) b[1], (Long) a[1]));
            long total = 0;
            for (Object[] o : res) total += (Long) o[1];
            p.mode = "analysis";
            p.analysisDir = dir;
            p.fs = dir.fs;
            p.dir = dir;
            p.setHeader("Disk analizi: " + relPath(dir) + "  (" + DiskOps.sizeText(total) + ")",
                    "Analiz: " + dir.name);
            List<Item> items = new ArrayList<>();
            for (Object[] o : res) {
                final VNode n = (VNode) o[0];
                long bytes = (Long) o[1];
                Item it = nodeItem(p, n, DiskOps.sizeText(bytes) + String.format(Locale.ROOT, "   %%%.1f",
                        total > 0 ? bytes * 100.0 / total : 0));
                it.bar = total > 0 ? (float) bytes / total : 0;
                if (n.dir) it.click = () -> analyze(p, n);
                items.add(it);
            }
            p.show(items);
        });
    }

    // ---------------------------------------------------------------- uygulama yöneticisi

    private void showApps(final Pane p, final boolean system) {
        final PackageManager pm = getPackageManager();
        final List<Item> items = new ArrayList<>();
        runOp("Uygulamalar yükleniyor", (pr, c) -> {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<Object[]> rows = new ArrayList<>();
            for (ApplicationInfo a : apps) {
                boolean sys = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (a.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (sys && !system) continue;
                String label = String.valueOf(pm.getApplicationLabel(a));
                rows.add(new Object[]{label, a});
            }
            Collections.sort(rows, (x, y) -> ((String) x[0]).compareToIgnoreCase((String) y[0]));
            for (Object[] r : rows) {
                final ApplicationInfo a = (ApplicationInfo) r[1];
                String ver = "";
                try {
                    PackageInfo pi = pm.getPackageInfo(a.packageName, 0);
                    ver = pi.versionName == null ? "" : " • " + pi.versionName;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                long size = a.sourceDir != null ? new File(a.sourceDir).length() : 0;
                Item it = item("🤖", (String) r[0], a.packageName + ver + " • "
                        + DiskOps.sizeText(size), null);
                it.app = a;
                try {
                    it.drawable = pm.getApplicationIcon(a);
                } catch (RuntimeException ignored) {
                }
                it.click = () -> appMenu(it);
                items.add(it);
            }
            return null;
        }, () -> {
            p.mode = "apps";
            p.setHeader("Uygulamalar (" + items.size() + ")" + (system ? " + sistem" : ""),
                    "Uygulamalar");
            Item toggle = item("⚙️", system ? "Yalnızca yüklenen uygulamalar" : "Sistem uygulamalarını da göster",
                    null, () -> {
                        prefs.edit().putBoolean("systemApps", !system).apply();
                        showApps(p, !system);
                    });
            items.add(0, toggle);
            p.show(items);
        });
    }

    private void appMenu(final Item it) {
        final ApplicationInfo a = it.app;
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(new String[]{"Aç", "APK'yı yedekle (İndirilenler/APK)", "APK'yı paylaş",
                        "Uygulama bilgisi", "Kaldır"}, (d, w) -> {
                    switch (w) {
                        case 0: {
                            Intent i = getPackageManager().getLaunchIntentForPackage(a.packageName);
                            if (i != null) startActivity(i);
                            else toast("Bu uygulamanın açılacak ekranı yok");
                            break;
                        }
                        case 1:
                            backupApk(it, false);
                            break;
                        case 2:
                            backupApk(it, true);
                            break;
                        case 3:
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + a.packageName)));
                            break;
                        default:
                            startActivity(new Intent(Intent.ACTION_DELETE,
                                    Uri.parse("package:" + a.packageName)));
                    }
                }).show();
    }

    private void backupApk(final Item it, final boolean share) {
        final ApplicationInfo a = it.app;
        final File[] out = new File[1];
        runOp("APK kopyalanıyor", (pr, c) -> {
            File src = new File(a.sourceDir);
            String name = (it.title + " " + a.packageName).replaceAll("[\\\\/:*?\"<>|]", "_") + ".apk";
            File dir = share ? new File(getCacheDir(), "acilan")
                    : new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "APK");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Klasör oluşturulamadı");
            LocalFs fs = new LocalFs("x", dir);
            try (InputStream in = new FileInputStream(src)) {
                VNode made = fs.create(fs.root(), name, in, src.length(), pr, c);
                out[0] = fs.localFile(made);
            }
            boolean split = a.splitSourceDirs != null && a.splitSourceDirs.length > 0;
            return share ? null : "Kaydedildi: İndirilenler/APK/" + out[0].getName()
                    + (split ? "\n(Bu uygulama parçalı APK kullanıyor; yalnızca ana parça kaydedildi)" : "");
        }, () -> {
            if (share && out[0] != null) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                        .putExtra(Intent.EXTRA_STREAM, uriFor(out[0]))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "APK paylaş"));
            }
        });
    }

    // ---------------------------------------------------------------- FTP

    private List<FtpFs.Server> ftpServers() {
        List<FtpFs.Server> out = new ArrayList<>();
        String s = prefs.getString("ftp", "");
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) continue;
            FtpFs.Server sv = new FtpFs.Server();
            sv.name = f[0];
            sv.host = f[1];
            try {
                sv.port = Integer.parseInt(f[2]);
            } catch (NumberFormatException e) {
                sv.port = 21;
            }
            sv.user = f[3];
            sv.pass = new String(Base64.decode(f[4], Base64.NO_WRAP));
            sv.path = f[5];
            out.add(sv);
        }
        return out;
    }

    private void saveFtp(List<FtpFs.Server> list) {
        StringBuilder sb = new StringBuilder();
        for (FtpFs.Server s : list) {
            sb.append(s.name.replace("\t", " ")).append('\t').append(s.host.trim()).append('\t')
                    .append(s.port).append('\t').append(s.user.replace("\t", " ")).append('\t')
                    .append(Base64.encodeToString(s.pass.getBytes(), Base64.NO_WRAP)).append('\t')
                    .append(s.path.replace("\t", " ")).append('\n');
        }
        prefs.edit().putString("ftp", sb.toString()).apply();
    }

    private void showFtpList(final Pane p) {
        p.mode = "ftp";
        p.setHeader("FTP sunucuları", "FTP");
        List<Item> items = new ArrayList<>();
        final List<FtpFs.Server> list = ftpServers();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final FtpFs.Server s = list.get(i);
            Item it = item("🌐", s.name, s.user + "@" + s.host + ":" + s.port + s.path, () -> {
                FtpFs fs = new FtpFs(s);
                p.openDir(fs.root());
            });
            it.longClick = () -> new AlertDialog.Builder(this).setTitle(s.name)
                    .setItems(new String[]{"Düzenle", "Sil"}, (d, w) -> {
                        if (w == 0) {
                            editFtp(p, idx);
                        } else {
                            list.remove(idx);
                            saveFtp(list);
                            showFtpList(p);
                        }
                    }).show();
            items.add(it);
        }
        items.add(item("➕", "Sunucu ekle", "Bilgisayar, modem ya da NAS'taki FTP sunucusu",
                () -> editFtp(p, -1)));
        p.show(items);
    }

    private void editFtp(final Pane p, final int idx) {
        final List<FtpFs.Server> list = ftpServers();
        final FtpFs.Server s = idx >= 0 ? list.get(idx) : new FtpFs.Server();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText name = input(idx >= 0 ? s.name : "", "Ad (ör. Bilgisayarım)");
        final EditText host = input(idx >= 0 ? s.host : "", "Adres (ör. 192.168.1.10)");
        final EditText port = input(String.valueOf(s.port), "Port");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText user = input(s.user, "Kullanıcı adı");
        final EditText pass = input(s.pass, "Parola");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText path = input(s.path, "Başlangıç klasörü");
        for (View v : new View[]{name, host, port, user, pass, path}) box.addView(v);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(idx >= 0 ? "Sunucuyu düzenle" : "FTP sunucusu ekle")
                .setView(sv)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaydet", (d, w) -> {
                    s.host = host.getText().toString().trim();
                    if (s.host.isEmpty()) {
                        toast("Adres gerekli");
                        return;
                    }
                    s.name = name.getText().toString().trim().isEmpty() ? s.host
                            : name.getText().toString().trim();
                    try {
                        s.port = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        s.port = 21;
                    }
                    s.user = user.getText().toString().trim().isEmpty() ? "anonymous"
                            : user.getText().toString().trim();
                    s.pass = pass.getText().toString();
                    s.path = path.getText().toString().trim().isEmpty() ? "/"
                            : path.getText().toString().trim();
                    if (idx >= 0) list.set(idx, s); else list.add(s);
                    saveFtp(list);
                    showFtpList(p);
                }).show();
    }

    // ---------------------------------------------------------------- WiFi paylaşımı

    private void toggleWifi() {
        if (wifi != null) {
            wifi.stop();
            wifi = null;
            toast("WiFi paylaşımı kapatıldı");
            refreshHome();
            return;
        }
        if (!hasStorageAccess()) {
            askStorageAccess();
            return;
        }
        String ip = WifiShare.address();
        if (ip == null) {
            toast("WiFi'ye bağlı değilsiniz (ya da erişim noktası kapalı)");
            return;
        }
        wifiCode = String.valueOf(1000 + new java.security.SecureRandom().nextInt(9000));
        try {
            wifi = WifiShare.start(internal.rootFile(), wifiCode);
        } catch (IOException e) {
            toast("Sunucu başlatılamadı: " + e.getMessage());
            return;
        }
        refreshHome();
        new AlertDialog.Builder(this).setTitle("WiFi dosya paylaşımı açık")
                .setMessage("Aynı WiFi'deki bilgisayarda tarayıcıyı açıp şu adrese gidin:\n\n"
                        + "http://" + ip + ":" + wifi.port + "\n\nKod: " + wifiCode
                        + "\n\nDosya indirip yükleyebilirsiniz. Uygulama açıkken çalışır; işiniz "
                        + "bitince kapatın.")
                .setPositiveButton("Tamam", null).show();
    }

    // ---------------------------------------------------------------- Kasa

    private void openVault(final Pane p) {
        final File dir = new File(getFilesDir(), "kasa");
        final String saltB = prefs.getString("vaultSalt", null);
        final String verB = prefs.getString("vaultVer", null);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText pass = input("", "Parola");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);
        final EditText pass2 = input("", "Parola (tekrar)");
        pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean setup = saltB == null || verB == null;
        if (setup) {
            box.addView(pass2);
            TextView note = label("Parolayı unutursanız kasadaki dosyalar açılamaz.", 13, 0xFF888888);
            box.addView(note);
        }
        new AlertDialog.Builder(this).setTitle(setup ? "Kasa parolası belirle" : "Kasayı aç")
                .setView(box)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton(setup ? "Oluştur" : "Aç", (d, w) -> {
                    final String pw = pass.getText().toString();
                    if (pw.length() < 4) {
                        toast("Parola en az 4 karakter olmalı");
                        return;
                    }
                    if (setup && !pw.equals(pass2.getText().toString())) {
                        toast("Parolalar aynı değil");
                        return;
                    }
                    final VaultFs[] opened = new VaultFs[1];
                    runOp("Kasa açılıyor", (pr, c) -> {
                        byte[] salt;
                        byte[] ver;
                        if (setup) {
                            byte[][] sv = VaultFs.setup(pw);
                            salt = sv[0];
                            ver = sv[1];
                            prefs.edit().putString("vaultSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
                                    .putString("vaultVer", Base64.encodeToString(ver, Base64.NO_WRAP))
                                    .apply();
                        } else {
                            salt = Base64.decode(saltB, Base64.NO_WRAP);
                            ver = Base64.decode(verB, Base64.NO_WRAP);
                        }
                        opened[0] = VaultFs.unlock(dir, pw, salt, ver);
                        return opened[0] == null ? "Parola yanlış" : null;
                    }, () -> {
                        if (opened[0] == null) return;
                        vault = opened[0];
                        refreshHome();
                        p.openDir(vault.root());
                        toast("Kasa açık. Dosya eklemek için diğer panelden kopyalayın ya da taşıyın.");
                    });
                }).show();
    }

    private void lockVault() {
        if (vault == null) return;
        for (Pane p : panes) if (p.fs == vault) p.showHome();
        vault.close();
        vault = null;
        clearOpenCache();
        refreshHome();
        toast("Kasa kilitlendi");
    }
}

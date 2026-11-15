package uz.fastbest.kartformat;

import android.Manifest;
import android.animation.ValueAnimator;
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.animation.DecelerateInterpolator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** X-plore benzeri ağaç görünümlü, iki panelli dosya yöneticisi. */
public class ExplorerActivity extends Activity {

    // ---------------------------------------------------------------- görünüm sabitleri

    private static final int BG = 0xFF2A2B2F;
    private static final int BG_DIM = 0xFF232427;
    private static final int TOP = 0xFF151619;
    private static final int TOOL_BG = 0xFF1C1D20;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFFA4A9B1;
    private static final int ACCENT = 0xFF29B6F6;
    private static final int ORANGE = 0xFFFF9800;
    private static final int SELECT = 0x553D8BD6;
    private static final int BAR = 0xFF4DB6AC;
    private static final int BAR_BG = 0xFF44474D;
    private static final int CHIP = 0xFF1E5BB8;

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
    private static final Set<String> DOCS = set("doc", "docx", "odt", "rtf");
    private static final Set<String> SHEETS = set("xls", "xlsx", "ods");

    private static Set<String> set(String... s) {
        Set<String> out = new HashSet<>();
        Collections.addAll(out, s);
        return out;
    }

    // ---------------------------------------------------------------- ağaç düğümü

    static final int K_VOLUME = 0;
    static final int K_DIR = 1;
    static final int K_FILE = 2;
    static final int K_GROUP = 3;
    static final int K_ACTION = 4;
    static final int K_APP = 5;
    static final int K_INFO = 6;

    static final class TNode {
        int kind;
        int depth;
        TNode parent;
        boolean expanded;
        List<TNode> children = new ArrayList<>();
        String key;
        String title;
        String sub;
        String path;
        String right1;
        String right2;
        float bar = -1;
        int icon;
        Drawable drawable;
        VNode node;
        ApplicationInfo app;
        Runnable action;
        Runnable onLong;
        String group;
        boolean temp;
        int dirs = -1;
        int files = -1;

        boolean expandable() {
            return kind == K_VOLUME || kind == K_DIR || kind == K_GROUP;
        }

        boolean fileish() {
            return (kind == K_DIR || kind == K_FILE) && node != null;
        }
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

    private FrameLayout paneRow;
    private FrameLayout colWrap;
    private FrameLayout stripHolder;
    private LinearLayout statusBar;
    private ProgressBar statusProgress;
    private TextView statusText;
    private TextView statusCancel;
    private final List<View[]> toolViews = new ArrayList<>();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (UsbSession.ACTION_PERMISSION.equals(a)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openUsb();
                } else {
                    toast("USB izni verilmedi");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                cancelled.set(true);
                io.execute(() -> usb.close());
                refreshRoots();
                toast("Kart okuyucu çıkarıldı");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                refreshRoots();
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

        refreshRoots();
        // Açılışta dahili depolama sol panelde açık gelsin
        Pane p = panes[0];
        if (hasStorageAccess()) {
            for (TNode r : p.roots) {
                if (("vol:" + internal.rootFile().getAbsolutePath()).equals(r.key)) {
                    p.current = r;
                    expand(p, r);
                }
            }
        }
        setActive(0);
        if (!hasStorageAccess()) askStorageAccess();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refreshRoots();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRoots();
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
            p.refresh();
            return;
        }
        if (!goUp(p)) super.onBackPressed();
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
            otherVolumes.add(new LocalFs("SD kart", root));
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
        refreshRoots();
    }

    // ---------------------------------------------------------------- arayüz iskeleti

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private TextView label(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private ImageView iconView(int res, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private static GradientDrawable round(int fill, int stroke, float strokePx, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (stroke != 0) g.setStroke((int) strokePx, stroke);
        return g;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(TOP);
        getWindow().setNavigationBarColor(TOP);

        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(BG);
        rootView.setFitsSystemWindows(true);

        // Üst çubuk
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(TOP);
        top.setPadding(dp(16), 0, dp(4), 0);
        top.setMinimumHeight(dp(52));
        TextView title = label("Kart Format", 20, TEXT);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(topButton(R.drawable.ic_m_search, v -> askSearch()));
        top.addView(topButton(R.drawable.ic_m_sort, v -> askSort()));
        top.addView(topButton(R.drawable.ic_m_menu, v -> showMenu()));
        rootView.addView(top);

        // Paneller + araç çubuğu: araç çubuğu sütunu etkin panelin yanında durur
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        FrameLayout body = new FrameLayout(this) {
            float dx0, dy0, pos0;
            boolean swiping;
            android.view.VelocityTracker vt;

            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
                if (landscape()) return false;
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dx0 = e.getX();
                        dy0 = e.getY();
                        swiping = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - dx0, dy = e.getY() - dy0;
                        if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                            swiping = true;
                            dx0 = e.getX();
                            if (paneAnim != null) paneAnim.cancel();
                            pos0 = panePos;
                            vt = android.view.VelocityTracker.obtain();
                            vt.addMovement(e);
                            return true;
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) {
                if (!swiping) return true;
                if (vt != null) vt.addMovement(e);
                int a = e.getActionMasked();
                float span = Math.max(1, getWidth() - dp(60));
                if (a == android.view.MotionEvent.ACTION_MOVE) {
                    panePos = Math.max(0f, Math.min(1f, pos0 - (e.getX() - dx0) / span));
                    applyPanePos(panePos);
                } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    float v = 0;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        v = vt.getXVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    int target;
                    if (Math.abs(v) > dp(600)) target = v < 0 ? 1 : 0;
                    else target = panePos >= 0.5f ? 1 : 0;
                    swiping = false;
                    animatePaneTo(target);
                }
                return true;
            }
        };
        body.setBackgroundColor(0xFF131416);
        paneRow = body;
        panes[0] = new Pane(0);
        panes[1] = new Pane(1);
        body.addView(panes[0].box, new FrameLayout.LayoutParams(dp(100),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        body.addView(panes[1].box, new FrameLayout.LayoutParams(dp(100),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setBackgroundColor(TOOL_BG);
        stripHolder = new FrameLayout(this);
        for (int i = 0; i < 2; i++) {
            stripHolder.addView(panes[i].strip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        rightCol.addView(stripHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));
        rightCol.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        colWrap = new FrameLayout(this);
        colWrap.setBackgroundColor(0xFF131416);
        colWrap.addView(rightCol, new FrameLayout.LayoutParams(dp(58),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        body.addView(colWrap, new FrameLayout.LayoutParams(dp(60),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        rootView.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Alt işlem çubuğu
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.VERTICAL);
        statusBar.setBackgroundColor(TOP);
        statusBar.setPadding(dp(14), dp(8), dp(10), dp(8));
        statusBar.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusText = label("", 14, TEXT);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusCancel = label("İptal", 15, 0xFFFF7A7A);
        statusCancel.setPadding(dp(14), dp(6), dp(8), dp(6));
        statusCancel.setOnClickListener(v -> {
            cancelled.set(true);
            statusCancel.setEnabled(false);
            statusCancel.setText("İptal ediliyor…");
        });
        row.addView(statusCancel);
        statusBar.addView(row);
        statusProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        statusProgress.setMax(1000);
        statusProgress.setProgressDrawable(barDrawable());
        statusBar.addView(statusProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        rootView.addView(statusBar);

        setContentView(rootView);
        layoutPanes();
    }

    private View topButton(int res, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setPadding(dp(13), dp(13), dp(13), dp(13));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        b.setOnClickListener(l);
        b.setBackgroundResource(themeRes(android.R.attr.selectableItemBackgroundBorderless));
        return b;
    }

    private int themeRes(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    /** Teal doluluk çubuğu. */
    private Drawable barDrawable() {
        GradientDrawable bg = round(BAR_BG, 0, 0, dp(2));
        GradientDrawable fg = round(BAR, 0, 0, dp(2));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg,
                new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private static final int T_UP = 0;
    private static final int T_NEWFOLDER = 1;
    private static final int T_RENAME = 2;
    private static final int T_COPY = 3;
    private static final int T_MOVE = 4;
    private static final int T_DELETE = 5;
    private static final int T_ZIP = 6;
    private static final int T_EXTRACT = 7;
    private static final int T_SHARE = 8;
    private static final int T_OPENWITH = 9;
    private static final int T_INFO = 10;
    private static final int T_SELECTALL = 11;
    private static final int T_ANALYSIS = 12;
    private static final int T_STAR = 13;
    private static final int T_REFRESH = 14;

    private View buildToolbar() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(TOOL_BG);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        Object[][] tools = {
                {R.drawable.ic_t_up, "Üst klasör", (Runnable) () -> goUp(panes[active])},
                {R.drawable.ic_t_newfolder, "Yeni klasör", (Runnable) this::askNewFolder},
                {R.drawable.ic_t_rename, "Ad değiştir", (Runnable) this::askRename},
                {R.drawable.ic_t_copy, "Kopyala", (Runnable) () -> copySelected(false)},
                {R.drawable.ic_t_move, "Taşı", (Runnable) () -> copySelected(true)},
                {R.drawable.ic_t_delete, "Sil", (Runnable) this::askDelete},
                {R.drawable.ic_t_zip, "ZIP yap", (Runnable) this::askZip},
                {R.drawable.ic_t_extract, "Arşivi aç", (Runnable) this::extractSelected},
                {R.drawable.ic_t_share, "Paylaş", (Runnable) this::shareSelected},
                {R.drawable.ic_t_openwith, "Birlikte aç", (Runnable) this::openWithSelected},
                {R.drawable.ic_t_info, "Özellikler", (Runnable) this::showProperties},
                {R.drawable.ic_t_selectall, "Tümünü seç", (Runnable) this::selectAll},
                {R.drawable.ic_t_analysis, "Disk analizi", (Runnable) this::analyzeCurrent},
                {R.drawable.ic_t_star, "Sık kullan.", (Runnable) this::addFavorite},
                {R.drawable.ic_t_refresh, "Yenile", (Runnable) () -> reloadPane(panes[active])},
        };
        for (Object[] t : tools) {
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(1), dp(7), dp(1), dp(6));
            b.setBackgroundResource(themeRes(android.R.attr.selectableItemBackground));
            ImageView icon = iconView((Integer) t[0], 28);
            TextView name = label((String) t[1], 9.5f, 0xFFD0D3D8);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setPadding(0, dp(3), 0, 0);
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
            View line = new View(this);
            line.setBackgroundColor(0xFF2A2C30);
            col.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            toolViews.add(new View[]{b, icon, name});
        }
        sv.addView(col);
        return sv;
    }

    /** Uygulanamayan araçları soluk gösterir. */
    private void updateToolbar() {
        Pane p = panes[active];
        if (p == null || panes[1 - active] == null) return;
        List<VNode> targets = targets(p);
        VNode dir = dirOf(p.current);
        boolean any = !targets.isEmpty();
        boolean single = targets.size() == 1;
        boolean singleFile = single && !targets.get(0).dir;
        boolean writable = any && targets.get(0).fs.writable();
        VNode otherDir = dirOf(other().current);
        boolean[] on = new boolean[15];
        on[T_UP] = p.current != null && (p.current.parent != null || p.current.expanded);
        on[T_NEWFOLDER] = dir != null && dir.fs.writable();
        on[T_RENAME] = single && writable;
        on[T_COPY] = any && otherDir != null;
        on[T_MOVE] = any && writable && otherDir != null;
        on[T_DELETE] = any && writable;
        on[T_ZIP] = any;
        on[T_EXTRACT] = singleFile && ARCHIVES.contains(targets.get(0).ext());
        on[T_SHARE] = any;
        on[T_OPENWITH] = singleFile;
        on[T_INFO] = any || dir != null;
        on[T_SELECTALL] = dir != null;
        on[T_ANALYSIS] = dir != null;
        on[T_STAR] = dir != null && dir.fs instanceof LocalFs;
        on[T_REFRESH] = true;
        for (int i = 0; i < toolViews.size() && i < on.length; i++) {
            toolViews.get(i)[1].setAlpha(on[i] ? 1f : 0.28f);
            toolViews.get(i)[2].setAlpha(on[i] ? 1f : 0.4f);
        }
    }

    private float panePos;            // 0 = sol panel açık, 1 = sağ panel açık
    private ValueAnimator paneAnim;
    private boolean paneRowHooked;

    private void layoutPanes() {
        if (panes[0] == null) return;
        boolean land = landscape();
        if (paneAnim != null) paneAnim.cancel();
        if (!paneRowHooked) {
            paneRowHooked = true;
            paneRow.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                if (r - l != or - ol) applyPanePos(panePos);
            });
        }
        for (int i = 0; i < 2; i++) {
            Pane p = panes[i];
            p.chip.setBackground(round(i == active ? CHIP : 0xFF3A3D44, 0, 0, dp(7)));
            p.box.setBackgroundColor(i == active || !land ? BG : BG_DIM);
            p.list.setAlpha(i == active || !land ? 1f : 0.82f);
        }
        stripHolder.setVisibility(land ? View.GONE : View.VISIBLE);
        panePos = land ? 1 : active;
        applyPanePos(panePos);
        updateToolbar();
    }

    private void place(View v, int x, int w) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        w = Math.max(w, 0);
        if (lp.leftMargin != x || lp.width != w) {
            lp.leftMargin = x;
            lp.width = w;
            v.setLayoutParams(lp);
        }
        v.setVisibility(w <= 0 ? View.GONE : View.VISIBLE);
    }

    /** Panelleri ve araç çubuğu sütununu yerleştirir. pos: 0 = sol panel etkin, 1 = sağ panel etkin */
    private void applyPanePos(float pos) {
        int w = paneRow.getWidth();
        if (w <= 0) {
            paneRow.post(() -> applyPanePos(panePos));
            return;
        }
        int cw = dp(60);
        int aw = w - cw;
        if (landscape()) {
            int half = (aw - 2) / 2;
            place(panes[0].box, 0, half);
            place(panes[1].box, half + 2, aw - half - 2);
            place(colWrap, aw, cw);
            for (int i = 0; i < 2; i++) {
                Pane p = panes[i];
                FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) p.content.getLayoutParams();
                if (cp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    cp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    p.content.setLayoutParams(cp);
                }
                p.content.setTranslationX(0);
                p.content.setAlpha(1f);
                p.strip.setVisibility(View.GONE);
            }
            return;
        }
        int colX = Math.round(aw * (1 - pos));
        place(panes[0].box, 0, colX);
        place(colWrap, colX, cw);
        place(panes[1].box, colX + cw, w - colX - cw);
        for (int i = 0; i < 2; i++) {
            Pane p = panes[i];
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) p.content.getLayoutParams();
            if (cp.width != aw) { cp.width = aw; p.content.setLayoutParams(cp); }
            p.content.setTranslationX(i == 0 ? colX - aw : 0);
            float stripA = i == 0 ? pos : 1 - pos;
            p.strip.setAlpha(stripA);
            p.strip.setVisibility(stripA <= 0.01f ? View.GONE : View.VISIBLE);
            float act = i == 0 ? 1 - pos : pos;
            p.content.setAlpha(0.45f + 0.55f * act);
        }
    }

    private void setActive(int i) {
        if (active == i && (landscape() || Math.abs(panePos - i) < 0.001f)) {
            updateToolbar();
            return;
        }
        if (landscape() || panes[0] == null || paneRow.getWidth() <= 0) {
            active = i;
            layoutPanes();
            return;
        }
        animatePaneTo(i);
    }

    /** Dikey düzende paneli hedef tarafa yumuşakça oturtur. */
    private void animatePaneTo(final int target) {
        active = target;
        for (int k = 0; k < 2; k++) {
            Pane p = panes[k];
            p.chip.setBackground(round(k == active ? CHIP : 0xFF3A3D44, 0, 0, dp(7)));
            p.list.setAlpha(1f);
        }
        if (paneAnim != null) paneAnim.cancel();
        float dist = Math.abs(panePos - target);
        paneAnim = ValueAnimator.ofFloat(panePos, target);
        paneAnim.setDuration((long) Math.max(90, 260 * dist));
        paneAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        paneAnim.addUpdateListener(a -> {
            panePos = (Float) a.getAnimatedValue();
            applyPanePos(panePos);
        });
        paneAnim.start();
        updateToolbar();
    }

    private void toast(String s) {
        Toast.makeText(this, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private Pane other() {
        return panes[1 - active];
    }

    // ---------------------------------------------------------------- panel

    final class Pane {
        final int index;
        final FrameLayout box;
        final LinearLayout content;
        final TextView chip;
        final LinearLayout strip;
        final VerticalLabel stripLabel;
        final ImageView stripIcon;
        final RecyclerView list;
        final LinearLayoutManager lm;
        final TreeAdapter adapter;
        final List<TNode> roots = new ArrayList<>();
        final List<TNode> visible = new ArrayList<>();
        final Set<TNode> selected = new LinkedHashSet<>();
        TNode current;

        Pane(int index) {
            this.index = index;
            box = new FrameLayout(ExplorerActivity.this);

            content = new LinearLayout(ExplorerActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            LinearLayout chipRow = new LinearLayout(ExplorerActivity.this);
            chipRow.setPadding(dp(6), dp(6), dp(6), dp(4));
            chip = label("", 13.5f, Color.WHITE);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setPadding(dp(8), dp(5), dp(12), dp(5));
            chip.setCompoundDrawablePadding(dp(6));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setOnClickListener(v -> {
                setActive(index);
                if (current != null) {
                    int pos = visible.indexOf(current);
                    if (pos >= 0) Pane.this.scrollTo(Math.max(0, pos - 2));
                }
            });
            chipRow.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(chipRow);
            list = new RecyclerView(ExplorerActivity.this);
            lm = new LinearLayoutManager(ExplorerActivity.this);
            list.setLayoutManager(lm);
            list.setVerticalScrollBarEnabled(true);
            list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            DefaultItemAnimator anim = new DefaultItemAnimator();
            anim.setAddDuration(180);
            anim.setRemoveDuration(150);
            anim.setMoveDuration(180);
            anim.setChangeDuration(0);
            anim.setSupportsChangeAnimations(false);
            list.setItemAnimator(anim);
            adapter = new TreeAdapter(this);
            list.setAdapter(adapter);
            content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

            strip = new LinearLayout(ExplorerActivity.this);
            strip.setOrientation(LinearLayout.VERTICAL);
            strip.setGravity(Gravity.CENTER_HORIZONTAL);
            strip.setBackgroundColor(0xFF26272B);
            strip.setPadding(0, dp(6), 0, dp(6));
            stripIcon = iconView(R.drawable.ic_phone, 26);
            strip.addView(stripIcon);
            ImageView arrow = iconView(R.drawable.ic_tree_closed, 18);
            arrow.setRotation(index == 0 ? 180 : 0);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(18), dp(18));
            alp.topMargin = dp(3);
            strip.addView(arrow, alp);
            stripLabel = new VerticalLabel(ExplorerActivity.this, dp(14), 0xFFE0E3E8);
            stripLabel.setPadding(0, dp(4), 0, dp(4));
            strip.addView(stripLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            strip.setOnClickListener(v -> setActive(index));
            strip.setClickable(true);
        }

        /** Görünen listeyi ağaçtan yeniden üretir. */
        void refresh() {
            final List<TNode> old = new ArrayList<>(visible);
            visible.clear();
            for (TNode r : roots) flatten(r, visible);
            if (current != null && !visible.contains(current)) current = null;
            selected.retainAll(new HashSet<>(visible));
            if (old.isEmpty() || old.size() + visible.size() > 4000 || !list.isLaidOut()) {
                adapter.notifyDataSetChanged();
            } else {
                final List<TNode> now = visible;
                DiffUtil.calculateDiff(new DiffUtil.Callback() {
                    @Override public int getOldListSize() { return old.size(); }
                    @Override public int getNewListSize() { return now.size(); }
                    @Override public boolean areItemsTheSame(int o, int n) { return old.get(o) == now.get(n); }
                    @Override public boolean areContentsTheSame(int o, int n) { return false; }
                    @Override public Object getChangePayload(int o, int n) { return "bind"; }
                }, false).dispatchUpdatesTo(adapter);
            }
            updateChip();
            if (index == active) updateToolbar();
        }

        void scrollTo(int pos) {
            lm.scrollToPositionWithOffset(Math.max(0, pos), 0);
        }

        private void flatten(TNode n, List<TNode> out) {
            out.add(n);
            if (n.expanded) for (TNode c : n.children) flatten(c, out);
        }

        void updateChip() {
            TNode root = current;
            while (root != null && root.parent != null) root = root.parent;
            String t = root != null ? root.title : "Kart Format";
            int icon = root != null && root.icon != 0 ? root.icon : R.drawable.ic_phone;
            String where = "";
            if (current != null && current != root && current.node != null) {
                where = "  ›  " + (current.kind == K_FILE && current.parent != null
                        ? current.parent.title : current.title);
            }
            chip.setText(t + where);
            Drawable d = getDrawable(icon);
            if (d != null) d.setBounds(0, 0, dp(18), dp(18));
            chip.setCompoundDrawables(d, null, null, null);
            stripIcon.setImageResource(icon);
            String label = current == null ? t
                    : current.kind == K_FILE && current.parent != null ? current.parent.title
                    : current.title;
            stripLabel.setText(label);
        }
    }

    // ---------------------------------------------------------------- satır çizimi

    static final class Holder extends RecyclerView.ViewHolder {
        final Row row;

        Holder(Row row) {
            super(row.root);
            this.row = row;
        }
    }

    final class TreeAdapter extends RecyclerView.Adapter<Holder> {
        private final Pane pane;

        TreeAdapter(Pane pane) {
            this.pane = pane;
            setHasStableIds(false);
        }

        @Override
        public int getItemCount() {
            return pane.visible.size();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Row r = new Row(pane);
            r.root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(r);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            h.row.bind(pane.visible.get(position));
        }
    }

    /** Ağaç bağlantı çizgilerini ve açık klasör çerçevesini (X-plore gibi) çizen satır kapsayıcısı. */
    final class GuideCard extends LinearLayout {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private TNode node, cur;
        private boolean inFrame, frameFirst, frameLast;

        GuideCard(Context c) {
            super(c);
            setWillNotDraw(false);
            line.setColor(0xFFA8733C);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.2f));
            line.setStrokeCap(Paint.Cap.ROUND);
            frame.setColor(ORANGE);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(dp(2));
        }

        void set(TNode n, TNode current, boolean in, boolean first, boolean last) {
            node = n;
            cur = current;
            inFrame = in;
            frameFirst = first;
            frameLast = last;
            invalidate();
        }

        private boolean isLast(TNode n) {
            TNode p = n.parent;
            return p == null || p.children.isEmpty() || p.children.get(p.children.size() - 1) == n;
        }

        /** a, x'in atası ya da kendisi mi? */
        private boolean ancOrSelf(TNode a, TNode x) {
            for (TNode t = x; t != null; t = t.parent) if (t == a) return true;
            return false;
        }

        private TNode chainChild(TNode p) {
            for (TNode t = cur; t != null; t = t.parent) if (t.parent == p) return t;
            return null;
        }

        private float cx(int k) {
            return dp(3) + dp(14) * k + dp(8);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            TNode n = node;
            if (n == null) return;
            int h = getHeight(), w = getWidth();
            float mid = h / 2f;
            float r = dp(5);
            // dikey geçiş çizgileri
            for (TNode a = n; a.parent != null; a = a.parent) {
                TNode p = a.parent;
                if (!p.expanded) continue;
                float x = cx(a.depth - 1);
                if (ancOrSelf(cur, p)) {            // p, seçili klasörün içinde: tüm çocuklar çizilir
                    if (a != n && !isLast(a)) cv.drawLine(x, 0, x, h, line);
                } else if (cur != null && ancOrSelf(p, cur)) {   // p, seçili klasörün atası
                    TNode c = chainChild(p);
                    if (c != null && a != c && p.children.indexOf(a) < p.children.indexOf(c)) {
                        cv.drawLine(x, 0, x, h, line);
                    }
                }
            }
            // kendi satırının dirseği
            TNode p = n.parent;
            if (p != null && p.expanded && n.depth >= 1) {
                boolean elbow = false, last = false;
                if (cur != null && ancOrSelf(cur, p)) {
                    elbow = true;
                    last = isLast(n);
                } else if (cur != null && ancOrSelf(p, cur)) {
                    elbow = chainChild(p) == n;
                    last = true;
                }
                if (elbow) {
                    float x = cx(n.depth - 1);
                    float endX = dp(3) + dp(14) * n.depth;
                    if (!last) cv.drawLine(x, 0, x, h, line);
                    else cv.drawLine(x, 0, x, mid - r, line);
                    path.reset();
                    path.moveTo(x, mid - r);
                    path.quadTo(x, mid, x + r, mid);
                    path.lineTo(endX, mid);
                    cv.drawPath(path, line);
                }
            }
            // açık klasörün ok altından aşağı inen çizgi
            if (n.expanded && !n.children.isEmpty() && cur != null
                    && (ancOrSelf(cur, n) || ancOrSelf(n, cur))) {
                float x = cx(n.depth);
                cv.drawLine(x, mid + dp(8), x, h, line);
            }
            // açık klasör çerçevesi
            if (inFrame) {
                float o = dp(1);
                cv.drawLine(o, 0, o, h, frame);
                cv.drawLine(w - o, 0, w - o, h, frame);
                if (frameFirst) cv.drawLine(0, o, w, o, frame);
                if (frameLast) cv.drawLine(0, h - o, w, h - o, frame);
            }
        }
    }

    final class Row {
        final Pane pane;
        final LinearLayout root;
        final GuideCard card;
        final View indent;
        final ImageView arrow;
        final ImageView icon;
        final TextView title;
        final TextView sub;
        final TextView path;
        final LinearLayout right;
        final TextView right1;
        final TextView right2;
        final ProgressBar bar;
        final ImageView check;
        final LinearLayout counts;
        final TextView countDirs;
        final TextView countFiles;
        final ProgressBar spinner;
        TNode node;

        Row(Pane pane) {
            this.pane = pane;
            root = new LinearLayout(ExplorerActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(3), 0, dp(3), 0);

            card = new GuideCard(ExplorerActivity.this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(3), dp(4), dp(6), dp(4));
            card.setMinimumHeight(dp(40));
            root.addView(card);
            View divider = new View(ExplorerActivity.this);
            divider.setBackgroundColor(0xFF45484F);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dlp.leftMargin = dp(8);
            root.addView(divider, dlp);

            indent = new View(ExplorerActivity.this);
            card.addView(indent, new LinearLayout.LayoutParams(0, 1));
            arrow = new ImageView(ExplorerActivity.this);
            card.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
            arrow.setOnClickListener(v -> {
                if (node != null && node.expandable() && !busy) {
                    setActive(pane.index);
                    pane.current = node;
                    toggle(pane, node);
                }
            });

            FrameLayout iconBox = new FrameLayout(ExplorerActivity.this);
            icon = new ImageView(ExplorerActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconBox.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            spinner = new ProgressBar(ExplorerActivity.this, null, android.R.attr.progressBarStyleSmall);
            iconBox.addView(spinner, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            card.addView(iconBox, new LinearLayout.LayoutParams(dp(30), dp(30)));

            LinearLayout texts = new LinearLayout(ExplorerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(9), 0, dp(6), 0);
            title = label("", 14, TEXT);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            sub = label("", 11, MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            path = label("", 11, MUTED);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.START);
            counts = new LinearLayout(ExplorerActivity.this);
            counts.setGravity(Gravity.CENTER_VERTICAL);
            counts.addView(iconView(R.drawable.ic_meta_folder, 12));
            countDirs = label("", 11, MUTED);
            countDirs.setPadding(dp(4), 0, dp(14), 0);
            counts.addView(countDirs);
            counts.addView(iconView(R.drawable.ic_meta_file, 12));
            countFiles = label("", 11, MUTED);
            countFiles.setPadding(dp(4), 0, 0, 0);
            counts.addView(countFiles);
            texts.addView(title);
            texts.addView(counts);
            texts.addView(sub);
            texts.addView(path);
            card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            right = new LinearLayout(ExplorerActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right1 = label("", 11, MUTED);
            right1.setGravity(Gravity.END);
            right2 = label("", 11, 0xFFC9CDD3);
            right2.setGravity(Gravity.END);
            bar = new ProgressBar(ExplorerActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressDrawable(barDrawable());
            right.addView(right1);
            right.addView(right2);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(80), dp(4));
            bp.topMargin = dp(4);
            right.addView(bar, bp);
            card.addView(right);

            check = new ImageView(ExplorerActivity.this);
            check.setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(19), dp(19));
            cp.leftMargin = dp(6);
            card.addView(check, cp);
            check.setOnClickListener(v -> {
                if (node != null && node.fileish() && !busy) {
                    setActive(pane.index);
                    if (!pane.selected.remove(node)) pane.selected.add(node);
                    pane.refresh();
                }
            });

            root.setTag(this);
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                root.setBackgroundResource(tv.resourceId);
            }
            root.setOnClickListener(v -> {
                if (node != null && pane.visible.contains(node)) onTap(pane, node);
            });
            root.setOnLongClickListener(v -> {
                if (node != null && pane.visible.contains(node)) onLongTap(pane, node);
                return true;
            });
        }

        void bind(TNode n) {
            node = n;
            boolean isCurrent = n == pane.current;
            boolean sel = pane.selected.contains(n);
            card.setBackground(round(sel ? SELECT : Color.TRANSPARENT, 0, 0, 0));
            indent.setLayoutParams(new LinearLayout.LayoutParams(dp(14) * n.depth, 1));
            {
                TNode cur = pane.current;
                boolean in = false, first = false, last = false;
                if (cur != null) {
                    boolean group = cur.expanded && !cur.children.isEmpty();
                    if (n == cur) {
                        in = true;
                        first = true;
                        last = !group;
                    } else if (group) {
                        for (TNode t = n.parent; t != null; t = t.parent) if (t == cur) in = true;
                        if (in) {
                            int pos = pane.visible.indexOf(n);
                            TNode next = pos >= 0 && pos + 1 < pane.visible.size() ? pane.visible.get(pos + 1) : null;
                            boolean nextIn = false;
                            for (TNode t = next == null ? null : next.parent; t != null; t = t.parent) if (t == cur) nextIn = true;
                            last = !nextIn;
                        }
                    }
                }
                card.set(n, cur, in, first, last);
            }
            if (n.expandable()) {
                arrow.setVisibility(View.VISIBLE);
                arrow.setImageResource(n.expanded ? R.drawable.ic_tree_open : R.drawable.ic_tree_closed);
            } else {
                arrow.setVisibility(View.INVISIBLE);
            }
            spinner.setVisibility(n.kind == K_INFO && "loading".equals(n.group) ? View.VISIBLE : View.GONE);
            icon.setTag(null);
            if (n.drawable != null) {
                icon.setImageDrawable(n.drawable);
            } else if (n.icon != 0) {
                icon.setImageResource(n.icon);
            } else {
                icon.setImageDrawable(null);
            }
            if (n.kind == K_FILE && n.node != null && IMAGES.contains(n.node.ext())) {
                File f = n.node.fs.localFile(n.node);
                if (f != null) loadThumb(icon, f);
            }
            title.setText(n.title);
            title.setTextSize(n.depth == 0 ? 14.5f : 14f);
            title.setTypeface(n.kind == K_FILE || n.kind == K_INFO ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            title.setTextColor(n.kind == K_INFO ? MUTED : TEXT);

            boolean showCounts = n.expanded && n.dirs >= 0;
            counts.setVisibility(showCounts ? View.VISIBLE : View.GONE);
            if (showCounts) {
                countDirs.setText(String.valueOf(n.dirs));
                countFiles.setText(String.valueOf(n.files));
            }
            String s = showCounts ? null : n.sub;
            sub.setText(s == null ? "" : s);
            sub.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
            path.setText(n.path == null ? "" : n.path);
            path.setVisibility(n.path == null || n.path.isEmpty() ? View.GONE : View.VISIBLE);

            boolean hasRight = n.right1 != null || n.right2 != null || n.bar >= 0;
            right.setVisibility(hasRight ? View.VISIBLE : View.GONE);
            right1.setText(n.right1 == null ? "" : n.right1);
            right1.setVisibility(n.right1 == null ? View.GONE : View.VISIBLE);
            right2.setText(n.right2 == null ? "" : n.right2);
            right2.setVisibility(n.right2 == null ? View.GONE : View.VISIBLE);
            if (n.bar >= 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress((int) (Math.min(1f, n.bar) * 1000));
            } else {
                bar.setVisibility(View.GONE);
            }

            if (n.fileish()) {
                check.setVisibility(View.VISIBLE);
                if (sel) check.setImageResource(R.drawable.ic_check);
                else check.setImageDrawable(null);
                check.setBackground(sel ? round(ACCENT, 0, 0, dp(4))
                        : round(Color.TRANSPARENT, 0xFF5F646C, dp(1.5f), dp(4)));
            } else {
                check.setVisibility(View.GONE);
            }
            root.setPadding(dp(3), n.depth == 0 ? dp(3) : dp(1), dp(3), dp(1));
        }
    }

    private void loadThumb(final ImageView view, final File f) {
        final String key = f.getAbsolutePath() + ":" + f.lastModified();
        Bitmap b = thumbCache.get(key);
        if (b != null) {
            view.setImageBitmap(b);
            return;
        }
        view.setTag(key);
        try {
            thumbs.execute(() -> {
                Bitmap bm = decodeSampled(f, dp(38));
                if (bm == null) return;
                thumbCache.put(key, bm);
                main.post(() -> {
                    if (key.equals(view.getTag())) view.setImageBitmap(bm);
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
            Bitmap full = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (full == null) return null;
            int side = Math.min(full.getWidth(), full.getHeight());
            return Bitmap.createBitmap(full, (full.getWidth() - side) / 2,
                    (full.getHeight() - side) / 2, side, side);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- düğüm üretimi

    private int iconFor(VNode n) {
        if (n.dir) return R.drawable.ic_folder;
        String e = n.ext();
        if (IMAGES.contains(e)) return R.drawable.ic_file_image;
        if (VIDEO.contains(e)) return R.drawable.ic_file_video;
        if (AUDIO.contains(e)) return R.drawable.ic_file_audio;
        if (e.equals("apk")) return R.drawable.ic_file_apk;
        if (ARCHIVES.contains(e)) return R.drawable.ic_file_archive;
        if (e.equals("pdf")) return R.drawable.ic_file_pdf;
        if (TEXTS.contains(e)) return R.drawable.ic_file_text;
        if (DOCS.contains(e)) return R.drawable.ic_file_doc;
        if (SHEETS.contains(e)) return R.drawable.ic_file_sheet;
        return R.drawable.ic_file;
    }

    private static String date(long t) {
        return t > 0 ? new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date(t)) : null;
    }

    private TNode fileNode(TNode parent, VNode v) {
        TNode t = new TNode();
        t.kind = v.dir ? K_DIR : K_FILE;
        t.parent = parent;
        t.depth = parent == null ? 0 : parent.depth + 1;
        t.node = v;
        t.key = "f:" + v.path;
        t.title = v.name;
        t.icon = iconFor(v);
        String d = date(v.modified);
        t.sub = v.dir ? d : DiskOps.sizeText(Math.max(0, v.size)) + (d == null ? "" : "  •  " + d);
        return t;
    }

    private TNode special(String key, int kind, int icon, String title, String sub) {
        TNode t = new TNode();
        t.key = key;
        t.kind = kind;
        t.icon = icon;
        t.title = title;
        t.sub = sub;
        return t;
    }

    private TNode volumeNode(Vfs fs, int icon, String key, String pathText) {
        TNode t = special(key, K_VOLUME, icon, fs.title(), null);
        t.node = fs.root();
        t.path = pathText;
        long total = fs.totalBytes();
        long free = fs.freeBytes();
        if (total > 0 && free >= 0) {
            t.right1 = "Boş";
            t.right2 = shortSize(free) + "/" + shortSize(total);
            t.bar = 1f - (float) free / total;
        }
        return t;
    }

    private static String shortSize(long b) {
        if (b >= 1000L * 1000 * 1000) return String.format(Locale.ROOT, "%.0fGB", b / 1e9);
        if (b >= 1000L * 1000) return String.format(Locale.ROOT, "%.0fMB", b / 1e6);
        return String.format(Locale.ROOT, "%.0fKB", b / 1e3);
    }

    /** Kök listesini kurar; var olan düğümlerin açık/kapalı durumu korunur. */
    private List<TNode> buildRoots(Pane p) {
        List<TNode> out = new ArrayList<>();
        if (!hasStorageAccess()) {
            TNode t = special("perm", K_ACTION, R.drawable.ic_warning, "Dosya erişim izni ver",
                    "Telefondaki dosyaları görmek için gerekli");
            t.action = this::askStorageAccess;
            out.add(t);
        }
        for (TNode n : p.roots) if (n.temp) out.add(n);
        out.add(volumeNode(internal, R.drawable.ic_phone,
                "vol:" + internal.rootFile().getAbsolutePath(), internal.rootFile().getAbsolutePath()));
        for (LocalFs v : otherVolumes) {
            out.add(volumeNode(v, R.drawable.ic_sdcard, "vol:" + v.rootFile().getAbsolutePath(),
                    v.rootFile().getAbsolutePath()));
        }
        if (usb.isOpen() && !usb.volumes().isEmpty()) {
            for (CardFs c : usb.volumes()) {
                out.add(volumeNode(c, R.drawable.ic_usb, "usb:" + c.title(),
                        usb.readerName() + " • " + c.volume().type()));
            }
        } else if (usb.device() != null) {
            TNode t = special("usbopen", K_ACTION, R.drawable.ic_usb, "USB kart (OTG)",
                    usb.isOpen() && usb.error() != null ? usb.error() : "Açmak için dokunun");
            t.action = this::openUsb;
            out.add(t);
        }
        out.add(special("fav", K_GROUP, R.drawable.ic_star, "Sık kullanılanlar", null));
        out.add(special("recent", K_GROUP, R.drawable.ic_clock, "Son dosyalar", null));
        out.add(special("apps", K_GROUP, R.drawable.ic_apps, "Uygulama yöneticisi", null));
        out.add(special("ftp", K_GROUP, R.drawable.ic_network, "FTP sunucuları", null));
        TNode wifiNode = special("wifi", K_ACTION, R.drawable.ic_wifi, "WiFi dosya paylaşımı",
                wifi == null ? "Kapalı" : "http://" + WifiShare.address() + ":" + wifi.port);
        if (wifi != null) wifiNode.right2 = "Kod " + wifiCode;
        wifiNode.action = this::toggleWifi;
        out.add(wifiNode);
        if (vault == null) {
            TNode v = special("vault", K_ACTION, R.drawable.ic_vault, "Kasa", "Şifreli dosyalar");
            v.action = () -> openVault(p);
            out.add(v);
        } else {
            TNode v = volumeNode(vault, R.drawable.ic_vault_open, "vaultopen", "Açık • uzun bas: kilitle");
            v.title = "Kasa";
            v.onLong = this::lockVault;
            out.add(v);
        }
        TNode tools = special("tools", K_ACTION, R.drawable.ic_tools, "USB kart araçları",
                "Biçimlendir, imaj yaz, Windows / FreeDOS, PS2…");
        tools.action = this::openTools;
        out.add(tools);

        // Eski düğümlerin durumunu taşı
        Map<String, TNode> old = new HashMap<>();
        for (TNode n : p.roots) old.put(n.key, n);
        for (TNode n : out) {
            TNode o = old.get(n.key);
            if (o == null || o == n) continue;
            if (o.kind == n.kind && o.expanded) {
                n.expanded = true;
                n.children = o.children;
                n.dirs = o.dirs;
                n.files = o.files;
                for (TNode c : n.children) c.parent = n;
            }
            if (p.current == o) p.current = n;
            if (p.selected.remove(o)) p.selected.add(n);
        }
        return out;
    }

    private void refreshRoots() {
        for (Pane p : panes) {
            if (p == null) continue;
            List<TNode> r = buildRoots(p);
            p.roots.clear();
            p.roots.addAll(r);
            p.refresh();
        }
    }

    // ---------------------------------------------------------------- dokunma

    private void onTap(Pane p, TNode n) {
        setActive(p.index);
        if (busy) return;
        if (!p.selected.isEmpty() && n.fileish()) {
            if (!p.selected.remove(n)) p.selected.add(n);
            p.refresh();
            return;
        }
        if (n.kind == K_INFO) return;
        p.current = n;
        switch (n.kind) {
            case K_ACTION:
                p.refresh();
                if (n.action != null) n.action.run();
                break;
            case K_APP:
                p.refresh();
                appMenu(n);
                break;
            case K_FILE:
                p.refresh();
                openFile(n);
                break;
            default:
                toggle(p, n);
        }
    }

    private void onLongTap(Pane p, TNode n) {
        setActive(p.index);
        if (busy) return;
        if (n.onLong != null) {
            n.onLong.run();
            return;
        }
        if (n.temp && n.depth == 0) {
            p.roots.remove(n);
            p.refresh();
            return;
        }
        if (n.fileish()) {
            if (!p.selected.remove(n)) p.selected.add(n);
            p.refresh();
        }
    }

    private void toggle(Pane p, TNode n) {
        if (!n.expandable()) return;
        if (n.expanded) {
            n.expanded = false;
            p.refresh();
        } else {
            expand(p, n);
        }
    }

    private TNode info(TNode parent, String text, String kind) {
        TNode t = new TNode();
        t.kind = K_INFO;
        t.parent = parent;
        t.depth = parent.depth + 1;
        t.title = text;
        t.group = kind;
        t.key = "info:" + kind;
        return t;
    }

    private interface Loader {
        List<TNode> load(TNode parent) throws IOException;
    }

    /** Düğümü açar ve içeriğini arka planda yükler. */
    private void expand(final Pane p, final TNode n) {
        n.expanded = true;
        final List<TNode> oldKids = n.children;
        final Loader loader = loaderFor(p, n);
        if (loader == null) {
            p.refresh();
            return;
        }
        if (oldKids.isEmpty()) {
            n.children = new ArrayList<>();
            n.children.add(info(n, "Yükleniyor…", "loading"));
        }
        p.refresh();
        io.execute(() -> {
            List<TNode> kids;
            String error = null;
            try {
                kids = loader.load(n);
            } catch (IOException | RuntimeException e) {
                kids = new ArrayList<>();
                error = e.getMessage();
            }
            final List<TNode> fk = kids;
            final String fe = error;
            main.post(() -> {
                if (!n.expanded) return;
                // Açık alt klasörler açık kalsın
                Map<String, TNode> prev = new HashMap<>();
                for (TNode o : oldKids) if (o.key != null) prev.put(o.key, o);
                List<TNode> reopen = new ArrayList<>();
                for (TNode k : fk) {
                    TNode o = prev.get(k.key);
                    if (o == null) continue;
                    if (o.expanded && k.expandable()) {
                        k.expanded = true;
                        k.children = o.children;
                        for (TNode c : k.children) c.parent = k;
                        reopen.add(k);
                    }
                    if (p.current == o) p.current = k;
                    if (p.selected.remove(o)) p.selected.add(k);
                }
                if (fe != null) {
                    fk.clear();
                    fk.add(info(n, "Açılamadı: " + fe, "error"));
                } else if (fk.isEmpty()) {
                    fk.add(info(n, "(boş)", "empty"));
                }
                n.children = fk;
                p.refresh();
                for (TNode k : reopen) expand(p, k);
            });
        });
    }

    private Loader loaderFor(final Pane p, final TNode n) {
        if (n.kind == K_VOLUME || n.kind == K_DIR) {
            return parent -> dirChildren(parent, parent.node.fs.list(parent.node));
        }
        if (n.key == null) return null;
        switch (n.key) {
            case "fav":
                return this::favoriteChildren;
            case "recent":
                return this::recentChildren;
            case "apps":
                return parent -> appChildren(parent, prefs.getBoolean("systemApps", false));
            case "ftp":
                return this::ftpChildren;
            default:
                return null;   // geçici gruplar (arama, analiz) hazır gelir
        }
    }

    private List<TNode> favoriteChildren(TNode parent) {
        List<TNode> out = new ArrayList<>();
        for (String f : favorites()) {
            File file = new File(f);
            if (!file.isDirectory()) continue;
            TNode t = fileNode(parent, fsFor(file).node(file));
            t.path = f;
            t.key = "fav:" + f;
            out.add(t);
        }
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        for (File f : new File[]{dl, dcim}) {
            if (!f.isDirectory()) continue;
            TNode t = fileNode(parent, internal.node(f));
            t.icon = f == dl ? R.drawable.ic_download : R.drawable.ic_camera;
            t.key = "fav:" + f.getAbsolutePath();
            t.path = f.getAbsolutePath();
            out.add(t);
        }
        if (favorites().isEmpty()) {
            out.add(info(parent, "Bir klasöre dokunup ⭐ ile ekleyebilirsiniz", "hint"));
        }
        return out;
    }

    private LocalFs fsFor(File f) {
        for (LocalFs v : otherVolumes) {
            if (f.getAbsolutePath().startsWith(v.rootFile().getAbsolutePath())) return v;
        }
        if (f.getAbsolutePath().startsWith(internal.rootFile().getAbsolutePath())) return internal;
        return new LocalFs(f.getName(), f);
    }

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

    private List<TNode> dirChildren(TNode parent, List<VNode> list) {
        boolean hidden = prefs.getBoolean("hidden", false);
        List<VNode> sorted = new ArrayList<>();
        int dirs = 0;
        int files = 0;
        for (VNode k : list) {
            if (!hidden && k.hidden()) continue;
            sorted.add(k);
            if (k.dir) dirs++; else files++;
        }
        Collections.sort(sorted, comparator());
        parent.dirs = dirs;
        parent.files = files;
        List<TNode> out = new ArrayList<>();
        for (VNode k : sorted) out.add(fileNode(parent, k));
        return out;
    }

    // ---------------------------------------------------------------- yukarı / yenile

    private boolean goUp(Pane p) {
        TNode c = p.current;
        if (c == null) return false;
        if (c.expanded && c.expandable()) {
            c.expanded = false;
            p.refresh();
            return true;
        }
        if (c.parent != null) {
            p.current = c.parent;
            p.current.expanded = false;
            p.refresh();
            int pos = p.visible.indexOf(p.current);
            if (pos >= 0) p.scrollTo(pos - 2);
            return true;
        }
        return false;
    }

    /** Açık klasörlerin içeriğini yeniden okur. */
    private void reloadPane(Pane p) {
        List<TNode> r = buildRoots(p);
        p.roots.clear();
        p.roots.addAll(r);
        p.refresh();
        for (TNode n : p.roots) {
            if (n.expanded && !n.temp && loaderFor(p, n) != null) expand(p, n);
        }
    }

    private void reloadAll() {
        reloadPane(panes[0]);
        reloadPane(panes[1]);
    }

    // ---------------------------------------------------------------- hedefler

    /** Seçili öğeler; seçim yoksa üzerinde durulan dosya/klasör. */
    private List<VNode> targets(Pane p) {
        List<VNode> out = new ArrayList<>();
        for (TNode n : p.selected) if (n.node != null) out.add(n.node);
        if (out.isEmpty() && p.current != null && p.current.fileish()) out.add(p.current.node);
        return out;
    }

    /** İşlem yapılacak klasör: üzerinde durulan klasör ya da dosyanın klasörü. */
    private static VNode dirOf(TNode n) {
        if (n == null || n.node == null) return null;
        if (n.kind == K_FILE) return n.node.parent;
        return n.node.dir ? n.node : null;
    }

    private List<VNode> needTargets() {
        List<VNode> t = targets(panes[active]);
        if (t.isEmpty()) toast("Önce bir öğeye dokunun ya da uzun basıp seçin");
        return t;
    }

    // ---------------------------------------------------------------- dosya açma

    private void openFile(final TNode t) {
        final Pane p = panes[active];
        final VNode f = t.node;
        String e = f.ext();
        File local = f.fs.localFile(f);
        if ((e.equals("zip") || e.equals("jar")) && local != null) {
            try {
                final ZipFs z = new ZipFs(local);
                final TNode g = special("zip:" + local.getAbsolutePath(), K_VOLUME,
                        R.drawable.ic_file_archive, z.title(), "ZIP içi • uzun bas: kapat");
                g.node = z.root();
                g.temp = true;
                g.onLong = () -> {
                    z.close();
                    for (Pane q : panes) {
                        q.roots.remove(g);
                        q.refresh();
                    }
                };
                addTempGroup(p, g);
                expand(p, g);
            } catch (IOException | RuntimeException ex) {
                new AlertDialog.Builder(this).setTitle(f.name)
                        .setMessage("ZIP içine göz atılamadı (" + ex.getMessage() + "). "
                                + "Arşivi bir klasöre çıkarmayı deneyin.")
                        .setNegativeButton("Vazgeç", null)
                        .setPositiveButton("Buraya çıkar", (d, w) -> extract(f, f.parent))
                        .show();
            }
            return;
        }
        if (ARCHIVES.contains(e) && !e.equals("zip") && !e.equals("jar")) {
            new AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setItems(new String[]{"Diğer paneldeki klasöre çıkar", "Bu klasöre çıkar",
                            "Başka uygulamayla aç"}, (d, w) -> {
                        if (w == 2) withLocal(f, file -> openExternal(file, null));
                        else extract(f, w == 0 ? dirOf(other().current) : f.parent);
                    }).show();
            return;
        }
        if (IMAGES.contains(e)) {
            if (local != null && t.parent != null) {
                ArrayList<String> paths = new ArrayList<>();
                int index = 0;
                for (TNode s : t.parent.children) {
                    if (s.kind == K_FILE && s.node != null && IMAGES.contains(s.node.ext())) {
                        File sf = s.node.fs.localFile(s.node);
                        if (sf == null) continue;
                        if (s == t) index = paths.size();
                        paths.add(sf.getAbsolutePath());
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

    private void withLocal(final VNode f, final FileAction action) {
        File local = f.fs.localFile(f);
        if (local != null) {
            action.run(local);
            return;
        }
        final File[] out = new File[1];
        runOp("Dosya hazırlanıyor: " + f.name, (pr, c) -> {
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
        final List<VNode> sel = targets(panes[active]);
        if (sel.size() != 1 || sel.get(0).dir) {
            toast("Bir dosyaya dokunun");
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
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------- işlem çalıştırıcı

    interface Job {
        String run(Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException;
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
        statusCancel.setText("İptal");
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

    // ---------------------------------------------------------------- dosya işlemleri

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
        final VNode dir = dirOf(panes[active].current);
        if (dir == null) {
            toast("Önce bir klasöre dokunun");
            return;
        }
        if (!dir.fs.writable()) {
            toast("Bu konum salt okunur");
            return;
        }
        final EditText e = input("", "Klasör adı");
        new AlertDialog.Builder(this).setTitle("Yeni klasör: " + dir.name).setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Oluştur", (d, w) -> {
                    final String name = e.getText().toString();
                    runOp("Klasör oluşturuluyor", (pr, c) -> {
                        dir.fs.mkdir(dir, name);
                        return null;
                    }, this::reloadAll);
                }).show();
    }

    private void askRename() {
        final List<VNode> sel = needTargets();
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
                    }, this::reloadAll);
                }).show();
    }

    private void copySelected(final boolean move) {
        final List<VNode> sel = needTargets();
        if (sel.isEmpty()) return;
        final VNode dest = dirOf(other().current);
        if (dest == null) {
            toast("Diğer panelde hedef klasöre dokunun");
            setActive(1 - active);
            return;
        }
        if (!dest.fs.writable()) {
            toast("Hedef salt okunur");
            return;
        }
        if (move && !sel.get(0).fs.writable()) {
            toast("Kaynak salt okunur; taşınamaz, kopyalayın");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(move ? "Taşı" : "Kopyala")
                .setMessage(sel.size() + " öğe\n→ " + dest.fs.title() + " › " + dest.name)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton(move ? "Taşı" : "Kopyala", (d, w) ->
                        runOp(move ? "Taşınıyor" : "Kopyalanıyor",
                                (pr, c) -> new FileOps(pr, c, this::status).copy(sel, dest, move),
                                () -> {
                                    panes[active].selected.clear();
                                    reloadAll();
                                }))
                .show();
    }

    private void askDelete() {
        final List<VNode> sel = needTargets();
        if (sel.isEmpty()) return;
        if (!sel.get(0).fs.writable()) {
            toast("Bu konum salt okunur");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(5, sel.size()); i++) names.append("• ").append(sel.get(i).name).append('\n');
        if (sel.size() > 5) names.append("… ve ").append(sel.size() - 5).append(" öğe daha\n");
        new AlertDialog.Builder(this)
                .setTitle(sel.size() == 1 ? "Silinsin mi?" : sel.size() + " öğe silinsin mi?")
                .setMessage(names + "\nSilinen dosyalar geri getirilemez.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> runOp("Siliniyor", (pr, c) -> {
                    FileOps ops = new FileOps(pr, c, this::status);
                    ops.delete(sel);
                    return ops.files() + " öğe silindi";
                }, () -> {
                    Pane p = panes[active];
                    p.selected.clear();
                    if (p.current != null && p.current.fileish()) p.current = p.current.parent;
                    reloadAll();
                }))
                .show();
    }

    private void askZip() {
        final List<VNode> sel = needTargets();
        if (sel.isEmpty()) return;
        VNode here = sel.get(0).parent;
        final VNode dest = here != null && here.fs.writable() ? here : dirOf(other().current);
        if (dest == null || !dest.fs.writable()) {
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
                    }, this::reloadAll);
                }).show();
    }

    private void extractSelected() {
        final List<VNode> sel = needTargets();
        if (sel.isEmpty()) return;
        if (sel.size() != 1 || sel.get(0).dir) {
            toast("Bir arşiv dosyası seçin");
            return;
        }
        VNode o = dirOf(other().current);
        extract(sel.get(0), o != null && o.fs.writable() ? o : sel.get(0).parent);
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
        }, this::reloadAll);
    }

    private void shareSelected() {
        final List<VNode> sel = needTargets();
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
        List<VNode> sel = targets(p);
        if (sel.isEmpty()) {
            VNode d = dirOf(p.current);
            if (d != null) sel = Collections.singletonList(d);
        }
        if (sel.isEmpty()) {
            toast("Önce bir öğeye dokunun");
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
                sb.append("Yer: ").append(n.fs.title()).append("  ").append(n.path).append('\n');
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
            int dirsSel = 0;
            for (VNode v : nodes) if (v.dir) dirsSel++;
            sb.append("Boyut: ").append(DiskOps.sizeText(r[0][0])).append(" (")
                    .append(String.format(Locale.ROOT, "%,d", r[0][0])).append(" bayt)\n");
            sb.append("İçerik: ").append(r[0][1]).append(" dosya, ")
                    .append(Math.max(0, r[0][2] - dirsSel)).append(" klasör");
            new AlertDialog.Builder(this).setTitle("Özellikler").setMessage(sb)
                    .setPositiveButton("Tamam", null).show();
        });
    }

    private void selectAll() {
        Pane p = panes[active];
        TNode c = p.current;
        TNode dir = c == null ? null : c.kind == K_FILE ? c.parent : c;
        if (dir == null || !dir.expanded) {
            toast("Önce bir klasör açın");
            return;
        }
        boolean all = true;
        for (TNode k : dir.children) if (k.fileish() && !p.selected.contains(k)) all = false;
        for (TNode k : dir.children) {
            if (!k.fileish()) continue;
            if (all) p.selected.remove(k); else p.selected.add(k);
        }
        p.refresh();
    }

    private void addFavorite() {
        VNode d = dirOf(panes[active].current);
        if (d == null || !(d.fs instanceof LocalFs)) {
            toast("Telefon belleğinde bir klasöre dokunun");
            return;
        }
        List<String> favs = favorites();
        if (favs.contains(d.path)) {
            favs.remove(d.path);
            toast("Sık kullanılanlardan çıkarıldı");
        } else {
            favs.add(d.path);
            toast("Sık kullanılanlara eklendi");
        }
        StringBuilder sb = new StringBuilder();
        for (String f : favs) sb.append(f).append('\n');
        prefs.edit().putString("favorites", sb.toString()).apply();
        for (Pane p : panes) {
            for (TNode r : p.roots) if ("fav".equals(r.key) && r.expanded) expand(p, r);
        }
    }

    private List<String> favorites() {
        String s = prefs.getString("favorites", "");
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.trim().isEmpty()) out.add(x);
        return out;
    }

    // ---------------------------------------------------------------- arama / sıralama / menü

    private void addTempGroup(Pane p, TNode g) {
        for (int i = p.roots.size() - 1; i >= 0; i--) {
            if (p.roots.get(i).temp && g.key.equals(p.roots.get(i).key)) p.roots.remove(i);
        }
        int at = 0;
        while (at < p.roots.size() && "perm".equals(p.roots.get(at).key)) at++;
        p.roots.add(at, g);
        p.current = g;
        p.refresh();
        p.scrollTo(at);
    }

    private void askSearch() {
        final Pane p = panes[active];
        VNode d = dirOf(p.current);
        final VNode where = d != null ? d : internal.root();
        final EditText e = input("", "Dosya adı (bir kısmı)");
        new AlertDialog.Builder(this).setTitle("Ara: " + where.name).setView(padded(e))
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Ara", (dd, w) -> {
                    final String q = e.getText().toString().trim();
                    if (q.isEmpty()) return;
                    final List<VNode> found = new ArrayList<>();
                    runOp("Aranıyor", (pr, c) -> {
                        found.addAll(new FileOps(pr, c, this::status).search(where, q, 1000));
                        return found.isEmpty() ? "Bulunamadı" : null;
                    }, () -> {
                        if (found.isEmpty()) return;
                        TNode g = special("search", K_GROUP, R.drawable.ic_search_file,
                                "Arama: \"" + q + "\"", found.size() + " sonuç • uzun bas: kapat");
                        g.temp = true;
                        g.expanded = true;
                        for (VNode n : found) {
                            TNode t = fileNode(g, n);
                            t.path = n.parent != null ? n.parent.path : null;
                            g.children.add(t);
                        }
                        addTempGroup(p, g);
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
                    reloadAll();
                })
                .setNeutralButton(desc ? "Artan sırala" : "Azalan sırala", (d, w) -> {
                    prefs.edit().putBoolean("desc", !desc).apply();
                    reloadAll();
                })
                .show();
    }

    private void showMenu() {
        final boolean hidden = prefs.getBoolean("hidden", false);
        String[] opts = {
                (hidden ? "✓ " : "") + "Gizli dosyaları göster",
                "Panelleri değiştir",
                "WiFi dosya paylaşımı",
                "USB kart araçları",
                "Önbelleği temizle",
                "Hakkında"
        };
        new AlertDialog.Builder(this).setItems(opts, (d, w) -> {
            switch (w) {
                case 0:
                    prefs.edit().putBoolean("hidden", !hidden).apply();
                    reloadAll();
                    break;
                case 1:
                    setActive(1 - active);
                    break;
                case 2:
                    toggleWifi();
                    break;
                case 3:
                    openTools();
                    break;
                case 4:
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

    // ---------------------------------------------------------------- USB

    private void openUsb() {
        if (!usb.ensurePermission()) {
            if (usb.device() == null) toast("Kart okuyucu bulunamadı");
            return;
        }
        runOp("USB kart açılıyor", (pr, c) -> {
            usb.open();
            return usb.volumes().isEmpty() ? (usb.error() != null ? usb.error()
                    : "Kartta açılabilen bölüm yok") : null;
        }, () -> {
            refreshRoots();
            Pane p = panes[active];
            for (TNode r : p.roots) {
                if (r.key != null && r.key.startsWith("usb:") && !r.expanded) {
                    p.current = r;
                    expand(p, r);
                    break;
                }
            }
        });
    }

    private void openTools() {
        try {
            io.submit(() -> usb.close()).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // bağlantı kapanmadıysa araçlar ekranı "Yenile" ile yeniden dener
        }
        refreshRoots();
        startActivity(new Intent(this, MainActivity.class));
    }

    // ---------------------------------------------------------------- son dosyalar

    private List<TNode> recentChildren(TNode parent) {
        List<TNode> out = new ArrayList<>();
        if (!hasStorageAccess()) {
            out.add(info(parent, "Dosya erişim izni gerekli", "error"));
            return out;
        }
        String[] proj = {MediaStore.Files.FileColumns.DATA};
        try (Cursor cur = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                proj, null, null, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (cur == null) return out;
            String root = internal.rootFile().getAbsolutePath();
            while (cur.moveToNext() && out.size() < 200) {
                String path = cur.getString(0);
                if (path == null || !path.startsWith(root)) continue;
                File f = new File(path);
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                TNode t = fileNode(parent, internal.node(f));
                t.path = f.getParent();
                out.add(t);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- disk analizi

    private void analyzeCurrent() {
        final Pane p = panes[active];
        final VNode dir = dirOf(p.current);
        if (dir == null) {
            toast("Önce bir klasöre dokunun");
            return;
        }
        final List<Object[]> res = new ArrayList<>();
        runOp("Disk analizi: " + dir.name, (pr, c) -> {
            res.addAll(new FileOps(pr, c, this::status).analyze(dir));
            return null;
        }, () -> {
            Collections.sort(res, (a, b) -> Long.compare((Long) b[1], (Long) a[1]));
            long total = 0;
            for (Object[] o : res) total += (Long) o[1];
            TNode g = special("analysis", K_GROUP, R.drawable.ic_t_analysis,
                    "Disk analizi: " + dir.name, DiskOps.sizeText(total) + " • uzun bas: kapat");
            g.temp = true;
            g.expanded = true;
            for (Object[] o : res) {
                VNode n = (VNode) o[0];
                long bytes = (Long) o[1];
                TNode t = fileNode(g, n);
                t.sub = null;
                t.right1 = String.format(Locale.ROOT, "%%%.1f", total > 0 ? bytes * 100.0 / total : 0);
                t.right2 = DiskOps.sizeText(bytes);
                t.bar = total > 0 ? (float) bytes / total : 0;
                g.children.add(t);
            }
            if (g.children.isEmpty()) g.children.add(info(g, "(boş)", "empty"));
            addTempGroup(p, g);
        });
    }

    // ---------------------------------------------------------------- uygulama yöneticisi

    private List<TNode> appChildren(TNode parent, final boolean system) {
        PackageManager pm = getPackageManager();
        List<TNode> out = new ArrayList<>();
        TNode toggle = new TNode();
        toggle.kind = K_ACTION;
        toggle.parent = parent;
        toggle.depth = parent.depth + 1;
        toggle.key = "apps:toggle";
        toggle.icon = R.drawable.ic_t_selectall;
        toggle.title = system ? "Yalnızca yüklenen uygulamalar" : "Sistem uygulamalarını da göster";
        toggle.action = () -> {
            prefs.edit().putBoolean("systemApps", !system).apply();
            for (Pane p : panes) for (TNode r : p.roots) if ("apps".equals(r.key) && r.expanded) expand(p, r);
        };
        out.add(toggle);
        List<Object[]> rows = new ArrayList<>();
        for (ApplicationInfo a : pm.getInstalledApplications(0)) {
            boolean sys = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (a.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (sys && !system) continue;
            rows.add(new Object[]{String.valueOf(pm.getApplicationLabel(a)), a});
        }
        Collections.sort(rows, (x, y) -> ((String) x[0]).compareToIgnoreCase((String) y[0]));
        for (Object[] r : rows) {
            ApplicationInfo a = (ApplicationInfo) r[1];
            TNode t = new TNode();
            t.kind = K_APP;
            t.parent = parent;
            t.depth = parent.depth + 1;
            t.key = "app:" + a.packageName;
            t.title = (String) r[0];
            t.app = a;
            String ver = "";
            try {
                PackageInfo pi = pm.getPackageInfo(a.packageName, 0);
                ver = pi.versionName == null ? "" : pi.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            t.sub = a.packageName;
            t.right1 = ver.length() > 14 ? ver.substring(0, 14) : ver;
            t.right2 = a.sourceDir != null ? DiskOps.sizeText(new File(a.sourceDir).length()) : null;
            try {
                t.drawable = pm.getApplicationIcon(a);
            } catch (RuntimeException ignored) {
                t.icon = R.drawable.ic_file_apk;
            }
            out.add(t);
        }
        return out;
    }

    private void appMenu(final TNode it) {
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

    private void backupApk(final TNode it, final boolean share) {
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

    private List<TNode> ftpChildren(TNode parent) {
        List<TNode> out = new ArrayList<>();
        final List<FtpFs.Server> list = ftpServers();
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final FtpFs.Server s = list.get(i);
            FtpFs fs = new FtpFs(s);
            TNode t = new TNode();
            t.kind = K_VOLUME;
            t.parent = parent;
            t.depth = parent.depth + 1;
            t.key = "ftpsrv:" + s.host + ":" + s.port + ":" + s.user;
            t.node = fs.root();
            t.icon = R.drawable.ic_network;
            t.title = s.name;
            t.sub = s.user + "@" + s.host + ":" + s.port + s.path;
            t.onLong = () -> new AlertDialog.Builder(this).setTitle(s.name)
                    .setItems(new String[]{"Düzenle", "Sil"}, (d, w) -> {
                        if (w == 0) {
                            editFtp(idx);
                        } else {
                            list.remove(idx);
                            saveFtp(list);
                            reloadFtp();
                        }
                    }).show();
            out.add(t);
        }
        TNode add = new TNode();
        add.kind = K_ACTION;
        add.parent = parent;
        add.depth = parent.depth + 1;
        add.key = "ftp:add";
        add.icon = R.drawable.ic_t_newfolder;
        add.title = "Sunucu ekle";
        add.sub = "Bilgisayar, modem ya da NAS'taki FTP sunucusu";
        add.action = () -> editFtp(-1);
        out.add(add);
        return out;
    }

    private void reloadFtp() {
        for (Pane p : panes) for (TNode r : p.roots) if ("ftp".equals(r.key) && r.expanded) expand(p, r);
    }

    private void editFtp(final int idx) {
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
                    reloadFtp();
                }).show();
    }

    // ---------------------------------------------------------------- WiFi paylaşımı

    private void toggleWifi() {
        if (wifi != null) {
            wifi.stop();
            wifi = null;
            toast("WiFi paylaşımı kapatıldı");
            refreshRoots();
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
        refreshRoots();
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
            box.addView(label("Parolayı unutursanız kasadaki dosyalar açılamaz.", 13, 0xFF888888));
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
                        refreshRoots();
                        for (TNode r : p.roots) {
                            if ("vaultopen".equals(r.key)) {
                                p.current = r;
                                expand(p, r);
                            }
                        }
                        toast("Kasa açık. Dosya eklemek için diğer panelden kopyalayın ya da taşıyın.");
                    });
                }).show();
    }

    private void lockVault() {
        if (vault == null) return;
        vault.close();
        vault = null;
        clearOpenCache();
        refreshRoots();
        toast("Kasa kilitlendi");
    }
}

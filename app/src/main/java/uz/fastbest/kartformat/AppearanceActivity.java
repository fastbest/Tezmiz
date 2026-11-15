package uz.fastbest.kartformat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Görünüm: Myplayer ile aynı tema seçenekleri (Tema, Stil, Renk, Yazı boyutu, Ön izleme). */
public class AppearanceActivity extends Activity {

    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private interface IntCb {
        void on(int v);
    }

    private SharedPreferences sp;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onCreate(Bundle s) {
        setTheme(Theme.widgetTheme(this));
        super.onCreate(s);
        L.init(this);
        sp = Theme.prefs(this);
        Theme.apply(this);
        build();
    }

    private void changed() {
        Theme.load(this);
        recreate();
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(L.t(s));
        t.setTextSize(size * Theme.SCALE);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private ImageView img(int res, int tint, int size, int pad) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        if (tint != 0) v.setColorFilter(tint);
        int p = dp(pad);
        v.setPadding(p, p, p, p);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(size + pad * 2), dp(size + pad * 2)));
        return v;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.pageBg());
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Theme.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(64));
        bar.setPadding(dp(4), 0, dp(12), 0);
        TextView back = text("←", 24, Theme.TXT, false);
        back.setPadding(dp(14), dp(6), dp(14), dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView t = text("Görünüm", 21, Theme.TXT, true);
        t.setPadding(dp(4), 0, 0, 0);
        bar.addView(t);
        root.addView(bar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), 0, dp(18), dp(30));

        body.addView(label("Tema", R.drawable.ic_sun));
        String[] themes = new String[Theme.THEMES.length];
        for (int i = 0; i < themes.length; i++) {
            String v = L.exact("tema:" + Theme.THEMES[i]);
            themes[i] = v.startsWith("tema:") ? Theme.THEMES[i] : v;
        }
        body.addView(segmented(themes, new int[]{R.drawable.ic_theme_auto, R.drawable.ic_sun, R.drawable.ic_moon},
                sp.getInt("tema", Theme.DEF_TEMA), k -> {
            sp.edit().putInt("tema", k).commit();
            changed();
        }));

        body.addView(label("Stil", R.drawable.ic_style_glass));
        body.addView(segmented(new String[]{"Düz", "Cam"},
                new int[]{R.drawable.ic_style_flat, R.drawable.ic_style_glass}, Theme.GLASS ? 1 : 0, k -> {
            sp.edit().putBoolean("cam", k == 1).commit();
            changed();
        }));

        body.addView(label("Renk", R.drawable.ic_palette));
        final int sel = Math.max(0, Math.min(Theme.SWATCHES.length - 1, sp.getInt("renk", Theme.DEF_RENK)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout sw = new LinearLayout(this);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < Theme.SWATCHES.length; i++) {
            final int k = i;
            int color = Color.parseColor(Theme.SWATCHES[i][1]);
            View c = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            if (i == sel) g.setStroke(dp(3), Theme.GLASS ? Theme.G2 : Theme.BG);
            c.setBackground(g);
            FrameLayout ring = new FrameLayout(this);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            rg.setColor(i == sel ? color : Color.TRANSPARENT);
            ring.setBackground(rg);
            if (i == sel) ring.setElevation(dp(6));
            ring.addView(c, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            ring.setOnClickListener(v -> {
                sp.edit().putInt("renk", k).commit();
                changed();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(42), dp(42));
            rp.setMargins(0, 0, dp(10), 0);
            sw.addView(ring, rp);
        }
        hs.addView(sw);
        body.addView(hs);
        TextView name = text(Theme.SWATCHES[sel][0], 14, Theme.ACCENT, true);
        name.setPadding(dp(2), dp(8), 0, 0);
        body.addView(name);

        body.addView(label("Yazı boyutu", R.drawable.ic_textsize));
        int zsel = 1;
        float cur = sp.getFloat("yazi", 1f);
        for (int i = 0; i < Theme.ZOOMS.length; i++) if (Math.abs(Theme.ZOOMS[i] - cur) < 0.01f) zsel = i;
        body.addView(segmented(Theme.ZOOM_LABELS, new int[]{-13, -16, -19, -23}, zsel, k -> {
            sp.edit().putFloat("yazi", Theme.ZOOMS[k]).commit();
            changed();
        }));

        body.addView(label("Ön izleme", R.drawable.ic_style_flat));
        body.addView(preview());

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1));
        setContentView(root);
    }

    private View label(String s, int icon) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(20), 0, dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        if (icon != R.drawable.ic_palette) iv.setColorFilter(Theme.ACCENT);
        row.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(s, 15, Theme.TXT, true);
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private LinearLayout segmented(String[] items, int[] icons, int selected, IntCb cb) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(round(Theme.FIELD, dp(22)));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            boolean on = i == selected;
            int fg = on ? (Theme.LIGHT || !Theme.GLASS ? Theme.ACCENT : Theme.TXT) : Theme.SUB;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(9), 0, dp(8));
            if (icons[i] > 0) {
                ImageView iv = new ImageView(this);
                iv.setImageResource(icons[i]);
                iv.setColorFilter(fg);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
            } else {
                TextView a = text("A", -icons[i], fg, true);
                a.setGravity(Gravity.CENTER);
                a.setIncludeFontPadding(false);
                cell.addView(a, new LinearLayout.LayoutParams(WC, dp(24)));
            }
            TextView t = text(items[i], 13f, on ? Theme.TXT : Theme.SUB, on);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(4), 0, 0);
            cell.addView(t);
            if (on) {
                cell.setBackground(round(Theme.LIGHT ? Color.WHITE : Theme.OUTLINE, dp(18)));
                cell.setElevation(dp(2));
            }
            cell.setOnClickListener(v -> {
                if (k != selected) cb.on(k);
            });
            seg.addView(cell, new LinearLayout.LayoutParams(0, WC, 1));
        }
        return seg;
    }

    /** Küçük ön izleme: üst çubuk, yol çubuğu, iki satır ve araç çubuğu parçası. */
    private View preview() {
        LinearLayout prev = new LinearLayout(this);
        prev.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = round(Theme.GLASS ? Theme.ROW : Theme.BG, dp(22));
        pg.setStroke(dp(1), Theme.OUTLINE);
        prev.setBackground(pg);
        prev.setClipToOutline(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Theme.BAR);
        top.setPadding(dp(14), dp(10), dp(8), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView nm = text(getString(R.string.app_name), 18, Theme.TXT, true);
        nm.setPadding(dp(10), 0, 0, 0);
        top.addView(nm, new LinearLayout.LayoutParams(0, WC, 1));
        top.addView(img(R.drawable.ic_m_search, Theme.TXT, 22, 6));
        top.addView(img(R.drawable.ic_m_menu, Theme.TXT, 22, 6));
        prev.addView(top);

        TextView crumb = text("Dahili depolama  ›  Download", 13, Color.WHITE, false);
        crumb.setBackground(round(Theme.ACCENT, dp(6)));
        crumb.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WC, WC);
        clp.setMargins(dp(10), dp(8), 0, dp(4));
        prev.addView(crumb, clp);

        prev.addView(sampleRow(R.drawable.ic_folder, "Download", "7  •  96", true));
        prev.addView(sampleRow(R.drawable.ic_file_image, "foto.jpg", "2,4 MB  •  15.11.2026", false));

        View prog = new View(this);
        prog.setBackgroundColor(Theme.ACCENT);
        prev.addView(prog, new LinearLayout.LayoutParams(dp(120), dp(3)));
        LinearLayout status = new LinearLayout(this);
        status.setBackgroundColor(Theme.BAR);
        status.setPadding(dp(14), dp(8), dp(10), dp(8));
        status.addView(text("Kopyalanıyor", 13, Theme.SUB, false));
        prev.addView(status);
        return prev;
    }

    private View sampleRow(int icon, String title, String sub, boolean current) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(10), dp(8), dp(10));
        if (current) {
            GradientDrawable g = round(Theme.alpha(Theme.ACCENT, 0x33), 0);
            g.setStroke(dp(2), Theme.ACCENT);
            r.setBackground(g);
        }
        r.addView(img(icon, 0, 28, 4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        col.addView(text(title, 16, Theme.TXT, false));
        col.addView(text(sub, 13, Theme.SUB, false));
        r.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
        r.addView(img(R.drawable.ic_check, current ? Theme.ACCENT : Theme.DIM, 22, 6));
        return r;
    }
}

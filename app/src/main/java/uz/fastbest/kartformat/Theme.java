package uz.fastbest.kartformat;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

/**
 * Görünüm teması — Myplayer ile aynı seçenekler ve renkler:
 * Tema (Sistem / Açık / Koyu), Stil (Düz / Cam), Renk (9 vurgu rengi), Yazı boyutu.
 */
public final class Theme {

    public static int BG = 0xFF121212, BAR = 0xFF1C1C1C, SURF = 0xFF2B2B2B, ACCENT = 0xFFFF8800,
            TXT = 0xFFFFFFFF, SUB = 0xFFAAAAAA, ICON = 0xFFBDBDBD, LINE = 0xFF2A2A2A,
            ROW = 0xFF2A2A2A, DIM = 0xFF8A8A8A, TRACK = 0xFF4A4A4A, FIELD = 0xFF2E2E2E, OUTLINE = 0xFF4A4A4A,
            G1, G2, G3;
    public static boolean LIGHT, GLASS;
    public static float SCALE = 1f;
    /** Tema her değiştiğinde artar; açık ekranlar kendini yeniden kurar. */
    public static int stamp;
    private static String loadedKey = "";

    public static final String[][] SWATCHES = {
            {"Turuncu", "#FF8800"}, {"Turkuaz", "#5FA3B0"}, {"Okyanus", "#3B8FE0"}, {"Lavanta", "#8E7CE8"},
            {"Gül", "#E0679C"}, {"Mercan", "#F2655B"}, {"Zümrüt", "#2EA56F"}, {"Kehribar", "#E0A23B"},
            {"Grafit", "#7D8396"}};
    public static final String[] THEMES = {"Sistem", "Açık", "Koyu"};
    public static final float[] ZOOMS = {0.85f, 1f, 1.15f, 1.3f};
    public static final String[] ZOOM_LABELS = {"Küçük", "Normal", "Büyük", "Çok büyük"};

    private Theme() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gezgin", Context.MODE_PRIVATE);
    }

    public static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) * t + Color.red(b) * (1 - t)),
                (int) (Color.green(a) * t + Color.green(b) * (1 - t)),
                (int) (Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    public static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int col(String s) {
        return Color.parseColor(s);
    }

    public static void load(Context c) {
        SharedPreferences sp = prefs(c);
        int t = sp.getInt("tema", 2);
        boolean nightSys = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int sw = sp.getInt("renk", 0);
        if (sw < 0 || sw >= SWATCHES.length) sw = 0;
        boolean glass = sp.getBoolean("cam", false);
        float scale = sp.getFloat("yazi", 1f);
        boolean light = t == 1 || (t == 0 && !nightSys);
        String key = light + "/" + glass + "/" + sw + "/" + scale;
        if (key.equals(loadedKey)) return;
        loadedKey = key;
        stamp++;
        LIGHT = light;
        GLASS = glass;
        SCALE = scale;
        if (LIGHT) {
            BG = col("#F3F4F8"); BAR = col("#FFFFFF"); SURF = col("#FFFFFF");
            TXT = col("#1E2130"); SUB = col("#6B7085"); ICON = col("#5F6476"); LINE = col("#E3E5EB");
            ROW = col("#E7E9EF"); DIM = col("#8A8F9E"); TRACK = col("#D3D6DF"); FIELD = col("#E8EAF0");
            OUTLINE = col("#D3D6DF");
        } else {
            BG = 0xFF121212; BAR = 0xFF1C1C1C; SURF = 0xFF2B2B2B;
            TXT = 0xFFFFFFFF; SUB = 0xFFAAAAAA; ICON = 0xFFBDBDBD; LINE = 0xFF2A2A2A;
            ROW = 0xFF2A2A2A; DIM = 0xFF8A8A8A; TRACK = 0xFF4A4A4A; FIELD = 0xFF2E2E2E; OUTLINE = 0xFF4A4A4A;
        }
        ACCENT = col(SWATCHES[sw][1]);
        if (GLASS) {
            int base = LIGHT ? col("#F1F3F9") : col("#171A2B");
            int second = col(SWATCHES[(sw + 2) % SWATCHES.length][1]);
            G1 = mix(ACCENT, base, LIGHT ? 0.45f : 0.50f);
            G2 = mix(ACCENT, base, LIGHT ? 0.10f : 0.12f);
            G3 = mix(second, base, LIGHT ? 0.35f : 0.38f);
            BG = mix(ACCENT, base, 0.08f);
            SURF = LIGHT ? col("#F7FFFFFF") : mix(ACCENT, base, 0.22f);
            if (LIGHT) {
                BAR = col("#8CFFFFFF"); FIELD = col("#A6FFFFFF"); ROW = col("#BFFFFFFF");
                OUTLINE = col("#E6FFFFFF"); LINE = col("#1A000000"); SUB = col("#565B70");
                TRACK = col("#33000000");
            } else {
                BAR = col("#24FFFFFF"); FIELD = col("#2BFFFFFF"); ROW = col("#30FFFFFF");
                OUTLINE = col("#4DFFFFFF"); LINE = col("#22FFFFFF"); SUB = col("#C9CCDA");
                TRACK = col("#44FFFFFF");
            }
        }
    }

    /** Ekranın arka planı: cam stilde renk geçişi, değilse düz renk. */
    public static Drawable pageBg() {
        if (GLASS) return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2, G3});
        return new ColorDrawable(BG);
    }

    /** Opak çubuk rengi (cam stilde yarı saydam olmasın diye arka planla karıştırılır). */
    public static int solidBar() {
        if (!GLASS) return BAR;
        return mix(G1, LIGHT ? Color.WHITE : Color.BLACK, 0.85f);
    }

    @SuppressWarnings("deprecation")
    public static void apply(Activity a) {
        load(a);
        a.getWindow().setStatusBarColor(GLASS ? G1 : BAR);
        a.getWindow().setNavigationBarColor(GLASS ? G3 : BAR);
        int f = 0;
        if (LIGHT) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        a.getWindow().getDecorView().setSystemUiVisibility(f);
        a.getWindow().setBackgroundDrawable(pageBg());
    }

    public static int dialogTheme() {
        return LIGHT ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert;
    }
}

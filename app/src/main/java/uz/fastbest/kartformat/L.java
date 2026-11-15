package uz.fastbest.kartformat;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arayüz dili. Kaynak metinler Türkçe; seçilen dilin karşılıkları assets/lang/xx.tsv'den okunur.
 * Bir dilde karşılığı olmayan metin önce İngilizceye, o da yoksa Türkçeye düşer.
 */
public final class L {

    /** Desteklenen diller: kod, kendi dilindeki adı. */
    public static final String[][] LANGS = {
            {"en", "English"}, {"ru", "Русский"}, {"uz", "O'zbekcha"}, {"tr", "Türkçe"},
            {"ar", "العربية"}, {"es", "Español"}, {"pt", "Português"}, {"fr", "Français"},
            {"de", "Deutsch"}, {"zh", "中文"}, {"hi", "हिन्दी"},
    };

    private static String lang = "tr";
    private static final Map<String, String> map = new HashMap<>();
    private static final Map<String, String> cache = new HashMap<>();
    private static final List<String> phrases = new ArrayList<>();

    private L() {
    }

    public static String lang() {
        return lang;
    }

    /** prefs'teki seçim ya da sistem dili; desteklenmiyorsa İngilizce. */
    public static void init(Context c) {
        SharedPreferences p = c.getSharedPreferences("gezgin", Context.MODE_PRIVATE);
        String want = p.getString("lang", "");
        if (want.isEmpty()) want = Locale.getDefault().getLanguage();
        boolean ok = false;
        for (String[] l : LANGS) if (l[0].equals(want)) ok = true;
        if (!ok) want = "en";
        if (want.equals(lang) && (lang.equals("tr") || !map.isEmpty())) return;
        lang = want;
        map.clear();
        cache.clear();
        phrases.clear();
        if (lang.equals("tr")) return;
        if (!lang.equals("en")) load(c, "en");   // eksikler İngilizceye düşsün
        load(c, lang);
        phrases.addAll(map.keySet());
        Collections.sort(phrases, (a, b) -> b.length() - a.length());
    }

    private static void load(Context c, String code) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("lang/" + code + ".tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                map.put(unesc(line.substring(0, tab)), unesc(line.substring(tab + 1)));
            }
        } catch (Exception ignored) {
        }
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /** Tam eşleşme; yoksa metnin içindeki bilinen ifadeleri değiştirir. */
    public static String t(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString();
        if (map.isEmpty() || s.isEmpty()) return s;
        String r = map.get(s);
        if (r != null) return r;
        r = cache.get(s);
        if (r != null) return r;
        String out = s;
        for (String k : phrases) {
            if (k.trim().length() < 3) continue;
            int from = 0;
            while (true) {
                int at = out.indexOf(k, from);
                if (at < 0) break;
                int end = at + k.length();
                boolean okL = at == 0 || !Character.isLetter(out.charAt(at - 1)) || !Character.isLetter(k.charAt(0));
                boolean okR = end >= out.length() || !Character.isLetter(out.charAt(end))
                        || !Character.isLetter(k.charAt(k.length() - 1));
                if (okL && okR) {
                    String v = map.get(k);
                    out = out.substring(0, at) + v + out.substring(end);
                    from = at + v.length();
                } else {
                    from = at + 1;
                }
            }
        }
        if (cache.size() > 2000) cache.clear();
        cache.put(s, out);
        return out;
    }

    /** Yalnız tam eşleşme (dosya ve sunucu adları gibi kullanıcı metinleri bozulmasın). */
    public static String exact(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString();
        String r = map.get(s);
        return r != null ? r : s;
    }

    public static String[] t(CharSequence[] a) {
        if (a == null) return null;
        String[] o = new String[a.length];
        for (int i = 0; i < a.length; i++) o[i] = t(a[i]);
        return o;
    }

    /** XML'den kurulan ekranlardaki metinleri çevirir. */
    public static void tree(View v) {
        if (map.isEmpty() || v == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getText() != null && tv.getText().length() > 0) tv.setText(t(tv.getText()));
            if (tv.getHint() != null) tv.setHint(t(tv.getHint()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tree(g.getChildAt(i));
        }
    }

    /** Başlık, ileti, düğme ve seçenekleri çeviren AlertDialog.Builder. */
    public static class Builder extends AlertDialog.Builder {
        public Builder(Context c) {
            super(c);
        }

        @Override
        public Builder setTitle(CharSequence s) {
            super.setTitle(t(s));
            return this;
        }

        @Override
        public Builder setMessage(CharSequence s) {
            super.setMessage(t(s));
            return this;
        }

        @Override
        public Builder setPositiveButton(CharSequence s, DialogInterface.OnClickListener l) {
            super.setPositiveButton(t(s), l);
            return this;
        }

        @Override
        public Builder setNegativeButton(CharSequence s, DialogInterface.OnClickListener l) {
            super.setNegativeButton(t(s), l);
            return this;
        }

        @Override
        public Builder setNeutralButton(CharSequence s, DialogInterface.OnClickListener l) {
            super.setNeutralButton(t(s), l);
            return this;
        }

        @Override
        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener l) {
            super.setItems(t(items), l);
            return this;
        }

        @Override
        public Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
            super.setSingleChoiceItems(t(items), checked, l);
            return this;
        }

        @Override
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked,
                                           DialogInterface.OnMultiChoiceClickListener l) {
            super.setMultiChoiceItems(t(items), checked, l);
            return this;
        }
    }
}

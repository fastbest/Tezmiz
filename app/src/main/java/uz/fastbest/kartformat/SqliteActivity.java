package uz.fastbest.kartformat;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** SQLite veritabanı görüntüleyici (salt okunur): tablolar ve ilk 200 satır. */
public class SqliteActivity extends Activity {

    private SQLiteDatabase db;
    private LinearLayout col;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Theme.widgetTheme(this));
        super.onCreate(savedInstanceState);
        L.init(this);
        Theme.apply(this);
        String path = getIntent().getStringExtra("path");
        try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY
                    | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, L.t("Veritabanı açılamadı: " + e.getMessage()),
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Theme.pageBg());
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(14));
        sv.addView(col);
        setContentView(sv);
        TextView head = text(new File(path).getName(), 20, Theme.TXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(head);
        List<String> tables = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", null)) {
            while (c.moveToNext()) tables.add(c.getString(0));
        } catch (Exception e) {
            col.addView(text("Okunamadı: " + e.getMessage(), 14, 0xFFE53935));
        }
        col.addView(text(tables.size() + " tablo", 13, Theme.SUB));
        for (final String t : tables) {
            long n = -1;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM \"" + t.replace("\"", "\"\"") + "\"", null)) {
                if (c.moveToFirst()) n = c.getLong(0);
            } catch (Exception ignored) {
            }
            TextView row = text(t + (n >= 0 ? "   (" + n + " satır)" : ""), 16, Theme.TXT);
            row.setPadding(dp(10), dp(12), dp(10), dp(12));
            row.setBackgroundColor(Theme.GLASS ? Theme.ROW : Theme.SURF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setOnClickListener(v -> showTable(t));
            col.addView(row, lp);
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void showTable(String t) {
        TableLayout table = new TableLayout(this);
        table.setPadding(dp(6), dp(6), dp(6), dp(6));
        try (Cursor c = db.rawQuery("SELECT * FROM \"" + t.replace("\"", "\"\"") + "\" LIMIT 200", null)) {
            TableRow h = new TableRow(this);
            for (String name : c.getColumnNames()) h.addView(cell(name, true));
            table.addView(h);
            while (c.moveToNext()) {
                TableRow r = new TableRow(this);
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String v;
                    int type = c.getType(i);
                    if (type == Cursor.FIELD_TYPE_NULL) v = "NULL";
                    else if (type == Cursor.FIELD_TYPE_BLOB) v = "[BLOB " + c.getBlob(i).length + " B]";
                    else v = c.getString(i);
                    r.addView(cell(v, false));
                }
                table.addView(r);
            }
        } catch (Exception e) {
            table.addView(cell("Okunamadı: " + e.getMessage(), false));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(table);
        ScrollView vs = new ScrollView(this);
        vs.addView(hs);
        new L.Builder(this).setTitle(t + " (en çok 200 satır)").setView(vs)
                .setPositiveButton("Kapat", null).show();
    }

    private View cell(String s, boolean head) {
        TextView v = text(s == null ? "" : s, 12, head ? Color.WHITE : Theme.TXT);
        if (head) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setMaxWidth(dp(220));
        v.setMaxLines(3);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(8), dp(5), dp(8), dp(5));
        v.setBackgroundColor(head ? Theme.ACCENT : (Theme.GLASS ? Theme.ROW : Theme.SURF));
        TableRow.LayoutParams lp = new TableRow.LayoutParams();
        lp.setMargins(1, 1, 1, 1);
        v.setLayoutParams(lp);
        return v;
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }
}

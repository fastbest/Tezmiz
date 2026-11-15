package uz.fastbest.kartformat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

/** Dar paneldeki dikey (90° döndürülmüş) yazı. */
final class VerticalLabel extends View {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";

    VerticalLabel(Context c, float textSizePx, int color) {
        super(c);
        paint.setTextSize(textSizePx);
        paint.setColor(color);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setText(String t) {
        text = t == null ? "" : t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        String t = TextUtils.ellipsize(text, paint, h - getPaddingTop() - getPaddingBottom(),
                TextUtils.TruncateAt.END).toString();
        canvas.save();
        canvas.rotate(90);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = -(w / 2f) + (fm.descent - fm.ascent) / 2f - fm.descent;
        canvas.drawText(t, getPaddingTop(), baseline, paint);
        canvas.restore();
    }
}

package uz.fastbest.kartformat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Disk haritası: klasörler soldan sağa sütunlar halinde, her öğe boyutu oranında yükseklik kaplar.
 * Dokununca o öğeye yakınlaşır, iki parmakla büyütülür, sürükleyerek gezilir.
 */
public final class DiskMap {

    public static final int T_DIR = 0, T_FILE = 1, T_REST = 2, T_FREE = 3, T_ROOT = 4;

    /** Haritadaki bir dikdörtgen. y0..y1: kök yüksekliği 0..1 içinde. */
    public static final class Node {
        public String name;
        public long size;
        public int type;
        public int depth;
        public double y0, y1;
        public Node parent;
        public final List<Node> kids = new ArrayList<>();
        public VNode vnode;
    }

    public interface Progress {
        void onItem(String name) throws IOException;
    }

    /** Ağacı kurar. free >= 0 ise kök sütununa "Boş" bölümü eklenir. */
    public static Node build(VNode dir, long free, Progress pr) throws IOException {
        Node root = new Node();
        root.name = dir.name;
        root.type = T_ROOT;
        root.vnode = dir;
        File local = dir.fs.localFile(dir);
        if (local != null) scanLocal(root, local, pr, 0, dir);
        else scanVfs(root, dir, pr, 0);
        long used = root.size;
        Node top = root;
        if (free > 0) {
            // Kök sütunu: [kullanılan klasör][boş alan]
            Node wrap = new Node();
            wrap.name = dir.name;
            wrap.type = T_ROOT;
            wrap.vnode = dir;
            root.type = T_DIR;
            Node f = new Node();
            f.name = "Boş";
            f.type = T_FREE;
            f.size = free;
            wrap.kids.add(root);
            wrap.kids.add(f);
            root.parent = wrap;
            f.parent = wrap;
            wrap.size = used + free;
            top = wrap;
        }
        layout(top, 0, 1, 0);
        return top;
    }

    private static void scanLocal(Node n, File f, Progress pr, int depth, VNode vn) throws IOException {
        File[] list = f.listFiles();
        if (list == null) return;
        if (depth <= 2 && pr != null) pr.onItem(f.getName());
        for (File k : list) {
            Node c = new Node();
            c.name = k.getName();
            c.parent = n;
            c.depth = depth + 1;
            boolean link = false;
            try {
                link = !k.getCanonicalPath().equals(k.getAbsolutePath()) && k.isDirectory();
            } catch (IOException ignored) {
            }
            if (k.isDirectory() && !link && depth < 64) {
                c.type = T_DIR;
                c.vnode = vn == null ? null : vn.fs instanceof LocalFs ? ((LocalFs) vn.fs).node(k) : null;
                scanLocal(c, k, pr, depth + 1, c.vnode);
            } else {
                c.type = T_FILE;
                c.size = k.length();
                c.vnode = vn == null ? null : vn.fs instanceof LocalFs ? ((LocalFs) vn.fs).node(k) : null;
            }
            n.size += c.size;
            n.kids.add(c);
        }
        trim(n);
    }

    private static void scanVfs(Node n, VNode dir, Progress pr, int depth) throws IOException {
        List<VNode> list;
        try {
            list = dir.fs.list(dir);
        } catch (IOException e) {
            return;
        }
        if (depth <= 2 && pr != null) pr.onItem(dir.name);
        for (VNode k : list) {
            Node c = new Node();
            c.name = k.name;
            c.parent = n;
            c.depth = depth + 1;
            c.vnode = k;
            if (k.dir && depth < 64) {
                c.type = T_DIR;
                scanVfs(c, k, pr, depth + 1);
            } else {
                c.type = T_FILE;
                c.size = Math.max(0, k.size);
            }
            n.size += c.size;
            n.kids.add(c);
        }
        trim(n);
    }

    /** Büyükten küçüğe sırala; çok küçükleri tek "…" kutusunda topla. */
    private static void trim(Node n) {
        Collections.sort(n.kids, (a, b) -> Long.compare(b.size, a.size));
        if (n.kids.isEmpty()) return;
        long min = (long) (n.size * 0.006);
        int keep = 0;
        long rest = 0;
        int restCount = 0;
        List<Node> out = new ArrayList<>();
        for (Node k : n.kids) {
            if (keep < 80 && (k.size >= min || n.kids.size() <= 12)) {
                out.add(k);
                keep++;
            } else {
                rest += k.size;
                restCount++;
            }
        }
        if (restCount > 0) {
            Node r = new Node();
            r.name = "… " + restCount;
            r.type = T_REST;
            r.size = rest;
            r.parent = n;
            r.depth = n.depth + 1;
            out.add(r);
        }
        n.kids.clear();
        n.kids.addAll(out);
    }

    private static void layout(Node n, double y0, double y1, int depth) {
        n.y0 = y0;
        n.y1 = y1;
        n.depth = depth;
        if (n.size <= 0 || n.kids.isEmpty()) return;
        double y = y0;
        for (Node k : n.kids) {
            double h = (y1 - y0) * k.size / n.size;
            layout(k, y, y + h, depth + 1);
            y += h;
        }
    }

    // ----------------------------------------------------------------- görünüm

    public interface Listener {
        void onSelect(Node n);

        void onOpen(Node n);
    }

    public static final class MapView extends View {
        private final Node root;
        private final Listener listener;
        private final Paint fill = new Paint();
        private final Paint edge = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sel = new Paint();
        private final RectF r = new RectF();
        private final float dens;
        /** Görünen alan: dikeyde vy0..vy1 (0..1), yatayda vx sütunundan başlayarak. */
        private double vy0 = 0, vy1 = 1, vx = 0;
        private Node selected;
        private ValueAnimator anim;
        private final ScaleGestureDetector scale;
        private final GestureDetector gest;

        public MapView(Context c, Node root, Listener l) {
            super(c);
            this.root = root;
            this.listener = l;
            dens = c.getResources().getDisplayMetrics().density;
            edge.setColor(Theme.LIGHT ? 0xFFFFFFFF : 0xFF1C1D20);
            edge.setStrokeWidth(Math.max(1, dens * 0.8f));
            text.setColor(Color.WHITE);
            text.setTextSize(11 * dens);
            text.setShadowLayer(2 * dens, 0, 0, 0xCC000000);
            sel.setStyle(Paint.Style.STROKE);
            sel.setColor(Theme.LIGHT ? Theme.TXT : Color.WHITE);
            sel.setStrokeWidth(3 * dens);
            selected = root;
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    stopAnim();
                    double f = d.getScaleFactor();
                    double span = (vy1 - vy0) / f;
                    span = Math.max(1e-7, Math.min(1, span));
                    double fy = vy0 + (vy1 - vy0) * d.getFocusY() / Math.max(1, getHeight());
                    double rel = d.getFocusY() / Math.max(1, getHeight());
                    vy0 = fy - span * rel;
                    vy1 = vy0 + span;
                    clamp();
                    invalidate();
                    return true;
                }
            });
            gest = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    stopAnim();
                    double span = vy1 - vy0;
                    vy0 += dy / Math.max(1, getHeight()) * span;
                    vy1 = vy0 + span;
                    vx += dx / colW();
                    clamp();
                    invalidate();
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    Node n = hit(root, e.getX(), e.getY());
                    if (n != null) {
                        selected = n;
                        if (listener != null) listener.onSelect(n);
                        zoomTo(n);
                    }
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    Node n = hit(root, e.getX(), e.getY());
                    if (n != null && listener != null) listener.onOpen(n);
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoomOut();
                    return true;
                }
            });
        }

        public Node selected() {
            return selected;
        }

        private float colW() {
            return Math.max(dens * 90, getWidth() / 3f);
        }

        private int maxDepth(Node n) {
            int m = n.depth;
            for (Node k : n.kids) m = Math.max(m, maxDepth(k));
            return m;
        }

        private void clamp() {
            double span = vy1 - vy0;
            if (vy0 < 0) {
                vy0 = 0;
                vy1 = span;
            }
            if (vy1 > 1) {
                vy1 = 1;
                vy0 = 1 - span;
            }
            double maxX = Math.max(0, maxDepth(root) + 1 - getWidth() / colW());
            vx = Math.max(0, Math.min(maxX, vx));
        }

        private void stopAnim() {
            if (anim != null) anim.cancel();
        }

        /** Öğeyi ekrana sığdırır: kendi sütunu solda, yüksekliği tüm ekran. */
        public void zoomTo(final Node n) {
            stopAnim();
            final double a0 = vy0, a1 = vy1, ax = vx;
            double pad = (n.y1 - n.y0) * 0.02;
            final double b0 = Math.max(0, n.y0 - pad), b1 = Math.min(1, n.y1 + pad);
            final double bx = Math.max(0, n.depth - (n.depth > 0 ? 0.35 : 0));
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(380);
            anim.setInterpolator(new DecelerateInterpolator(1.8f));
            anim.addUpdateListener(v -> {
                float t = (Float) v.getAnimatedValue();
                // yakınlaştırmada logaritmik geçiş daha doğal görünür
                double s0 = a1 - a0, s1 = b1 - b0;
                double s = Math.exp(Math.log(s0) + (Math.log(s1) - Math.log(s0)) * t);
                double c0 = (a0 + a1) / 2, c1 = (b0 + b1) / 2;
                double c = c0 + (c1 - c0) * t;
                vy0 = c - s / 2;
                vy1 = c + s / 2;
                vx = ax + (bx - ax) * t;
                clamp();
                invalidate();
            });
            anim.start();
        }

        public boolean zoomOut() {
            if (selected == null || selected.parent == null) {
                if (vy1 - vy0 < 0.999) {
                    selected = root;
                    zoomTo(root);
                    return true;
                }
                return false;
            }
            selected = selected.parent;
            if (listener != null) listener.onSelect(selected);
            zoomTo(selected);
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            if (!scale.isInProgress()) gest.onTouchEvent(e);
            return true;
        }

        private float sy(double y) {
            return (float) ((y - vy0) / (vy1 - vy0) * getHeight());
        }

        private float sx(int depth) {
            return (float) ((depth - vx) * colW());
        }

        private Node hit(Node n, float x, float y) {
            float x0 = sx(n.depth), x1 = sx(n.depth + 1);
            float y0 = sy(n.y0), y1 = sy(n.y1);
            if (y < y0 || y >= y1) return null;
            if (x >= x0 && x < x1) return n;
            for (Node k : n.kids) {
                Node h = hit(k, x, y);
                if (h != null) return h;
            }
            return null;
        }

        private int color(Node n) {
            switch (n.type) {
                case T_FILE:
                    return 0xFF2F86D6;
                case T_REST:
                    return 0xFFB8692C;
                case T_FREE:
                case T_ROOT:
                    return Theme.ACCENT;
                default:
                    return 0xFFA9AC2C;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawColor(Theme.LIGHT ? 0xFFF3F4F8 : 0xFF2A2B2F);
            draw(cv, root);
            if (selected != null) {
                r.set(sx(selected.depth), sy(selected.y0), sx(selected.depth + 1), sy(selected.y1));
                r.inset(sel.getStrokeWidth() / 2, sel.getStrokeWidth() / 2);
                cv.drawRect(r, sel);
            }
        }

        private void draw(Canvas cv, Node n) {
            float y0 = sy(n.y0), y1 = sy(n.y1);
            if (y1 < 0 || y0 > getHeight()) return;
            float x0 = sx(n.depth), x1 = sx(n.depth + 1);
            if (y1 - y0 >= 0.6f && x1 > 0 && x0 < getWidth()) {
                int c = color(n);
                fill.setShader(new LinearGradient(x0, 0, x1, 0, lighten(c, 1.12f), lighten(c, 0.86f),
                        Shader.TileMode.CLAMP));
                cv.drawRect(x0, Math.max(-2, y0), x1, Math.min(getHeight() + 2, y1), fill);
                if (y1 - y0 > 2.5f * dens) cv.drawLine(x0, y0, x1, y0, edge);
                cv.drawLine(x1, Math.max(0, y0), x1, Math.min(getHeight(), y1), edge);
                float h = y1 - y0;
                if (h > 15 * dens) {
                    float ty = Math.max(y0, 0) + 13 * dens;
                    if (ty < y1 - 2 * dens) {
                        text.setTypeface(n.type == T_DIR || n.type == T_ROOT ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                        String label = n.type == T_FREE ? L.t("Boş") : n.name;
                        cv.drawText(ellipsize(label, x1 - x0 - 8 * dens), x0 + 4 * dens, ty, text);
                        if (h > 28 * dens && ty + 12 * dens < y1) {
                            String sz = DiskOps.sizeText(n.size);
                            if (n.type == T_FREE && n.parent != null && n.parent.size > 0) {
                                sz = Math.round(n.size * 100.0 / n.parent.size) + "%  " + sz + "/"
                                        + DiskOps.sizeText(n.parent.size);
                            }
                            cv.drawText(ellipsize(sz, x1 - x0 - 8 * dens), x0 + 4 * dens, ty + 12 * dens, text);
                        }
                    }
                }
            }
            if (y1 - y0 < 0.6f) return;
            for (Node k : n.kids) draw(cv, k);
        }

        private String ellipsize(String s, float w) {
            if (w <= 0) return "";
            if (text.measureText(s) <= w) return s;
            int n = text.breakText(s, true, Math.max(0, w - text.measureText("…")), null);
            return n <= 0 ? "" : s.substring(0, n) + "…";
        }

        private static int lighten(int c, float f) {
            int r = Math.min(255, (int) (((c >> 16) & 255) * f));
            int g = Math.min(255, (int) (((c >> 8) & 255) * f));
            int b = Math.min(255, (int) ((c & 255) * f));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
}

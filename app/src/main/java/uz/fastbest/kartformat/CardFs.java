package uz.fastbest.kartformat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** USB ile takılan karttaki bir FAT/exFAT bölümü. */
public final class CardFs implements Vfs {

    private final CardVolume vol;
    private final String title;
    private final VNode root;
    private final Runnable onClose;

    public CardFs(CardVolume vol, String title, Runnable onClose) {
        this.vol = vol;
        this.title = title;
        this.onClose = onClose;
        this.root = new VNode(this, null, title, true, 0, 0, "/", vol.root());
    }

    public CardVolume volume() {
        return vol;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public VNode root() {
        return root;
    }

    private static CardVolume.Entry entry(VNode n) {
        return (CardVolume.Entry) n.handle;
    }

    private VNode wrap(VNode parent, CardVolume.Entry e) {
        String p = parent.path.endsWith("/") ? parent.path + e.name : parent.path + "/" + e.name;
        return new VNode(this, parent, e.name, e.dir, e.dir ? 0 : e.size, 0, p, e);
    }

    @Override
    public List<VNode> list(VNode dir) throws IOException {
        List<VNode> out = new ArrayList<>();
        for (CardVolume.Entry e : vol.list(entry(dir))) out.add(wrap(dir, e));
        return out;
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        return vol.openStream(entry(f));
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        return wrap(dir, vol.create(entry(dir), name, in, size, p, c));
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        return wrap(dir, vol.mkdir(entry(dir), name));
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        vol.delete(entry(n), c);
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        return wrap(n.parent, vol.rename(entry(n), newName));
    }

    @Override
    public VNode move(VNode n, VNode dir) {
        return null;
    }

    @Override
    public boolean writable() {
        return vol.writable();
    }

    @Override
    public long freeBytes() {
        try {
            return vol.freeBytes();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public long totalBytes() {
        return vol.totalBytes();
    }

    @Override
    public File localFile(VNode n) {
        return null;
    }

    @Override
    public void close() {
        if (onClose != null) onClose.run();
    }
}

package uz.fastbest.kartformat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** ZIP dosyasının içine klasör gibi göz atma (salt okunur). */
public final class ZipFs implements Vfs {

    private final ZipFile zip;
    private final String title;
    private final VNode root;
    private final Map<String, List<VNode>> children = new HashMap<>();

    public ZipFs(File file) throws IOException {
        this.zip = new ZipFile(file);
        this.title = file.getName();
        this.root = new VNode(this, null, title, true, 0, file.lastModified(), "/", null);
        children.put("/", new ArrayList<VNode>());
        Map<String, VNode> dirs = new HashMap<>();
        dirs.put("/", root);
        Enumeration<? extends ZipEntry> en = zip.entries();
        int count = 0;
        while (en.hasMoreElements()) {
            if (++count > 200000) throw new IOException("ZIP'te çok fazla dosya var");
            ZipEntry e = en.nextElement();
            String[] parts = Extractor.split(e.getName());
            if (parts.length == 0) continue;
            VNode parent = root;
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                path.append('/').append(parts[i]);
                parent = dirNode(dirs, parent, parts[i], path.toString(), e.getTime());
            }
            path.append('/').append(parts[parts.length - 1]);
            if (e.isDirectory()) {
                dirNode(dirs, parent, parts[parts.length - 1], path.toString(), e.getTime());
            } else {
                VNode f = new VNode(this, parent, parts[parts.length - 1], false,
                        Math.max(0, e.getSize()), e.getTime(), path.toString(), e);
                children.get(parent.path).add(f);
            }
        }
    }

    private VNode dirNode(Map<String, VNode> dirs, VNode parent, String name, String path,
                          long time) {
        VNode d = dirs.get(path);
        if (d != null) return d;
        d = new VNode(this, parent, name, true, 0, time, path, null);
        dirs.put(path, d);
        children.put(path, new ArrayList<VNode>());
        children.get(parent.path).add(d);
        return d;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public VNode root() {
        return root;
    }

    @Override
    public List<VNode> list(VNode dir) {
        List<VNode> l = children.get(dir.path);
        return l == null ? new ArrayList<VNode>() : new ArrayList<>(l);
    }

    @Override
    public InputStream open(VNode f) throws IOException {
        return zip.getInputStream((ZipEntry) f.handle);
    }

    private static IOException readOnly() {
        return new IOException("ZIP içi salt okunur; önce dışarı kopyalayın");
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        throw readOnly();
    }

    @Override
    public VNode mkdir(VNode dir, String name) throws IOException {
        throw readOnly();
    }

    @Override
    public void delete(VNode n, DiskOps.Cancel c) throws IOException {
        throw readOnly();
    }

    @Override
    public VNode rename(VNode n, String newName) throws IOException {
        throw readOnly();
    }

    @Override
    public VNode move(VNode n, VNode dir) {
        return null;
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public long freeBytes() {
        return -1;
    }

    @Override
    public long totalBytes() {
        return -1;
    }

    @Override
    public File localFile(VNode n) {
        return null;
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
        }
    }
}

package uz.fastbest.kartformat;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 7z ve RAR (RAR4) arşivlerinin içine klasör gibi göz atma (salt okunur). */
public final class ArchiveFs implements Vfs {

    private final File file;
    private final File cacheDir;
    private final String title;
    private final VNode root;
    private final Map<String, List<VNode>> children = new HashMap<>();
    private final Map<String, VNode> dirs = new HashMap<>();
    private SevenZFile seven;
    private Archive rar;

    public ArchiveFs(File file, File cacheDir) throws IOException {
        this.file = file;
        this.cacheDir = cacheDir;
        this.title = file.getName();
        this.root = new VNode(this, null, title, true, 0, file.lastModified(), "/", null);
        children.put("/", new ArrayList<VNode>());
        dirs.put("/", root);
        String n = file.getName().toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        if (n.endsWith(".7z")) {
            seven = new SevenZFile(file);
            for (SevenZArchiveEntry e : seven.getEntries()) {
                if (++count > 200000) throw new IOException("Arşivde çok fazla dosya var");
                long t = e.getHasLastModifiedDate() ? e.getLastModifiedDate().getTime() : 0;
                add(e.getName(), e.isDirectory(), e.getSize(), t, e);
            }
        } else if (n.endsWith(".rar")) {
            try {
                rar = new Archive(file);
            } catch (Exception ex) {
                throw new IOException("RAR açılamadı (RAR5 desteklenmiyor olabilir): " + ex.getMessage());
            }
            if (rar.isEncrypted()) throw new IOException("Şifreli RAR desteklenmiyor");
            for (FileHeader h : rar.getFileHeaders()) {
                if (++count > 200000) throw new IOException("Arşivde çok fazla dosya var");
                String name = h.getFileName().replace('\\', '/');
                long t = h.getMTime() == null ? 0 : h.getMTime().getTime();
                add(name, h.isDirectory(), h.getFullUnpackSize(), t, h);
            }
        } else {
            throw new IOException("Desteklenmeyen arşiv");
        }
    }

    private void add(String name, boolean isDir, long size, long time, Object handle) {
        String[] parts = Extractor.split(name);
        if (parts.length == 0) return;
        VNode parent = root;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            path.append('/').append(parts[i]);
            parent = dirNode(parent, parts[i], path.toString(), time);
        }
        path.append('/').append(parts[parts.length - 1]);
        if (isDir) {
            dirNode(parent, parts[parts.length - 1], path.toString(), time);
        } else {
            children.get(parent.path).add(new VNode(this, parent, parts[parts.length - 1], false,
                    Math.max(0, size), time, path.toString(), handle));
        }
    }

    private VNode dirNode(VNode parent, String name, String path, long time) {
        VNode d = dirs.get(path);
        if (d != null) return d;
        d = new VNode(this, parent, name, true, 0, time, path, null);
        dirs.put(path, d);
        children.put(path, new ArrayList<VNode>());
        children.get(parent.path).add(d);
        return d;
    }

    @Override public String title() { return title; }

    @Override public VNode root() { return root; }

    @Override
    public List<VNode> list(VNode dir) {
        List<VNode> l = children.get(dir.path);
        return l == null ? new ArrayList<VNode>() : new ArrayList<>(l);
    }

    /** Arşivler sırayla okunduğundan, öğe önce önbelleğe çıkarılır, oradan okunur. */
    @Override
    public synchronized InputStream open(VNode f) throws IOException {
        File out = File.createTempFile("arc", ".tmp", cacheDir);
        out.deleteOnExit();
        try (OutputStream os = new FileOutputStream(out)) {
            if (seven != null) {
                try (InputStream in = seven.getInputStream((SevenZArchiveEntry) f.handle)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
            } else {
                try {
                    rar.extractFile((FileHeader) f.handle, os);
                } catch (Exception e) {
                    throw new IOException("RAR'dan çıkarılamadı: " + e.getMessage());
                }
            }
        }
        final File tmp = out;
        return new FileInputStream(tmp) {
            @Override
            public void close() throws IOException {
                super.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        };
    }

    private static IOException readOnly() {
        return new IOException("Arşiv içi salt okunur; önce dışarı kopyalayın");
    }

    @Override
    public VNode create(VNode dir, String name, InputStream in, long size,
                        Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        throw readOnly();
    }

    @Override public VNode mkdir(VNode dir, String name) throws IOException { throw readOnly(); }

    @Override public void delete(VNode n, DiskOps.Cancel c) throws IOException { throw readOnly(); }

    @Override public VNode rename(VNode n, String newName) throws IOException { throw readOnly(); }

    @Override public VNode move(VNode n, VNode dir) { return null; }

    @Override public boolean writable() { return false; }

    @Override public long freeBytes() { return -1; }

    @Override public long totalBytes() { return -1; }

    @Override public File localFile(VNode n) { return null; }

    @Override
    public void close() {
        try {
            if (seven != null) seven.close();
            if (rar != null) rar.close();
        } catch (IOException ignored) {
        }
    }

    File archiveFile() {
        return file;
    }
}

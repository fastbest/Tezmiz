package uz.fastbest.kartformat;

/** Bir dosya ya da klasör. */
public final class VNode {
    public final Vfs fs;
    public final String name;
    public final boolean dir;
    public final long size;
    public final long modified;
    public final String path;
    public final VNode parent;
    final Object handle;

    VNode(Vfs fs, VNode parent, String name, boolean dir, long size, long modified, String path,
          Object handle) {
        this.fs = fs;
        this.parent = parent;
        this.name = name;
        this.dir = dir;
        this.size = size;
        this.modified = modified;
        this.path = path;
        this.handle = handle;
    }

    public boolean hidden() {
        return name.startsWith(".");
    }

    public String ext() {
        int dot = name.lastIndexOf('.');
        return dot > 0 && !dir ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** Aynı dosya sisteminde aynı yer mi? */
    public boolean same(VNode o) {
        return o != null && o.fs == fs && o.path.equals(path);
    }

    /** Bu düğüm verilen klasörün içinde mi (ya da kendisi mi)? */
    public boolean inside(VNode dir) {
        if (dir == null || dir.fs != fs) return false;
        String a = path.endsWith("/") ? path : path + "/";
        String b = dir.path.endsWith("/") ? dir.path : dir.path + "/";
        return a.startsWith(b);
    }
}

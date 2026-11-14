package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PS2 oyun ISO'sunu Open PS2 Loader'ın (OPL) beklediği adla karta kopyalar. */
final class Ps2Games {

    private static final Pattern ID = Pattern.compile("([A-Z]{4})[_-](\\d{3})\\.?(\\d{2})");
    private static final long CD_MAX = 750L * 1000 * 1000;

    static final class Game {
        String id;
        String title;
        boolean dvd;
        long size;

        String fileName() {
            return id + "." + title + ".iso";
        }
    }

    private Ps2Games() {
    }

    static Game inspect(RandomSource iso, String fileName) throws IOException {
        UdfReader.Volume v;
        try {
            v = Iso9660Reader.read(iso);
        } catch (IOException e) {
            v = IsoImage.open(iso);
        }
        UdfReader.Node cnf = v.root.child("SYSTEM.CNF");
        if (cnf == null || cnf.dir || cnf.size > 64 * 1024) {
            throw new IOException("Bu bir PS2 oyun ISO'su değil (SYSTEM.CNF bulunamadı)");
        }
        byte[] d = new byte[(int) cnf.size];
        new UdfReader.FileView(iso, cnf).readFully(0, d, 0, d.length);
        String text = new String(d, StandardCharsets.ISO_8859_1);
        String boot = null;
        for (String line : text.split("[\\r\\n]+")) {
            String l = line.trim();
            if (l.toUpperCase(Locale.ROOT).startsWith("BOOT2")) boot = l;
        }
        if (boot == null) {
            if (text.toUpperCase(Locale.ROOT).contains("BOOT")) {
                throw new IOException("Bu bir PS1 oyunu; OPL'de PS1 oyunları POPS ile çalışır ve "
                        + "bu araç desteklemiyor");
            }
            throw new IOException("SYSTEM.CNF içinde BOOT2 satırı yok");
        }
        Matcher m = ID.matcher(boot.toUpperCase(Locale.ROOT));
        if (!m.find()) throw new IOException("Oyun kodu okunamadı: " + boot);
        Game g = new Game();
        g.id = m.group(1) + "_" + m.group(2) + "." + m.group(3);
        g.size = iso.size();
        g.dvd = g.size > CD_MAX;
        g.title = title(fileName, g.id);
        return g;
    }

    /** Dosya adından oyun adı: uzantı ve baştaki kod atılır, en çok 32 karakter. */
    static String title(String fileName, String id) {
        String t = fileName == null ? "" : fileName;
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        Matcher m = ID.matcher(t.toUpperCase(Locale.ROOT));
        if (m.find() && m.start() == 0) t = t.substring(m.end());
        t = t.replaceAll("^[ ._-]+", "").replaceAll("[\\\\/:*?\"<>|]", "").trim();
        String noTags = t.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").trim();
        if (!noTags.isEmpty()) t = noTags;      // (USA), [v1.03] gibi etiketler atılır
        if (t.isEmpty()) t = id;
        if (t.length() > 32) t = t.substring(0, 32).trim();
        return t;
    }

    /** Oyunu kartın ilk FAT/exFAT bölümüne kopyalar. */
    static String copy(BlockDevice dev, RandomSource iso, Game g, Fat32Formatter.Progress p,
                       DiskOps.Cancel c) throws IOException {
        CardVolume vol = CardVolume.open(dev);
        if (!vol.writable()) throw new IOException("Kart yazılabilir değil");
        boolean fat = !vol.type().equals("exFAT");
        if (fat && g.size > Fat32Builder.MAX_FILE) {
            throw new IOException("Oyun 4 GB'tan büyük; FAT32'ye sığmaz. Kartı exFAT olarak "
                    + "biçimlendirin (OPL 1.2 ve üstü exFAT'ı okur).");
        }
        String folder = g.dvd ? "DVD" : "CD";
        CardVolume.Entry dir = null;
        for (CardVolume.Entry e : vol.list(vol.root())) {
            if (e.dir && e.name.equalsIgnoreCase(folder)) dir = e;
        }
        if (dir == null) dir = vol.mkdir(vol.root(), folder);
        for (CardVolume.Entry e : vol.list(dir)) {
            if (!e.dir && e.name.equalsIgnoreCase(g.fileName())) {
                throw new IOException("Bu oyun kartta zaten var: " + folder + "/" + e.name);
            }
        }
        if (vol.freeBytes() < g.size) throw new IOException("Kartta yeterli yer yok");
        CardVolume.Entry e = vol.create(dir, g.fileName(), new IsoImage.Stream(iso, 0, g.size),
                g.size, p, c);
        return "Tamamlandı: " + folder + "/" + e.name + "\n\nKartı PS2'ye takıp OPL'de "
                + (vol.type().equals("exFAT") ? "BDM/USB" : "USB") + " oyunlarını açın.";
    }
}

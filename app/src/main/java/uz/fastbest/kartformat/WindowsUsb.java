package uz.fastbest.kartformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Windows kurulum ISO'sundan UEFI ile açılan FAT32 kurulum kartı hazırlar. */
public final class WindowsUsb {

    /** FAT32'de bir dosya en çok 4 GB - 1 bayt olabilir; parçalar biraz daha küçük tutulur. */
    static final long PART_LIMIT = 4000L * 1024 * 1024;

    public static final class Info {
        UdfReader.Node root;
        String label;
        String arch;
        long contentBytes;
        boolean split;
        int parts;
        boolean hasUnattend;
        private List<Content> swm;
        private UdfReader.Node install;
    }

    private WindowsUsb() {
    }

    public static Info inspect(RandomSource iso) throws IOException {
        UdfReader.Volume vol = UdfReader.read(iso);
        UdfReader.Node root = vol.root;
        UdfReader.Node sources = root.child("sources");
        if (sources == null || !sources.dir) {
            throw new IOException("Bu bir Windows kurulum ISO'su değil (sources klasörü yok)");
        }
        UdfReader.Node install = sources.child("install.wim");
        if (install == null) install = sources.child("install.esd");
        if (install == null) install = sources.child("install.swm");
        if (install == null || install.dir) {
            throw new IOException("ISO'da install.wim / install.esd bulunamadı");
        }
        UdfReader.Node efiBoot = root.path("efi", "boot");
        String arch = null;
        if (efiBoot != null) {
            if (efiBoot.child("bootx64.efi") != null) arch = "amd64";
            else if (efiBoot.child("bootaa64.efi") != null) arch = "arm64";
            else if (efiBoot.child("bootia32.efi") != null) arch = "x86";
        }
        if (arch == null) {
            throw new IOException("Bu ISO UEFI ile açılmayı desteklemiyor (efi\\boot dosyası yok)");
        }

        Info info = new Info();
        info.root = root;
        info.label = vol.label;
        info.arch = arch;
        info.install = install;
        info.hasUnattend = root.child("autounattend.xml") != null;
        info.contentBytes = total(root);
        checkSizes(root, install);

        if (install.size > Fat32Builder.MAX_FILE) {
            if (!install.name.equalsIgnoreCase("install.wim")) {
                throw new IOException(install.name + " 4 GB'tan büyük ve bölünemiyor");
            }
            info.swm = WimSplitter.plan(new UdfReader.FileView(iso, install), PART_LIMIT);
            info.split = true;
            info.parts = info.swm.size();
        }
        return info;
    }

    private static long total(UdfReader.Node n) {
        long s = n.dir ? 0 : n.size;
        for (UdfReader.Node k : n.kids) s += total(k);
        return s;
    }

    private static void checkSizes(UdfReader.Node n, UdfReader.Node install) throws IOException {
        for (UdfReader.Node k : n.kids) {
            if (k.dir) {
                checkSizes(k, install);
            } else if (k != install && k.size > Fat32Builder.MAX_FILE) {
                throw new IOException(k.name + " 4 GB'tan büyük; FAT32'ye yazılamaz");
            }
        }
    }

    public static String write(BlockDevice dev, RandomSource iso, Info info, boolean gpt,
                               boolean bypassChecks, boolean localAccount,
                               Fat32Formatter.Progress p, DiskOps.Cancel c) throws IOException {
        Fat32Builder.Node root = copy(iso, info.root, info);
        boolean wroteUnattend = false;
        if ((bypassChecks || localAccount) && !info.hasUnattend) {
            byte[] xml = unattend(info.arch, bypassChecks, localAccount)
                    .getBytes(StandardCharsets.UTF_8);
            root.kids.add(Fat32Builder.Node.file("autounattend.xml", new Content.Bytes(xml)));
            wroteUnattend = true;
        }

        String label = Fat32Formatter.cleanLabel(info.label);
        if (label.isEmpty()) label = "WINDOWS";
        DiskFormatter.layout(dev, gpt, 0x0C, label, new Fat32Builder(root, label, c), p);

        StringBuilder sb = new StringBuilder("Tamamlandı. Windows kurulum kartı hazır.\n\n");
        if (info.split) {
            sb.append("install.wim ").append(info.parts).append(" parçaya bölündü (install.swm).\n");
        }
        if (wroteUnattend) {
            sb.append(bypassChecks ? "Windows 11 donanım denetimleri atlanacak.\n" : "");
            sb.append(localAccount ? "Kurulumda internetsiz/yerel hesap seçilebilecek.\n" : "");
        } else if (bypassChecks || localAccount) {
            sb.append("ISO'da zaten autounattend.xml olduğu için ayarlar eklenmedi.\n");
        }
        sb.append("\nBilgisayarı karttan açmak için açılış menüsünden (F12, F11, F9 ya da Esc) "
                + "\"UEFI: USB\" seçeneğini seçin.");
        return sb.toString();
    }

    private static Fat32Builder.Node copy(RandomSource iso, UdfReader.Node n, Info info) {
        Fat32Builder.Node out = Fat32Builder.Node.dir(n.name);
        for (UdfReader.Node k : n.kids) {
            if (k.dir) {
                out.kids.add(copy(iso, k, info));
            } else if (k == info.install && info.split) {
                for (int i = 0; i < info.swm.size(); i++) {
                    String name = i == 0 ? "install.swm" : "install" + (i + 1) + ".swm";
                    out.kids.add(Fat32Builder.Node.file(name, info.swm.get(i)));
                }
            } else {
                out.kids.add(Fat32Builder.Node.file(k.name,
                        new Content.Range(new UdfReader.FileView(iso, k), 0, k.size)));
            }
        }
        return out;
    }

    static String unattend(String arch, boolean bypassChecks, boolean localAccount) {
        String comp = " processorArchitecture=\"" + arch + "\" publicKeyToken=\"31bf3856ad364e35\""
                + " language=\"neutral\" versionScope=\"nonSxS\""
                + " xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n");
        x.append("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\">\r\n");
        if (bypassChecks) {
            x.append("  <settings pass=\"windowsPE\">\r\n");
            x.append("    <component name=\"Microsoft-Windows-Setup\"").append(comp).append(">\r\n");
            x.append("      <RunSynchronous>\r\n");
            String[] keys = {"BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck"};
            for (int i = 0; i < keys.length; i++) {
                x.append("        <RunSynchronousCommand wcm:action=\"add\"><Order>").append(i + 1)
                        .append("</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v ")
                        .append(keys[i]).append(" /t REG_DWORD /d 1 /f</Path>"
                        + "</RunSynchronousCommand>\r\n");
            }
            x.append("      </RunSynchronous>\r\n");
            x.append("    </component>\r\n");
            x.append("  </settings>\r\n");
        }
        if (localAccount) {
            x.append("  <settings pass=\"specialize\">\r\n");
            x.append("    <component name=\"Microsoft-Windows-Deployment\"").append(comp)
                    .append(">\r\n");
            x.append("      <RunSynchronous>\r\n");
            x.append("        <RunSynchronousCommand wcm:action=\"add\"><Order>1</Order>"
                    + "<Path>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE"
                    + " /v BypassNRO /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>\r\n");
            x.append("      </RunSynchronous>\r\n");
            x.append("    </component>\r\n");
            x.append("  </settings>\r\n");
        }
        x.append("</unattend>\r\n");
        return x.toString();
    }
}

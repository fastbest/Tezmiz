# Kart Format

X-plore benzeri **iki panelli dosya yöneticisi** ve OTG ile takılan microSD kart / USB bellekler
için **root'suz**, reklamsız araç kutusu.

## Dosya yöneticisi
- İki panel (yatayda yan yana, dikeyde etkin panel geniş, diğeri kenarda şerit); paneller arası
  kopyalama ve taşıma.
- Konumlar: dahili depolama, SD kart, **USB kart (OTG, FAT/exFAT)**, ZIP içi, FTP sunucuları, şifreli Kasa.
- İşlemler: çoklu seçim (uzun bas), yeni klasör, yeniden adlandır, kopyala, taşı, sil, ZIP oluştur,
  arşiv aç (zip, 7z, tar, tar.gz/xz/bz2, ISO), paylaş, birlikte aç, özellikler, tümünü seç.
- Sıralama (ad, boyut, tarih, tür), gizli dosyalar, arama, sık kullanılanlar, küçük resimler.
- Son dosyalar, uygulama yöneticisi (aç, APK yedekle/paylaş, kaldır), disk analizi.
- WiFi dosya paylaşımı: aynı ağdaki bilgisayardan tarayıcıyla indirme/yükleme (kodlu).
- Kasa: AES-256 ile şifrelenmiş, parolalı dosya deposu.
- Görüntüleyiciler: resim (yakınlaştırma, kaydırma), metin düzenleyici, hex.

## USB kart araçları
- Biçimlendir (FAT32, exFAT, NTFS, ext4; MBR/GPT; küme boyutu), bölüm sihirbazı (en çok 4 bölüm).
- İmaj yaz (ham, .gz, .xz, .bz2, .zip) ve doğrula; yedek al; sil; hız testi; kart bilgisi.
- Windows kurulum kartı (UEFI; MBR'de eski BIOS da), FreeDOS açılış kartı, PS2 oyunu ekle (OPL),
  birleştirme (defrag).

Telefonun kendi kart yuvasındaki kartı biçimlendiremez (Android buna root'suz izin vermez).

APK: bu deponun **Releases** bölümündeki `KartFormat.apk`.

## Kullanılan açık kaynak yazılımlar

| Yazılım | Ne için | Lisans |
|---|---|---|
| [ntfs-3g](https://github.com/tuxera/ntfs-3g) (`mkntfs`) | NTFS biçimlendirme; derlemede `tools/build-mkntfs.sh` ile derlenir | GPL-2.0 |
| [Rufus](https://github.com/pbatard/rufus) / ms-sys | BIOS açılış kodları ve FreeDOS; derlemede `tools/fetch-bootcode.sh` ile indirilir | GPL-3.0 / GPL-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | zip, tar, 7z, bzip2 | Apache-2.0 |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | FTP | Apache-2.0 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | WiFi dosya paylaşımı | BSD-3 |
| [XZ for Java](https://tukaani.org/xz/java.html) | .xz | 0BSD |
| [AndroidX Core](https://developer.android.com/jetpack/androidx) | FileProvider | Apache-2.0 |

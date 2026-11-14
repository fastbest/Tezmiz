# Kart Format

OTG kart okuyucuyla takılan microSD kart ve USB bellekler için **root'suz**, reklamsız
Android araç kutusu.

- **Dosyalar:** göz atma, telefona dosya/klasör kaydetme, telefondan karta ekleme, silme,
  klasör oluşturma; zip / 7z / tar(.gz/.xz/.bz2) arşivlerini ve ISO'ları karta açma
  (FAT12/16/32 ve exFAT).
- **Biçimlendir:** FAT32, exFAT, NTFS ya da ext4; MBR ya da GPT; küme boyutu seçimi.
- **Bölüm sihirbazı:** en çok 4 bölüm, her biri ayrı dosya sistemiyle.
- **İmaj yaz:** ISO / IMG dosyasını (ham, .gz, .xz, .bz2, .zip) karta yazar ve doğrular.
- **Windows kurulum kartı:** Windows 10/11 ISO'sundan UEFI ile açılan kurulum kartı;
  4 GB'tan büyük install.wim otomatik bölünür, Windows 11 denetimleri isteğe bağlı atlanır.
- **PS2 oyunu ekle:** ISO'dan oyun kodunu okuyup OPL'nin beklediği adla `CD/` ya da `DVD/`
  klasörüne kopyalar.
- **Yedek al / Sil / Hız testi / Kart bilgisi.**

Telefonun kendi kart yuvasındaki kartla çalışmaz (Android buna root'suz izin vermez).

APK: bu deponun **Releases** bölümündeki `KartFormat.apk`.

## Kullanılan açık kaynak yazılımlar

| Yazılım | Ne için | Lisans |
|---|---|---|
| [ntfs-3g](https://github.com/tuxera/ntfs-3g) (`mkntfs`) | NTFS biçimlendirme; derleme sırasında `tools/build-mkntfs.sh` ile kaynaktan derlenir | GPL-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | zip, tar, 7z, bzip2 | Apache-2.0 |
| [XZ for Java](https://tukaani.org/xz/java.html) | .xz | 0BSD / kamu malı |

# Kart Format

OTG kart okuyucuyla takılan microSD kart ve USB bellekler için **root'suz**, reklamsız
Android araç kutusu.

- **Dosyalar:** karttaki dosyalara göz atma, telefona kaydetme (dosya ya da klasör), telefondan
  karta ekleme, silme, klasör oluşturma (FAT12/16/32 ve exFAT).
- **Biçimlendir:** FAT32 ya da exFAT; MBR ya da GPT bölüm tablosu.
- **İmaj yaz:** ISO / IMG dosyasını (ham, `.gz` ya da `.zip`) karta yazar ve doğrular.
- **Yedek al:** kartın tamamını `.img.gz` dosyasına kopyalar; "İmaj yaz" ile geri yüklenir.
- **Windows kurulum kartı:** Windows 10/11 ISO'sundan UEFI ile açılan FAT32 kurulum kartı;
  4 GB'tan büyük install.wim otomatik bölünür, Windows 11 denetimleri isteğe bağlı atlanır.
- **Sil:** hızlı ya da tam silme.
- **Hız testi:** okuma/yazma hızını karttaki veriyi değiştirmeden ölçer.
- **Kart bilgisi:** bölüm tablosu ve dosya sistemini gösterir.

Telefonun kendi kart yuvasındaki kartla çalışmaz (Android buna root'suz izin vermez).

APK: bu deponun **Releases** bölümündeki `KartFormat.apk`.

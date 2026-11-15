# Kart Format — X-plore'a göre eksikler ve yapılış planı

## Durum (sürüm 5.9)
Yapıldı: A1–A5, B1–B9 (açık tema hariç), C1–C6, C7 (yalnız WebDAV), C8–C14.
Kalan: açık tema (renk sabitleri `final`, yüzlerce sabit renk var — ayrı iş);
Google Drive / Dropbox / OneDrive (API anahtarı ve kullanıcı onayı gerekir);
C15 root (yapılmayacak). Hepsi derlendi, telefonda denenmedi.

Bu dosya bir iş listesidir. Kaynağı: kullanıcının gönderdiği X-plore videosu ve ekran
görüntüleri ile X-plore hakkında genel bilgi. X-plore burada çalıştırılıp tek tek
doğrulanmadı; "isteğe bağlı" yazan maddeler kesin değildir.

## Genel kurallar (her iş için)

- Ana dosya: `app/src/main/java/uz/fastbest/kartformat/ExplorerActivity.java`
  (`Pane`, `Row`, `GuideCard`, `TreeAdapter`, `TNode`, `toggle()`, `setActive()`,
  `applyPanePos()`, `updateChip()`, `buildToolbar()`, `showMenu()`, `loadThumb()`, `openFile()`).
- X-plore'un simgesi, resmi, adı kopyalanmaz. Yeni simgeler `tools/icons/` içindeki
  Python betikleriyle çizilir: `python3 build.py vd app/src/main/res/drawable`.
- Arayüz XML değil, Java ile kuruluyor; aynı tarz sürdürülür. Renk sabitleri dosyanın başında.
- Her işten sonra `app/build.gradle` içinde `versionCode` +1 ve `versionName` artırılır,
  commit + push yapılır, GitHub Actions derlemesinin "success" olduğu ve
  `latest` sürümündeki `KartFormat.apk` tarihinin yenilendiği kontrol edilir.
- İmza sabit (`app/kartformat.jks`); değiştirilmez, yoksa güncelleme üstüne kurulmaz.
- Telefonda deneme imkânı yok; kullanıcıya her seferinde "derlendi, telefonda denenmedi" denir.
- Ayarlar `prefs` (SharedPreferences) içinde tutulur; mevcut anahtarlar:
  `hidden`, `sort`, `desc`, `systemApps`, `favorites`, `ftp`, `vaultSalt`, `vaultVer`.

---

## A. Geçiş efektleri

### A1. Klasör açılıp kapanırken satırların kayarak gelmesi  (öncelik: 1)
Şu an: `pane.refresh()` → `notifyDataSetChanged()`, satırlar bir anda beliriyor.
X-plore'da alt öğeler yukarıdan aşağı açılarak geliyor, kapanırken toplanıyor.

Yapılış:
1. `build.gradle`: `implementation 'androidx.recyclerview:recyclerview:1.3.2'`.
2. `Pane.list` → `RecyclerView` + `LinearLayoutManager`; `TreeAdapter` →
   `RecyclerView.Adapter<Holder>`; `Row` aynen `Holder.itemView` olarak kullanılır.
   `setHasStableIds(true)`, kimlik için her `TNode`'a artan bir `long id` verilir.
3. `toggle()` içinde tam yenileme yerine: açarken
   `notifyItemRangeInserted(pos + 1, eklenenSayı)`, kapatırken
   `notifyItemRangeRemoved(pos + 1, silinenSayı)`, ayrıca `notifyItemChanged(pos)`.
   Diğer bütün yerlerde `DiffUtil.calculateDiff` ile eski/yeni `visible` karşılaştırılır.
4. `DefaultItemAnimator`: add/remove 180 ms, change 0 ms (titreme olmasın).
5. Çerçeve ve ağaç çizgileri komşu satırlara bağlı olduğundan, aç/kapa sonrası
   `notifyItemRangeChanged(0, getItemCount(), "guide")` (payload ile, animasyonsuz) çağrılır.
6. Tıklama/uzun basma `itemView.setOnClickListener` ile; hızlı kaydırma için
   `RecyclerView` fast-scroll drawable'ları programla verilir ya da kaldırılır.
7. `list.setSelection(n)` çağrıları `scrollToPositionWithOffset(n, 0)` olur.

### A2. Panel kaydırmanın parmağı takip etmesi  (öncelik: 2)
Şu an: parmak bırakılınca 280 ms'lik animasyon başlıyor. X-plore'da panel parmakla
birlikte sürükleniyor, bırakınca yakın tarafa oturuyor.

Yapılış (`onCreate` içindeki `body` FrameLayout'un `onTouchEvent`'i):
- `ACTION_MOVE`: `panePos = clamp(başlangıçPos - dx / (genişlik - dp(60)), 0, 1)`;
  `applyPanePos(panePos)`.
- `ACTION_UP`: `VelocityTracker` ile hız; hız > 600dp/sn ise yöne göre, değilse
  `panePos >= 0.5` ise 1, yoksa 0 hedeflenir. Hedefe `ValueAnimator` (kalan mesafeye
  orantılı süre, en çok 250 ms). Animasyon bitince `active` güncellenir, `updateToolbar()`.
- `setActive()` içindeki animasyon kodu ortak bir `animatePaneTo(float hedef)` metoduna alınır.

### A3. Seçili satır çerçevesinin geçişi  (öncelik: düşük)
Başka satıra dokununca turuncu çerçeve 120 ms'de solarak eskisinden çıkıp yenisine girsin:
`GuideCard`'a `frameAlpha` alanı, `ValueAnimator` ile 0→255.

### A4. Ekranlar arası geçiş  (öncelik: düşük)
`openFile()` ile açılan `ImageViewerActivity`, `TextEditorActivity`, `HexViewerActivity`
ve `MainActivity` için `overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)`;
kapanışta (`finish()`) da aynısı.

### A5. Panel değişince listenin yerinde kalması
Kaydırma konumu panel başına korunur (şu an korunuyor mu diye kontrol edilir);
etkin panele geçince `current` satırı görünür değilse ona kaydırılır.

---

## B. Görünüm farkları

### B1. Parçalı yol çubuğu (breadcrumb)  (öncelik: 1)
X-plore: "Dahili depolam… ❯ DCIM" şeklinde ok biçimli ayrı parçalar; her parça dokunulabilir.
Bizde: tek mavi kutu, içinde "›".
Yapılış: `Pane.chip` (TextView) yerine yatay `LinearLayout` içinde parçalar
(`HorizontalScrollView` içinde). Her parça: sağ ucu ok biçimli arka plan — `Path` ile
çizen küçük bir `Drawable` sınıfı; son parça parlak mavi (`CHIP`), öncekiler koyu mavi.
`updateChip()`: `current`'tan köke kadar ata listesi çıkarılır; en fazla 3 parça,
fazlası baştan kısaltılır. Parçaya dokununca o `TNode` `current` olur ve listeye kaydırılır.

### B2. Seçim işareti  (öncelik: 1)
X-plore: sağda soluk gri "✓"; seçilince renkli. Bizde: boş kare kutu.
Yapılış: `Row.bind()` içinde `check` için arka plan kutusu kaldırılır; her zaman
`ic_check` gösterilir: seçili değilken `setColorFilter(0xFF4A4D52)`, seçiliyken `ACCENT`.
Boyut 24dp.

### B3. Klasörlerde her zaman klasör/dosya sayısı  (öncelik: 2)
X-plore her klasörün altında "📁 4  📄 5" gösteriyor; bizde kapalı klasörde tarih var.
Yapılış: `TNode.dirs/files` kapalı klasörler için de doldurulur. `LocalFs` için arka
plan iş parçacığında `File.list()` ile sayılır (tek seviyeli, `LruCache<String,int[]>`),
bitince ilgili satır yenilenir. Sayı hazırsa `counts`, değilse tarih gösterilir.
USB/FTP/ZIP için sayım yapılmaz (yavaş).

### B4. Özel klasör rozetleri  (öncelik: 2)
DCIM, Download, Music, Movies, Pictures, Documents klasörlerinde klasör simgesinin sağ
altında küçük rozet (kamera, indirme oku, nota, oynat, resim, belge).
Yapılış: `tools/icons` ile `ic_badge_*.xml` çizilir; `Row.iconBox` içine 14dp'lik ikinci
bir `ImageView` (Gravity.BOTTOM|END). Yalnızca depolama kökünün birinci seviyesinde, ada göre.

### B5. Arka plan katmanları  (öncelik: 2)
X-plore: kök satırlar (depolama, FTP…) gri zeminde, açılan içerik siyah zeminde;
böylece açık bölüm hemen seçiliyor.
Yapılış: `Row.bind()`: `n.depth == 0` → `root.setBackgroundColor(0xFF34363B)`,
diğerleri `0xFF101113`. Ayırıcı çizgi rengi buna göre ayarlanır.

### B6. Küçük resimler  (öncelik: 2)
Bizde yalnız resim dosyaları. Eklenmeli (`loadThumb()` genişletilir, aynı önbellek):
- Video: `ThumbnailUtils.createVideoThumbnail` / `MediaMetadataRetriever.getFrameAtTime`.
- APK: `PackageManager.getPackageArchiveInfo` + `applicationInfo.loadIcon` (sourceDir ayarlanarak).
- Ses: `MediaMetadataRetriever.getEmbeddedPicture()`.
- PDF: `PdfRenderer` ile ilk sayfa.

### B7. Görünüm ayarları ekranı  (öncelik: 1)
Bizde ayar ekranı yok (menüde yalnız "Gizli dosyaları göster"). Yeni `SettingsActivity`
(Java ile kurulmuş basit liste) ya da menüden açılan diyalog; hepsi `prefs`'te:
| Ayar | Anahtar | Uygulandığı yer |
|---|---|---|
| Tema: koyu / açık | `theme` | Renk sabitleri `final` olmaktan çıkarılıp `applyTheme()` ile atanır; değişince `recreate()` |
| Yazı boyutu: küçük/orta/büyük | `fontScale` (0.85/1/1.15) | `Row` içindeki bütün `setTextSize` çarpılır |
| Satır yüksekliği | `rowHeight` (36/40/48dp) | `card.setMinimumHeight`, simge kutusu orantılı |
| Küçük resimleri göster | `thumbs` | `loadThumb()` çağrısı |
| Gizli dosyalar | `hidden` | var |
| Klasörler önce / sıralama | `sort`, `desc`, `foldersFirst` | sıralama kodu (satır ~1600) |
| Ağaç çizgileri | `guides` | `GuideCard.onDraw` |
| Araç çubuğu yazıları | `toolLabels` | `buildToolbar()` |
| Araç çubuğu düğmeleri (hangileri, sırası) | `tools` | `buildToolbar()`; çoklu seçim diyaloğu |
| Açılışta son klasörler | `lastPath0`, `lastPath1` | `onPause` kaydeder, `onCreate` açar |
| Çıkışta sor | `confirmExit` | `onBackPressed` |
| Tam ekran | `fullscreen` | pencere bayrakları |

### B8. "Göster" satırı  (öncelik: 3)
X-plore'da kök listenin sonunda "Göster": hangi kök öğelerin (FTP, Uygulama yöneticisi,
Kasa, WiFi…) görüneceği seçiliyor.
Yapılış: kök listeye `special("show", K_ACTION, …)`; dokununca çoklu seçim diyaloğu,
sonuç `prefs` `rootItems` içinde; kök listeyi kuran kod (satır ~1340–1390) buna bakar.

### B9. Araç çubuğu ölçüleri
X-plore sütunu ~58dp, simgeler ~34dp, yazılar ~9sp ve iki satıra bölünebiliyor,
düğmeler arası ince çizgi. Bizdeki ölçüler ekran görüntüsüyle yan yana karşılaştırılıp
eşitlenir; etkin olmayan düğmeler bizde çok soluk (alpha 0.28) → 0.45.

---

## C. Eksik özellikler

| # | Özellik | Nasıl | Öncelik |
|---|---|---|---|
| C1 | Müzik çalar | Yeni `MusicActivity`: `MediaPlayer`, klasördeki sıradaki/önceki, ilerleme çubuğu, kapak resmi | 2 |
| C2 | Video oynatıcı | Yeni `VideoActivity`: `VideoView` + `MediaController`; `.srt` altyazı isteğe bağlı | 2 |
| C3 | PDF görüntüleyici | `PdfRenderer` ile sayfa sayfa, yakınlaştırma | 3 |
| C4 | RAR ve 7z'yi klasör gibi açma | 7z: commons-compress `SevenZFile` (zaten var); RAR: `com.github.junrar:junrar`. `ZipFs` örnek alınarak `ArchiveFs` | 2 |
| C5 | LAN (Windows paylaşımı, SMB) | `com.hierynomus:smbj` ile `SmbFs implements Vfs`; `FtpFs` ve FTP sunucu listesi örnek alınır | 2 |
| C6 | SFTP (SSH) | `com.github.mwiede:jsch` ile `SftpFs`; FTP listesine "tür" alanı eklenir | 3 |
| C7 | Bulut depolama | Önce WebDAV (`OkHttp` ile PROPFIND/GET/PUT) — Yandex Disk, Nextcloud vb. çalışır. Google Drive / Dropbox / OneDrive ayrı API anahtarı ister; kullanıcıya sorulmadan başlanmaz | 3 |
| C8 | Panoya kopyala / yapıştır | Seçilenler bellekte "pano" listesine alınır; başka klasörde "Yapıştır" düğmesi belirir. Mevcut kopyalama `FileOps.copy` kullanılır | 2 |
| C9 | Toplu yeniden adlandırma | Birden çok seçiliyken "Ad değiştir" → kalıp (`ad_##`) diyaloğu | 3 |
| C10 | Sağlama toplamı | Ayrıntılar diyaloğuna MD5 / SHA-1 / SHA-256 (arka planda, `MessageDigest`) | 3 |
| C11 | Yeni metin dosyası | "Yeni klasör" diyaloğuna "dosya" seçeneği | 3 |
| C12 | Geri tuşu | Önce seçim temizlenir, sonra üst klasöre çıkılır, kökte "çıkmak için tekrar bas" | 1 |
| C13 | Çöp kutusu (isteğe bağlı) | Silinenler `/.KartFormatCop/` altına taşınır, kökte "Çöp kutusu" satırı, 30 gün sonra temizlenir | 3 |
| C14 | SQLite görüntüleyici (isteğe bağlı) | `.db` dosyası: tablo listesi + ilk 200 satır | 3 |
| C15 | Root erişimi | Yapılmayacak (uygulama root'suz) | — |

Bizde olup X-plore'da olmayanlar (dokunulmaz): USB kart biçimlendirme, imaj yazma,
Windows/FreeDOS kurulum kartı, PS2 kartı, bölümleme.

---

## Önerilen sıra

1. A1 (RecyclerView + aç/kapa animasyonu) — diğer görünüm işlerinin temeli
2. A2 (parmağı takip eden panel kaydırma)
3. B1, B2, B5 (yol çubuğu, seçim işareti, zemin katmanları)
4. B7 (ayarlar ekranı) + C12 (geri tuşu)
5. B3, B4, B6 (sayılar, rozetler, küçük resimler)
6. C1, C2, C4, C8
7. C5 ve sonrası; B8, B9, A3–A5

Her adım ayrı sürüm olarak çıkarılır ki kullanıcı telefonda tek tek deneyebilsin.
Kullanıcı ekran görüntüsü gönderirse, oradaki kusur sıradaki işten önce düzeltilir.

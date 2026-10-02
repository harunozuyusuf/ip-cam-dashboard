# Windows setup ve RJ45 simgesi

Kurulum, güncel `src` kaynaklarından uygulamayı derler ve korunan referans
ZIP'ten Windows Java 21 runtime dosyalarını alır. Kullanıcı verileri pakete
eklenmez. Yönetici yetkisi istemeyen kullanıcı başına kurulum yapılır.

## Derleme

JDK 17 veya üstü (`javac` ve `jar`) ve Inno Setup 6 gerekir:

```powershell
.\installer\build-setup.ps1
```

Çıktı: `outputs/IPCameraMonitor-J.3.3-EVENTLOG-Setup.exe` ve SHA-256 kaydı.
Kurulum ve masaüstü/Başlat menüsü simgeleri `assets/rj45.ico` dosyasını
kullanır. ICO, 16–256 piksel arasında yedi boyut içerir; düzenlenebilir
SVG ve PNG önizleme aynı klasördedir. `build-icon.cjs` için Node.js ve
`sharp` paketi gerekir; hazır ICO ile setup derlemek için gerekmez.

## Veri koruma

Uygulama verileri kurulum klasöründeki `data` dizininde kalır. Güncelleme
ve kaldırma bu dizini silmez. Taşınabilir paketten geçiş adımları
`kurulum-bilgisi.txt` içinde ve kurulum sihirbazında gösterilir.
Kurulum/kaldırma mevcut kurulum yolunda çalışan Java uygulamasını
algılarsa önce kullanıcının durdurmasını ister.

## Test — 02/10/2026

Üretim kaydına dokunmayan ayrı AppId ve kısayol adlarıyla test paketi:

```powershell
.\installer\build-setup.ps1 -TestBuild
.\installer\test-setup.ps1
```

Sessiz kurulum, gömülü runtime, RJ45 kısayolları, tekrar kurulum,
kaldırma ve kullanıcı verisinin korunması doğrulandı. Çalışan süreç
üzerine kurulum koruması bir Java test süreciyle doğrulandı.

**Açık test sınırı:** Gerçek uygulama bu bilgisayarda Java başlatılırken
`Unable to establish loopback connection` / `Invalid argument: connect`
hatası verdi. Bu hata korunan pakette de görülmüştü. Canlı uygulama/API
açılış testi geçmedi; kurulum testleri bu sınırlamayı gidermiş sayılmaz.
Setup dijital olarak imzalanmamıştır.

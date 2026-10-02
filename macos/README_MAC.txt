IP KAMERA MONITOR — J.3.3 TR/EN DAYRESET EVENTLOG
macOS Universal paket — 02/10/2026

Intel (x86_64) ve Apple Silicon (arm64) için ayrı Java 21 çalışma
ortamları aynı ZIP içinde bulunur. İlk açılışta Java indirilmez.
Paket macOS 11 veya üzerini hedefler; gerçek Mac üzerinde test edilmemiştir.

KURULUM
1. ZIP'i tamamen açın.
2. IP Kamera Monitor.app ve IP Kamera Monitor Durdur.app uygulamalarını
   Applications klasörüne veya istediğiniz kalıcı bir klasöre taşıyın.
3. IP Kamera Monitor.app uygulamasını açın; arayüz tarayıcıda açılır.
4. Programı kapatmak için IP Kamera Monitor Durdur.app kullanın.
   Tarayıcı sekmesini kapatmak izlemeyi durdurmaz.

VERİ KONUMU
~/Library/Application Support/IPCameraMonitor/data
Finder > Git > Klasöre Git ile bu yolu açabilirsiniz.
Uygulamayı değiştirmek veya silmek bu klasörü silmez.

ESKİ PAKETTEN GEÇİŞ
Eski uygulamayı önce durdurun. Eski data klasörünüzün içeriğini yukarıdaki
data klasörüne, yeni uygulamayı ilk kez başlatmadan önce kopyalayın.
Hedefte mevcut kayıt varsa önce kopyasını saklayın; listeler otomatik birleşmez.
J.3.4 paketindeki diğer değişiklikler bu sürüme dahil olduğu varsayılmamalıdır;
bu paketin tabanı güncel J.3.3 DAYRESET EVENTLOG kaynaklarıdır.

KORUNAN ÖZELLİKLER
Olay bazlı loglar, 30 günlük saklama, günlük ZIP arşivleri, DAY_RESET,
G/A/Y tarih biçimi ve TR/EN FIXED. RJ45 uygulama simgesi dahildir.

DOĞRULAMA
Java kaynakları derlendi; ZIP bütünlüğü, iki Java mimarisi, dosya izinleri,
uygulama tanımları ve simge yapısı Windows üzerinde kontrol edildi.
Mac açılışı, kamera erişimi ve mail gönderimi canlı olarak doğrulanmadı.
Paket Apple Developer kimliğiyle imzalanmamış ve noterlenmemiştir.
macOS onay süreci: https://support.apple.com/en-us/102445

HATA DURUMUNDA
~/Library/Application Support/IPCameraMonitor/launcher.log dosyasına bakın.
Bu tanılama dosyası her açılışta yenilenir. Kamera olayları data/logs içindedir.

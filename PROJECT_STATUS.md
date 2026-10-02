# IP Kamera Monitor — geliştirme referansı

23/09/2026 tarihinde kullanıcı, sonraki geliştirmelerin aşağıdaki yapı
üzerinden devam etmesini istedi.

**Güncel referans: J.3.3 Windows TR/EN DAYRESET EVENTLOG**

- Korunan paket, kaynaklar ve sürüm notları: `baselines/J.3.3-DAYRESET-EVENTLOG/`
- ZIP: `IPCameraMonitor-J.3.3-Windows-TR-EN-DAYRESET-EVENTLOG.zip`
- SHA-256: `cdda22f88dbe48a0e22a273a08bbbe562754e8001b0d183d6f0d654b86897f90`

Yeni geliştirmeler için bu ZIP'i yeni bir çalışma klasörüne açın. Referans
paketin üzerine yazmayın. Yeni sürümleri ayrı adlarla üretin.
`filter-fix/` iptal edilmiş filtre çalışmasıdır; geliştirme tabanı değildir.
Derleme ve test klasörleri de referans paket olarak kullanılmamalıdır.

## Korunacak davranışlar

- Loglar olay bazlıdır; sürekli başarılı/başarısız ping satırları yazılmaz.
- ONLINE/OFFLINE geçişleri, 1 dakika uyarısı, NVR/sunucu problemi ve
  iyileşmesi, mail sonucu, DAY_RESET, sistem hataları ve yönetim işlemleri kaydedilir.
- Önceki günlerin logları otomatik ZIP yapılır.
- 30 günden eski loglar silinir; tam 30 günlük kayıtlar korunur.
- G/A/Y tarih formatı ve TR/EN FIXED dil sistemi korunur.
- Gün dönümünde OFFLINE sayacı sıfırlanır ve olay kaydı yazılır.
- İptal edilen sorunlu-kamera filtresi değişikliği dahil değildir.

## Doğrulama durumu

51 izole Java kontrolü ve arayüz kodu kontrolleri geçti. ZIP bütünlüğü ve
kaynak pakete göre değişen dosyalar doğrulandı. Gömülü Java 21 runtime,
başlatıcılar, dil kodu, DAY_RESET ve filtre davranışı korundu.

Gerçek uygulama başlatma ve canlı SMTP testi, çalışma ortamındaki yerel
bağlantı engeli nedeniyle tamamlanamadı. Kullanıcının canlı ortamda
doğruladığı bir stable sürüm olduğu henüz varsayılmamalıdır.
İzole test kopyasında HTTP istemcisi devre dışıdır; referans paket gerçek
HTTP istemcisini içerir. Test dosyaları önceki çalışma yollarına atıf
yapabilir; yeni çalışma klasöründe yollar uyarlanmalıdır.

Bu kayıt ve dosyalar yerel proje çalışma alanındadır. ChatGPT bulut
projesinin dosyalarına otomatik yüklendikleri varsayılmamalıdır.

# IP Camera Dashboard

Windows için IP kamera ve NVR/sunucu izleme uygulaması.

Geliştirme tabanı: **J.3.3 Windows TR/EN DAYRESET EVENTLOG**.

Windows kurulum projesi ve RJ45 simgesi [`installer`](installer) klasöründedir.
Derleme, geçiş ve doğrulama bilgileri [kurulum notlarında](installer/README.md) yer alır.

Intel ve Apple Silicon için Java 21 içeren ayrı macOS ZIP'i
[`macos/releases`](macos/releases) klasöründedir.
Mac kullanım ve doğrulama sınırları [Mac notlarında](macos/README_MAC.txt) açıklanır.

## Referans paket

Kaynaklar, testler, sürüm notları ve Java 21 runtime içeren Windows ZIP'i
[`baselines/J.3.3-DAYRESET-EVENTLOG`](baselines/J.3.3-DAYRESET-EVENTLOG)
altındadır. ZIP'i açıp `BASLAT_WINDOWS.bat` ile başlatabilirsiniz.

Yeni geliştirmeler bu referans üzerinden yapılır; referans paket korunur.
Davranışlar ve doğrulama sınırları [PROJECT_STATUS.md](PROJECT_STATUS.md)
dosyasında kayıtlıdır.

## Özellikler

- Kamera ve NVR/sunucu erişim izleme
- Olay bazlı loglar ve mail bildirimleri
- Önceki gün loglarını ZIP yapma ve 30 günden eski logları silme
- Gün dönümünde OFFLINE süresini sıfırlama ve DAY_RESET kaydı
- G/A/Y tarih biçimi ve TR/EN FIXED dil sistemi

## Kaynak ve testler

Türkçe açıklamalı güncel Java kaynak dosyası ve arayüz [`src`](src)
altındadır. Yeni geliştirmeler bu klasörde yapılır; `baselines` altındaki
orijinal kaynak ve paket korunur. Testler referans olarak saklanmıştır; testlerdeki önceki çalışma
klasörü yolları yeni ortamda uyarlanmalıdır.

Önceki doğrulamada 51 izole Java kontrolü ve arayüz kontrolleri geçmiştir.
Canlı kamera, gerçek SMTP gönderimi ve uygulamanın tam başlatma testi henüz
doğrulanmamıştır. Bu sınırlamalar sürüm notlarında açıklanır.

Çalışma verileri, kamera listeleri, mail kimlik bilgileri ve geçici derleme
klasörleri Git kapsamına alınmaz.

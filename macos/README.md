# macOS Universal ZIP

[Mac paketini indir](releases/IPCameraMonitor-J.3.3-macOS-Universal-DAYRESET-EVENTLOG.zip)
([SHA-256](releases/IPCameraMonitor-J.3.3-macOS-Universal-DAYRESET-EVENTLOG.zip.sha256)).

Intel x86_64 ve Apple Silicon arm64 Java 21 çalışma ortamları pakete
dahildir. Güncel `src` kodunu kullanır; iki .app başlatıcısı ve RJ45
simgesi içerir. Kullanım ve veri taşıma adımları [README_MAC.txt](README_MAC.txt)
dosyasındadır.

Paket oluşturma: JDK ve Python bulunan ortamda `python macos/build-mac.py`.
Java arşivleri `runtime-manifest.json` içinde sabitlenmiş resmi indirme
adreslerinden alınır ve SHA-256 ile doğrulanır. ICNS yeniden üretmek
için `build-icon.cjs`, Node.js ve sharp kullanılır.

Windows üzerinde Java derlemesi, ZIP CRC, Java Mach-O mimarileri,
Unix çalıştırma izinleri, plist tanımları ve arayüz eşitliği kontrol edildi.
Gerçek macOS açılışı, kamera ve mail testi yapılmadı. Uygulama Apple
Developer kimliğiyle imzalanmamış ve noterlenmemiştir.

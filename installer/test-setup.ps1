$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$setup = Join-Path $projectRoot 'outputs\IPCameraMonitor-J.3.3-EVENTLOG-Setup-TEST.exe'
$install = Join-Path $projectRoot ('setup-tests\' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $install | Out-Null
$arguments = @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="{0}"' -f $install),'/LANG=turkish','/TASKS=desktopicon')
$app = $null
$checks = [Collections.Generic.List[string]]::new()
function Check([bool]$ok,[string]$message) {
    if (-not $ok) { throw $message }
    $checks.Add($message)
}
try {
    $p = Start-Process -FilePath $setup -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    Check ($p.ExitCode -eq 0) 'Sessiz kurulum başarılı'
    Check (Test-Path "$install\runtime\bin\javaw.exe") 'Gömülü Java kuruldu'
    Check (Test-Path "$install\rj45.ico") 'RJ45 simgesi kuruldu'
    $link = Join-Path ([Environment]::GetFolderPath('Desktop')) 'IP Kamera Monitor Kurulum Testi.lnk'
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($link)
    Check ($shortcut.WorkingDirectory -eq $install) 'Kısayol çalışma klasörü doğru'
    Check ($shortcut.IconLocation -like "$install\rj45.ico*") 'Kısayol RJ45 simgesini kullanıyor'
    $startLink = Join-Path ([Environment]::GetFolderPath('Programs')) 'IP Kamera Monitor Kurulum Testi\IP Kamera Monitor Kurulum Testi.lnk'
    Check (Test-Path $startLink) 'Başlat menüsü kısayolu oluşturuldu'
    New-Item -ItemType Directory -Force "$install\data" | Out-Null
    [IO.File]::WriteAllText("$install\data\preserve-test.txt",'Kamera verisi korunmalıdır')
    # Bu süreç yalnızca boş test kurulumudur; fiziksel kamera veya mail hesabı tanımlanmaz.
    $app = Start-Process -FilePath "$install\runtime\bin\java.exe" -ArgumentList @('-Djava.awt.headless=true','-jar',('"{0}\IPCameraMonitor-J.3.3.jar"' -f $install)) -WorkingDirectory $install -WindowStyle Hidden -PassThru -RedirectStandardOutput "$install\test-stdout.txt" -RedirectStandardError "$install\test-stderr.txt"
    for ($i=0; $i -lt 30 -and -not (Test-Path "$install\data\monitor.port"); $i++) { Start-Sleep -Milliseconds 500 }
    if (Test-Path "$install\data\monitor.port") {
        Check $true 'Kurulu uygulama açıldı'
        $port = [IO.File]::ReadAllText("$install\data\monitor.port").Trim()
        $response = Invoke-WebRequest "http://127.0.0.1:$port/api/status" -UseBasicParsing
        Check ($response.StatusCode -eq 200 -and $response.Content.Trim() -eq '[]') 'Yerel durum API yanıtı başarılı'
        $html = Invoke-WebRequest "http://127.0.0.1:$port/" -UseBasicParsing
        Check ($html.Content -match 'i18nScript') 'TR/EN arayüzü sunuluyor'
    } else {
        $failure = [IO.File]::ReadAllText("$install\test-stderr.txt")
        if ($failure -notmatch 'Unable to establish loopback connection') { throw "Beklenmeyen açılış hatası: $failure" }
        $checks.Add('SINIR: Java yerel bağlantı hatası nedeniyle canlı uygulama/API testi geçmedi.')
        # Yalnızca kurulumun süreç korumasını sınamak için gerçek java.exe üzerinde
        # bekleyen bir test sınıfı çalıştırılır; bu uygulamanın açılış testi değildir.
        [IO.File]::WriteAllText("$install\InstallerWait.java",'public class InstallerWait { public static void main(String[] args) throws Exception { Thread.sleep(120000); } }')
        & javac -d $install "$install\InstallerWait.java"
        if ($LASTEXITCODE -ne 0) { throw 'Test yardımcısı derlenemedi.' }
        $app = Start-Process -FilePath "$install\runtime\bin\java.exe" -ArgumentList @('-cp',('"{0}"' -f $install),'InstallerWait',('"{0}\IPCameraMonitor-J.3.3.jar"' -f $install)) -WorkingDirectory $install -WindowStyle Hidden -PassThru
        Start-Sleep -Milliseconds 500
    }
    $blocked = Start-Process -FilePath $setup -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    Check ($blocked.ExitCode -ne 0) 'Çalışan uygulama üzerine kurulum engellendi'
    Stop-Process -Id $app.Id -ErrorAction SilentlyContinue
    $app.WaitForExit(); $app = $null
    $upgrade = Start-Process -FilePath $setup -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    Check ($upgrade.ExitCode -eq 0) 'Tekrar kurulum başarılı'
    Check ([IO.File]::ReadAllText("$install\data\preserve-test.txt") -eq 'Kamera verisi korunmalıdır') 'Güncellemede veri korundu'
} finally {
    if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id; $app.WaitForExit() }
    if (Test-Path "$install\unins000.exe") {
        $uninstall = Start-Process -FilePath "$install\unins000.exe" -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' -WindowStyle Hidden -Wait -PassThru
        Check ($uninstall.ExitCode -eq 0) 'Kaldırma başarılı'
        Check (Test-Path "$install\data\preserve-test.txt") 'Kaldırmada kullanıcı verisi korundu'
        Check (-not (Test-Path "$install\IPCameraMonitor-J.3.3.jar")) 'Kaldırmada program dosyaları temizlendi'
        Check (-not (Test-Path $link)) 'Test masaüstü kısayolu kaldırıldı'
    }
}
$checks | ForEach-Object { Write-Output $_ }
$checks | Set-Content (Join-Path $projectRoot 'outputs\setup-test-results.txt') -Encoding utf8

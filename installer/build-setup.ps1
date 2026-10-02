param(
    [string]$Iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    [switch]$TestBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$archive = Join-Path $projectRoot 'baselines\J.3.3-DAYRESET-EVENTLOG\IPCameraMonitor-J.3.3-Windows-TR-EN-DAYRESET-EVENTLOG.zip'
$expectedHash = 'CDDA22F88DBE48A0E22A273A08BBBE562754E8001B0D183D6F0D654B86897F90'
if ((Get-FileHash -LiteralPath $archive).Hash -ne $expectedHash) { throw 'Referans paket doğrulaması başarısız.' }
# Her derleme yeni bir geçici klasör kullanır; referans paket değiştirilmez.
$buildRoot = Join-Path $projectRoot ('setup-build\' + [guid]::NewGuid().ToString('N'))
$payload = Join-Path $buildRoot 'payload'
$classes = Join-Path $buildRoot 'classes'
$output = Join-Path $projectRoot 'outputs'
New-Item -ItemType Directory -Force $payload,$classes,$output | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $buildRoot 'reference')
$reference = Join-Path $buildRoot 'reference\ip_kamera_monitor_J_3_3_WINDOWS'
foreach ($name in @('runtime','ornekler','BASLAT_WINDOWS.bat','DURDUR_WINDOWS.bat','BASLAT_TEST_KONSOL.bat','SURUM_NOTLARI_EVENTLOG.txt')) {
    Copy-Item -LiteralPath (Join-Path $reference $name) -Destination $payload -Recurse
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'src\ui.html') -Destination $payload
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'kurulum-bilgisi.txt') -Destination $payload
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets\rj45.ico') -Destination $payload
& javac -encoding UTF-8 -d $classes (Join-Path $projectRoot 'src\Main.java')
if ($LASTEXITCODE -ne 0) { throw 'Java derlemesi başarısız.' }
& jar --create --file (Join-Path $payload 'IPCameraMonitor-J.3.3.jar') --main-class Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR oluşturulamadı.' }
$arguments = @("/DPayloadDir=$payload", "/DOutputDirPath=$output")
if ($TestBuild) { $arguments += '/DTestBuild' }
& $Iscc @arguments (Join-Path $PSScriptRoot 'windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'Setup oluşturulamadı.' }
$suffix = if ($TestBuild) { '-TEST' } else { '' }
$setup = Join-Path $output "IPCameraMonitor-J.3.3-EVENTLOG-Setup$suffix.exe"
$hash = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$setup.sha256", "$hash  $([IO.Path]::GetFileName($setup))`n")
Write-Output "SETUP: $setup"

#ifndef PayloadDir
  #error PayloadDir parametresi gereklidir; build-setup.ps1 kullanin.
#endif
#ifndef OutputDirPath
  #define OutputDirPath "..\outputs"
#endif
#ifdef TestBuild
  #define ProductId "IPCameraMonitor-Installer-Test"
  #define ProductName "IP Kamera Monitor Kurulum Testi"
  #define SetupName "IPCameraMonitor-J.3.3-EVENTLOG-Setup-TEST"
#else
  #define ProductId "IPCameraMonitor-Windows"
  #define ProductName "IP Kamera Monitor"
  #define SetupName "IPCameraMonitor-J.3.3-EVENTLOG-Setup"
#endif

[Setup]
AppId={#ProductId}
AppName={#ProductName}
AppVersion=J.3.3 DAYRESET EVENTLOG
AppVerName={#ProductName} J.3.3 DAYRESET EVENTLOG
VersionInfoVersion=3.3.0.1
AppPublisher=IP Camera Dashboard
AppPublisherURL=https://github.com/harunozuyusuf/ip-cam-dashboard
DefaultDirName={localappdata}\Programs\{#ProductName}
DefaultGroupName={#ProductName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDirPath}
OutputBaseFilename={#SetupName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
DisableProgramGroupPage=yes
CloseApplications=yes
RestartApplications=no
SetupIconFile=assets\rj45.ico
UninstallDisplayIcon={app}\rj45.ico
SetupLogging=yes
InfoBeforeFile=kurulum-bilgisi.txt

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; Yalnızca derlenen dağıtım dosyaları yüklenir; kullanıcının data klasörü pakete alınmaz.
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#ProductName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\IPCameraMonitor-J.3.3.jar"""; WorkingDir: "{app}"; IconFilename: "{app}\rj45.ico"
Name: "{group}\İzlemeyi Durdur"; Filename: "{app}\DURDUR_WINDOWS.bat"; WorkingDir: "{app}"; Flags: runminimized
Name: "{group}\Kaldır"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#ProductName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\IPCameraMonitor-J.3.3.jar"""; WorkingDir: "{app}"; IconFilename: "{app}\rj45.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-jar ""{app}\IPCameraMonitor-J.3.3.jar"""; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,{#ProductName}}"; Flags: nowait postinstall skipifsilent

[Code]
// Çalışan uygulamanın üzerine kurulum veya kaldırma yapılmasını engeller.
// Herhangi bir süreci zorla sonlandırmaz; kullanıcı önce izlemeyi durdurur.
function IsMonitorRunning: Boolean;
var
  Locator, Services, Processes, Process: Variant;
  I: Integer;
  CommandLine: String;
begin
  Result := False;
  Locator := CreateOleObject('WbemScripting.SWbemLocator');
  Services := Locator.ConnectServer('', 'root\CIMV2');
  Processes := Services.ExecQuery('SELECT CommandLine FROM Win32_Process WHERE Name="javaw.exe" OR Name="java.exe"');
  for I := 0 to Processes.Count - 1 do
  begin
    Process := Processes.ItemIndex(I);
    if not VarIsNull(Process.CommandLine) then
    begin
      CommandLine := Process.CommandLine;
      CommandLine := Lowercase(CommandLine);
      if (Pos('ipcameramonitor-j.3.3.jar', CommandLine) > 0) and
         (Pos(Lowercase(ExpandConstant('{app}')), CommandLine) > 0) then
        Result := True;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  try
    if IsMonitorRunning then
      Result := 'IP Kamera Monitor çalışıyor. Başlat menüsündeki İzlemeyi Durdur kısayolunu kullanıp tekrar deneyin. / Stop IP Camera Monitor before continuing.';
  except
    Result := 'Çalışan uygulama kontrol edilemedi. / Unable to check running processes.';
  end;
end;

function InitializeUninstall: Boolean;
begin
  Result := False;
  try
    Result := not IsMonitorRunning;
    if not Result then
      MsgBox('Önce IP Kamera Monitor uygulamasını durdurun. / Stop IP Camera Monitor first.', mbError, MB_OK);
  except
    MsgBox('Çalışan uygulama kontrol edilemedi. / Unable to check running processes.', mbError, MB_OK);
  end;
end;

// UninstallDelete kullanılmaz: uygulamanın ürettiği kamera verileri ve loglar korunur.

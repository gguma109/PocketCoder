#ifndef PayloadDir
  #define PayloadDir "..\windows-pc"
#endif
#ifndef InstallerOutput
  #define InstallerOutput "..\installer"
#endif
[Setup]
AppId={{66CE29F0-21AD-4D66-AC3E-B116CB288893}
AppName=PocketCoder PC
AppVersion=0.7.2
AppPublisher=PocketCoder
DefaultDirName={localappdata}\Programs\PocketCoder
DefaultGroupName=PocketCoder
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\PocketCoder-PC.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
OutputDir={#InstallerOutput}
OutputBaseFilename=PocketCoder-Setup-0.7.2
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "바탕화면 바로가기 만들기"; GroupDescription: "바로가기:"

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\PocketCoder\PocketCoder PC"; Filename: "{app}\PocketCoder-PC.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\PocketCoder PC"; Filename: "{app}\PocketCoder-PC.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\PocketCoder-PC.exe"; Description: "PocketCoder PC 열기"; Flags: nowait postinstall skipifsilent
Filename: "https://tailscale.com/download/windows"; Description: "Tailscale 설치 페이지 열기 (최초 설치 시 필요)"; Flags: shellexec postinstall skipifsilent unchecked; Check: NeedsTailscale

[Code]
function NeedsTailscale: Boolean;
begin
  Result := not FileExists(ExpandConstant('{commonpf}\Tailscale\tailscale.exe'));
end;

function StopExistingServer: Boolean;
var
  ResultCode: Integer;
  ControlFile: String;
begin
  Result := True;
  if FileExists(ExpandConstant('{app}\PocketCoder-PC.exe')) then
    Exec(ExpandConstant('{app}\PocketCoder-PC.exe'), '--prepare-update', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  ControlFile := ExpandConstant('{app}\pocketcoder-pc.ps1');
  if FileExists(ControlFile) then
    Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ControlFile + '" -Action Stop',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  if not StopExistingServer then
    Result := 'PocketCoder 서버를 종료하지 못했습니다. PC 제어 창에서 종료한 뒤 다시 설치하세요.'
  else
    Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    StopExistingServer;
end;

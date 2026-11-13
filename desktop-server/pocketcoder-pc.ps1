param([switch]$SelfTest, [switch]$CheckOnly, [ValidateSet("Start","Stop")][string]$Action)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$serverRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$serverJar = Join-Path $serverRoot 'pocketcoder-server.jar'
function Test-ServerCommand([string]$Command, [string]$Jar) {
    if (!$Command) { return $false }
    $escaped = [regex]::Escape($Jar)
    return $Command -match ('(?:^|\s)-jar\s+(?:"' + $escaped + '"|' + $escaped + ')(?=\s|$)')
}
if ($SelfTest) {
    $example = 'C:\Example Folder\pocketcoder-server.jar'
    if (!(Test-ServerCommand ('java -jar "' + $example + '" --bind=tailscale') $example)) { throw 'Quoted JAR path rejected' }
    if (Test-ServerCommand ('java -jar "' + $example + '.other"') $example) { throw 'Different JAR accepted' }
    if (Test-ServerCommand ('java -Dnote="' + $example + '" -jar other.jar') $example) { throw 'Unrelated process accepted' }
    if (Test-ServerCommand 'java -jar C:\Other\pocketcoder-server.jar' $example) { throw 'Other directory accepted' }
    Write-Output 'PC launcher identity checks PASS'
    exit 0
}
function Find-Java {
    $bundled = Join-Path $serverRoot "runtime\bin\java.exe"
    if (Test-Path -LiteralPath $bundled -PathType Leaf) { return $bundled }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\java.exe')
    )
    $adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path -LiteralPath $adoptium) {
        $candidates += @(Get-ChildItem -LiteralPath $adoptium -Directory | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' })
    }
    return $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
function Invoke-Quiet([string]$Executable, [string]$Arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $Executable; $info.Arguments = $Arguments
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        if (!$process.WaitForExit(4000)) { $process.Kill(); throw '프로그램 응답 시간이 초과됐습니다.' }
        if ($process.ExitCode -ne 0) { throw '프로그램 실행에 실패했습니다.' }
        return $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    } finally { $process.Dispose() }
}
$java = Find-Java
$javaReady = $false
if ($java) {
    try { $version = Invoke-Quiet $java '-version'; $javaReady = ($version -match 'version\s+"(\d+)' -and [int]$Matches[1] -ge 17) } catch { }
}
$ffmpeg = @(Join-Path $serverRoot 'ffmpeg.exe'; Join-Path $serverRoot 'bin\ffmpeg.exe') | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (!$ffmpeg) { $command = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue; if ($command) { $ffmpeg = $command.Source } }
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$ip = $null
if (Test-Path -LiteralPath $tailscale) {
    try { $ip = ((Invoke-Quiet $tailscale 'ip -4').Trim() -split '\r?\n')[0]; if ($ip -notmatch '^100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$') { $ip = $null } } catch { }
}
function Get-OwnedServer {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue | Where-Object {
        Test-ServerCommand $_.CommandLine $serverJar
    })
}
if ($CheckOnly) {
    [pscustomobject]@{Java17=$javaReady;FFmpeg=[bool]$ffmpeg;TailscaleConnected=[bool]$ip;Address=$ip;JarPresent=(Test-Path -LiteralPath $serverJar);OwnedServer=((Get-OwnedServer).Count -gt 0)} | ConvertTo-Json -Compress
    exit 0
}
if ($Action) {
    try {
        if ($Action -eq 'Stop') {
            foreach ($owned in (Get-OwnedServer)) {
                $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($owned.ProcessId)" -ErrorAction SilentlyContinue
                if ($current -and (Test-ServerCommand $current.CommandLine $serverJar)) {
                    $process=Get-Process -Id $current.ProcessId -ErrorAction SilentlyContinue
                    if ($process) { $process.Kill(); $process.WaitForExit(3000) | Out-Null }
                }
            }
            @{ok=$true;message='종료됨'} | ConvertTo-Json -Compress
        } else {
            if (!$javaReady) { throw 'Java 17 이상을 설치한 뒤 다시 실행하세요.' }
            if (!$ip) { throw 'PC Tailscale을 켜고 로그인한 뒤 다시 실행하세요.' }
            if (!(Test-Path -LiteralPath $serverJar -PathType Leaf)) { throw '배포 ZIP 전체를 같은 폴더에 압축 해제하세요.' }
            if ((Get-OwnedServer).Count -eq 0) {
                $logRoot=Join-Path $env:LOCALAPPDATA 'PocketCoder\logs'
                [IO.Directory]::CreateDirectory($logRoot) | Out-Null
                $stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
                $p=Start-Process -FilePath $java -ArgumentList @('-jar',('"'+$serverJar+'"'),'--bind=tailscale','--port=8765') -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot "server-$stamp.log") -RedirectStandardError (Join-Path $logRoot "server-$stamp-error.log") -PassThru
                for($attempt=0;$attempt -lt 24;$attempt++) {
                    Start-Sleep -Milliseconds 500
                    if ($p.HasExited) { throw '서버 시작 실패. 로그를 확인하세요. 방금 종료했다면 잠시 뒤 다시 시작하세요.' }
                    try { $health=Invoke-RestMethod -Uri "http://${ip}:8765/health" -TimeoutSec 1 -UseBasicParsing; if($health.ok -and $health.auth -eq 'tailscale'){break} } catch { }
                }
                if (!$health -or !$health.ok) { throw '서버 시작을 확인하지 못했습니다. 로그 폴더를 확인하세요.' }
            }
            @{ok=$true;message='실행 중';address="${ip}:8765"} | ConvertTo-Json -Compress
        }
    } catch { @{ok=$false;message=$_.Exception.Message} | ConvertTo-Json -Compress; exit 1 }
    exit 0
}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$script:startedProcess = $null
$script:startingUntil = [datetime]::MinValue
$script:lastErrorLog = $null
$form = New-Object Windows.Forms.Form
$form.Text = 'PocketCoder PC'; $form.ClientSize = New-Object Drawing.Size(540,330)
$form.StartPosition = 'CenterScreen'; $form.FormBorderStyle = 'FixedDialog'; $form.MaximizeBox = $false
$form.Font = New-Object Drawing.Font('Malgun Gothic',10)
function Label([string]$Text,[int]$Y,[int]$Height=26) {
    $view = New-Object Windows.Forms.Label
    $view.Text=$Text; $view.Location=New-Object Drawing.Point(20,$Y); $view.Size=New-Object Drawing.Size(500,$Height)
    $form.Controls.Add($view); return $view
}
$title = Label 'PocketCoder 원격 연결' 18 32
$title.Font=New-Object Drawing.Font('Malgun Gothic',16,[Drawing.FontStyle]::Bold)
$status = Label '상태 확인 중…' 62 28
$address = Label $(if($ip){"휴대폰에 입력: ${ip}:8765"}else{'Tailscale을 켜고 이 창을 다시 실행하세요.'}) 100 28
$dependencies = Label ('Java 17+: ' + $(if($javaReady){'준비됨'}else{'설치 필요'}) + '   /   FFmpeg: ' + $(if($ffmpeg){'준비됨'}else{'없음 · 저속 호환 모드'})) 138 28
$guide = Label 'PC와 휴대폰의 Tailscale에 같은 계정으로 로그인하세요.' 173 26
function Button([string]$Text,[int]$X,[scriptblock]$Click) {
    $view=New-Object Windows.Forms.Button
    $view.Text=$Text; $view.Location=New-Object Drawing.Point($X,214); $view.Size=New-Object Drawing.Size(118,43)
    $view.Add_Click($Click); $form.Controls.Add($view); return $view
}
$start = Button '시작' 20 {
    try {
        if (!$javaReady) { throw 'Java 17 이상을 설치한 뒤 다시 실행하세요.' }
        if (!$ip) { throw 'PC Tailscale에 로그인하고 연결한 뒤 이 창을 다시 실행하세요.' }
        if (!(Test-Path -LiteralPath $serverJar -PathType Leaf)) { throw '배포 ZIP의 모든 파일을 같은 폴더에 압축 해제하세요.' }
        if ((Get-OwnedServer).Count -gt 0) { return }
        $stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
        $logRoot=Join-Path $env:LOCALAPPDATA 'PocketCoder\logs'
        [IO.Directory]::CreateDirectory($logRoot) | Out-Null
        $script:lastErrorLog=Join-Path $logRoot "server-$stamp-error.log"
        $script:startedProcess=Start-Process -FilePath $java -ArgumentList @('-jar',('"'+$serverJar+'"'),'--bind=tailscale','--port=8765') -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot "server-$stamp.log") -RedirectStandardError $script:lastErrorLog -PassThru
        $script:startingUntil=[datetime]::Now.AddSeconds(12)
        $status.Text='시작 중…'; $start.Enabled=$false
    } catch { [Windows.Forms.MessageBox]::Show($_.Exception.Message,'PocketCoder') | Out-Null }
}
$stop = Button '종료' 148 {
    try {
        foreach($owned in (Get-OwnedServer)) {
            # Recheck identity immediately before stopping; never kill an unrelated Java process.
            $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($owned.ProcessId)" -ErrorAction SilentlyContinue
            if ($current -and (Test-ServerCommand $current.CommandLine $serverJar)) {
                $process=Get-Process -Id $current.ProcessId -ErrorAction SilentlyContinue
                if ($process) { $process.Kill(); $process.WaitForExit(3000) | Out-Null }
            }
        }
        $script:startingUntil=[datetime]::MinValue
        $status.Text='종료됨'; $start.Enabled=$true
    } catch { [Windows.Forms.MessageBox]::Show($_.Exception.Message,'PocketCoder') | Out-Null }
}
$copy = Button '주소 복사' 276 { if($ip){[Windows.Forms.Clipboard]::SetText("${ip}:8765")} }
$logs = Button '로그 폴더' 404 {
    $logRoot=Join-Path $env:LOCALAPPDATA 'PocketCoder\logs'
    [IO.Directory]::CreateDirectory($logRoot) | Out-Null
    Start-Process explorer.exe -ArgumentList ('"'+$logRoot+'"')
}
$note=Label '창을 닫아도 서버는 계속 실행됩니다. 끄려면 [종료]를 누르세요.' 278 38
$note.Font=New-Object Drawing.Font('Malgun Gothic',9)
$timer=New-Object Windows.Forms.Timer
$timer.Interval=2000
$timer.Add_Tick({
    try {
        $owned=Get-OwnedServer
        $running=$owned.Count -gt 0
        $stop.Enabled=$running; $copy.Enabled=[bool]$ip
        if($running -and $ip) {
            try {
                $health=Invoke-RestMethod -Uri "http://${ip}:8765/health" -TimeoutSec 1 -UseBasicParsing
                if($health.ok -and $health.auth -eq 'tailscale') {
                    $status.Text="실행 중 · 서버 $($health.version) · Tailscale 전용"
                    $start.Enabled=$false; $script:startingUntil=[datetime]::MinValue
                } else { $status.Text='동일 계정 인증 상태를 확인하세요.' }
            } catch { $status.Text='시작 중 / 연결 확인 중…' }
        } elseif([datetime]::Now -lt $script:startingUntil) {
            $status.Text='시작 중…'; $start.Enabled=$false
            if($script:startedProcess -and $script:startedProcess.HasExited) {
                $script:startingUntil=[datetime]::MinValue
                $status.Text='시작 실패 · 로그 폴더에서 오류를 확인하세요.'
                $start.Enabled=$true
            }
        } else {
            $status.Text='종료됨'; $start.Enabled=$javaReady -and [bool]$ip
        }
    } catch { $status.Text='상태 확인 실패 · 창을 다시 실행하세요.' }
})
$form.Add_Shown({$timer.Start()})
$form.Add_FormClosed({$timer.Stop();$timer.Dispose()})
[void]$form.ShowDialog()

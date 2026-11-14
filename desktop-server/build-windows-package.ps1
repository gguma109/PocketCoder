param([string]$Payload='windows-pc')
$ErrorActionPreference='Stop'
$payloadRoot=[IO.Path]::GetFullPath($Payload)
if(!(Test-Path -LiteralPath (Join-Path $payloadRoot 'pocketcoder-server.jar'))){throw 'Download the verified desktop artifact first'}
$vendor=Join-Path $env:TEMP 'pocketcoder-vendor'
if($env:RUNNER_TEMP){$vendor=Join-Path $env:RUNNER_TEMP 'pocketcoder-vendor'}
[IO.Directory]::CreateDirectory($vendor)|Out-Null
function Download([string]$Url,[string]$Target){
    if(!(Test-Path -LiteralPath $Target)) { Invoke-WebRequest -Uri $Url -OutFile $Target -TimeoutSec 180 }
}
$archive=Join-Path $vendor 'ffmpeg-lgpl.zip'
$url='https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-12-15-40/ffmpeg-n8.1.3-35-gf1acb0a266-win64-lgpl-shared-8.1.zip'
$expected='bb6eedd1978549c84e8dff7b72c885df090db6a6ee1de44e76c8855babea495c'
Download $url $archive
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'FFmpeg checksum mismatch; refusing to bundle'}
$expanded=Join-Path $vendor 'ffmpeg-expanded'
Expand-Archive -LiteralPath $archive -DestinationPath $expanded -Force
$ffmpegRoot=(Get-ChildItem -LiteralPath $expanded -Directory | Select-Object -First 1).FullName
Copy-Item -LiteralPath (Join-Path $ffmpegRoot 'bin') -Destination $payloadRoot -Recurse -Force
$thirdParty=Join-Path $payloadRoot 'third-party'
[IO.Directory]::CreateDirectory($thirdParty)|Out-Null
Copy-Item -LiteralPath (Join-Path $ffmpegRoot 'LICENSE.txt') -Destination (Join-Path $thirdParty 'FFmpeg-LGPLv3.txt') -Force
Copy-Item -LiteralPath (Join-Path $ffmpegRoot 'doc') -Destination (Join-Path $thirdParty 'ffmpeg-doc') -Recurse -Force
Download 'https://github.com/FFmpeg/FFmpeg/archive/f1acb0a2666c78d92688f4c3d2803a132f5590f6.tar.gz' (Join-Path $thirdParty 'ffmpeg-corresponding-source.tar.gz')
Download 'https://github.com/FFmpeg-Builds/FFmpeg-Builds/archive/c9fe5114060e507409f7ee07edfd82efaf25d767.tar.gz' (Join-Path $thirdParty 'ffmpeg-build-recipes.tar.gz')
Download 'https://raw.githubusercontent.com/FFmpeg/FFmpeg/f1acb0a2666c78d92688f4c3d2803a132f5590f6/COPYING.GPLv3' (Join-Path $thirdParty 'GPLv3.txt')
& (Join-Path $payloadRoot 'bin\ffmpeg.exe') -hide_banner -buildconf 2>&1 | Out-File -LiteralPath (Join-Path $thirdParty 'ffmpeg-build-configuration.txt') -Encoding utf8
$build=Get-Content -LiteralPath (Join-Path $thirdParty 'ffmpeg-build-configuration.txt') -Raw
if($build -match '--enable-gpl|--enable-nonfree'){throw 'Expected an LGPL-only build'}
@'
FFmpeg: LGPLv3 shared build by FFmpeg-Builds, unchanged binary and shared libraries.
Project and license information: https://ffmpeg.org/legal.html
The exact FFmpeg source and build recipes are included beside this notice.
Additional dependency sources and build instructions are referenced in the upstream build recipes.
Compatible FFmpeg binaries/shared libraries may be replaced in the bin folder.
OpenJDK runtime: GPLv2 with the Classpath Exception; see runtime/legal for all notices.
PocketCoder does not prevent debugging modifications to these third-party components.
'@ | Set-Content -Encoding utf8 (Join-Path $thirdParty 'NOTICE.txt')
$runtime=Join-Path $payloadRoot 'runtime'
if(!(Test-Path -LiteralPath $runtime)){
    & (Join-Path $env:JAVA_HOME 'bin\jlink.exe') --add-modules java.desktop,jdk.httpserver,java.management,jdk.unsupported,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtime
    if($LASTEXITCODE -ne 0){throw 'Runtime image creation failed'}
}
$compiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /target:winexe /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll ("/out:"+(Join-Path $payloadRoot 'PocketCoder-PC.exe')) (Join-Path $PSScriptRoot 'PocketCoderPC.cs') (Join-Path $PSScriptRoot 'RemoteViewer.cs')
if($LASTEXITCODE -ne 0){throw 'PC control window compilation failed'}
& $compiler /nologo /target:exe /reference:System.Drawing.dll /reference:System.Web.Extensions.dll ("/out:"+(Join-Path $payloadRoot 'PocketCoder-Cursor.exe')) (Join-Path $PSScriptRoot 'PocketCoderCursor.cs')
if($LASTEXITCODE -ne 0){throw 'Cursor helper compilation failed'}
& (Join-Path $payloadRoot 'PocketCoder-Cursor.exe') --self-test
if($LASTEXITCODE -ne 0){throw 'Cursor helper verification failed'}
$viewerTest=Start-Process -FilePath (Join-Path $payloadRoot 'PocketCoder-PC.exe') -ArgumentList '--viewer-self-test' -WindowStyle Hidden -Wait -PassThru
if($viewerTest.ExitCode -ne 0){throw 'PC viewer self-test failed'}
& $compiler /nologo /target:exe ("/out:"+(Join-Path $payloadRoot 'PocketCoder-Audio.exe')) (Join-Path $PSScriptRoot 'PocketCoderAudio.cs')
if($LASTEXITCODE -ne 0){throw 'Playback helper compilation failed'}
& (Join-Path $payloadRoot 'PocketCoder-Audio.exe') --self-test
if($LASTEXITCODE -ne 0){throw 'Audio conversion self-test failed'}
& (Join-Path $payloadRoot 'runtime\bin\java.exe') '-Dsun.java2d.uiScale=2' -jar (Join-Path $payloadRoot 'pocketcoder-server.jar') --self-test
if($LASTEXITCODE -ne 0){throw 'Bundled runtime/server self-test failed'}
@{version='0.9.6';ffmpegUrl=$url;ffmpegSha256=$expected;ffmpegSource='f1acb0a2666c78d92688f4c3d2803a132f5590f6';runtime=(Get-Content (Join-Path $runtime 'release') -Raw)} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $payloadRoot 'component-manifest.json')
Write-Output 'Windows payload, audio conversion and bundled runtime verified.'

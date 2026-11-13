# PocketCoder — Kotlin 원격 개발 콘솔

Android에서 Windows/macOS PC 화면을 보고 마우스와 키보드를 조작하는 MVP입니다.
0.6.3 앱은 **Tailscale 로그인만으로 연결**합니다. 토큰 입력이 필요 없습니다.
지원되는 Windows/NVIDIA 환경에서 DXGI GPU 직접 캡처·스케일링으로 H.264 목표 60fps를 사용합니다.
모니터 위치와 회전은 DXGI API에서 읽어 매칭하고 세로 모니터의 방향도 보정합니다.
지원하지 않는 FFmpeg/환경은 기존 GDI 30fps 경로로 호환됩니다. `--video-fps=30`으로 낮출 수 있습니다.
앱 자체 자판 대신 입력칸을 눌러 휴대폰 기본 키보드(삼성 키보드 등)를 사용합니다.
한/영 전환·조합·삭제는 휴대폰 키보드에서 하고, `전송`으로 PC에 붙여넣습니다. 자동 Enter는 하지 않으며 `↵`를 따로 누릅니다.
트랙패드는 높이 88dp로 줄였고, 입력칸 선택 시 마우스 패널을 자동으로 접어 원격 화면 공간을 확보합니다.
`펼치기`는 휴대폰 키보드를 닫고 마우스를 다시 표시합니다. `비우기`는 전송 전 입력창만 비웁니다.
원격 화면은 고정하고 가로에서는 화면 왼쪽·조작 오른쪽으로 배치합니다.
`전체`/`복귀`, 핀치 확대와 확대 화면 드래그 이동, PC Ctrl·방향키·삭제 단축키를 지원합니다.
연결 설정은 화면 크기를 밀어내지 않는 오버레이입니다. 고화질 적용에는 0.6.3 PC 서버를 사용합니다.
한 번 PC 주소를 입력해 연결하면 다음 앱 실행부터 자동 연결합니다.
다른 앱을 보는 동안 영상·입력을 닫아 배터리를 절약하고, PocketCoder로 돌아오면 자동 재연결합니다.
통신 실패도 앱이 보이는 동안 자동으로 재시도합니다. 마우스 입력 연결은 TCP_NODELAY를 사용하고 25ms 이동 제한을 제거했습니다.
FFmpeg가 없거나 다른 OS인 경우 연속 MJPEG(기본 목표 15fps)로 호환됩니다.

## 0.4.0 속도 개선 및 새 조작

- 이 PC에 설치된 FFmpeg를 PATH 또는 서버 폴더의 `ffmpeg.exe` / `bin/ffmpeg.exe`에서 찾습니다.
  NVIDIA NVENC를 실제 테스트 패턴으로 검증하여 우선 사용하고, 실패하면 libx264를 시도합니다.
  외부 명령을 전달하는 기능은 없으며 고정된 캡처/인코더 명령만 사용합니다. FFmpeg 바이너리는 저장소/Artifact에 포함하지 않습니다.
- H.264는 모니터 원본 해상도를 유지합니다(이 PC: 가로 1920×1080, 세로 1080×1920). GPU는 기기 주사율에 맞춰 최대 120fps를 요청하며 NVENC p4/VBR CQ12, 기준 40Mbps·최대 80Mbps이며 실제 사용량은 화면에 따라 달라집니다. CPU 호환 경로는 30fps/CRF12입니다.
  화질을 위해 전송량이 증가합니다. MJPEG도 원본 해상도/JPEG95로 전송합니다. 확대는 최대 6배입니다. B프레임/플레이어 사전 버퍼를 사용하지 않습니다.
  실제 프레임률·지연·화질은 PC/휴대폰/모바일 망에 따라 달라집니다. Android MediaCodec이 Surface로 직접 디코딩합니다.
- 입력은 인증된 하나의 chunked HTTP 연결에 URL 인코딩 이벤트를 LF로 구분해 보냅니다.
  매 입력마다 HTTP 응답 왕복을 기다리지 않습니다. 구형 서버에는 기존 POST 방식으로 호환합니다.
- 앱 화면 위 `모니터 1`, `모니터 2` 버튼으로 전환합니다. 화면과 마우스 경계를 함께 변경합니다.
  전환 중 도착한 이전 모니터의 입력은 버립니다. 선택은 서버 세션 전체에 적용됩니다.
- `PC ⌫`는 PC Backspace, `Delete`는 PC Delete 키입니다. 휴대폰 키보드의 삭제 키는 전송 전 입력만 지웁니다.
- Ctrl/Alt/Shift/CMD 조합키, 자주 쓰는 Ctrl+C/V/X/A/Z, 한글·여러 줄 붙여넣기를 지원합니다. 쉘 실행 API는 없습니다.

## APK 설치

1. https://github.com/gguma109/PocketCoder/actions 에서 성공한 Build Android APK 실행을 엽니다.
2. GitHub 로그인 후 Artifacts → pocketcoder-debug-apk를 내려받아 ZIP을 풉니다.
3. app-debug.apk를 Android 8.0(API 26) 이상 스마트폰으로 옮겨 엽니다.
4. 해당 파일 앱의 '알 수 없는 앱 설치'를 허용하고 설치합니다. 설치 후 허용을 다시 끄세요.

개발용 debug APK입니다. Artifact는 30일 보관되며 만료 시 Run workflow로 다시 빌드합니다.
CI의 debug 서명키가 바뀌어 업데이트가 거부되면 기존 앱 삭제 후 설치해야 합니다(저장 설정 삭제).

## Windows PC 서버 실행

1. 같은 Actions 실행에서 pocketcoder-desktop-server Artifact를 내려받아 ZIP을 풉니다.
2. Java/JDK 17 이상을 설치합니다. PATH의 Java 또는 Android Studio 기본 경로의 JBR을 사용합니다.
3. pocketcoder-server.jar와 run-windows.bat를 같은 폴더에 두고 BAT를 실행합니다.
4. 외부 접속은 `run-tailscale-windows.bat`를 사용하면 활성 Tailscale IPv4에 자동 바인딩합니다.
   수동 실행은 ipconfig의 Tailscale IPv4 또는 사설 LAN IPv4를 입력합니다.
5. 방화벽은 신뢰하는 개인 네트워크에만 허용합니다. 공용 네트워크에서는 허용하지 마세요.
6. Tailscale 모드에서는 토큰이 없습니다. 휴대폰도 PC와 같은 Tailscale 사용자 계정으로 로그인합니다.
   LAN/구형 서버 모드만 별도 토큰을 사용합니다.
7. Ctrl+C 또는 CMD 창 닫기로 서버를 종료합니다.

직접 실행: `java -jar pocketcoder-server.jar --bind=192.168.0.10 --port=8765`
바인딩 생략 시 127.0.0.1만 사용하여 휴대폰 연결은 불가능합니다.
서버는 wildcard(0.0.0.0) 및 공인 IPv4 바인딩을 거부합니다.
Tailscale 주소(100.64.0.0/10)는 허용합니다. 이 대역만으로 암호화가 보장되는 것은 아니며
실제로 두 기기가 Tailscale에 연결되어 있어야 합니다. 자세한 절차는 [외부 접속 안내](desktop-server/TAILSCALE_SETUP.md)를 보세요.
macOS는 `sh run-macos.sh`를 실행하고 Java/터미널에 화면 기록/손쉬운 사용 권한을 허용합니다.

## 스마트폰 연결

외부에서는 PC와 스마트폰을 같은 Tailscale 네트워크에 연결합니다(LTE/5G 가능).
LAN 접속은 같은 신뢰하는 Wi-Fi를 사용합니다. 앱에 PC LAN/Tailscale IP:포트와
PC 연결을 누릅니다. **Tailscale 로그인은 Tailscale 앱에서 한 번만** 진행합니다.
서버는 실제 접속 IP를 로컬 Tailscale 서비스에 조회하여 PC와 같은 사용자 소유 기기인지 확인합니다.
클라이언트가 보낸 이메일이나 X-Forwarded 헤더로 인증하지 않습니다.
다른 사용자/태그 기기/만료된 로그인/조회 실패는 거부합니다. PC 주소는 최초 한 번 필요합니다.
화면 탭은 해당 위치 클릭, 트랙패드는 마우스 이동,
길게 누르기는 우클릭, 두 손가락 이동은 스크롤입니다. L/R 버튼도 사용할 수 있습니다.
Ctrl/Alt/Shift/CMD는 다음 키 입력과 조합된 뒤 해제됩니다.
한글/여러 줄은 하단 입력창에서 전송합니다(1,000자 이하). PC 활성 창에 클립보드로 붙여넣으므로
대상 창을 먼저 선택하고 필요하면 Enter를 따로 누르세요. PC 클립보드를 일시 사용합니다.

## 보안

- 서버 HTTP 자체는 암호화되지 않습니다. 직접 통신은 신뢰하는 LAN 전용이고, 외부 접속은 Tailscale 암호화 터널을 사용합니다.
- 직접 HTTP를 공용 Wi-Fi에서 사용하지 마세요. 포트포워딩/Funnel/공용 터널로 TCP 8765를 인터넷에 노출하지 마세요.
  사설 주소 바인딩 자체가 라우터의 포트포워딩을 막지는 않습니다.
- 기본 Tailscale 모드는 토큰을 생성하지 않습니다. 동일 Tailscale 사용자 소유 기기만 허용합니다.
  `tailscale whois --json`으로 실제 소켓의 원격 주소를 검증하며 조회 실패 시 거부합니다.
  인증 캐시는 최대 5초이고 스트림 도중에도 재확인합니다. PC에서 Tailscale CLI 접근이 가능해야 합니다.
- LAN/구형 호환 모드 토큰은 시작 시 SecureRandom 256비트로 생성하고 Bearer 헤더에만 전달합니다.
  소스에 토큰/비밀번호를 넣지 않으며 토큰을 GitHub 이슈/로그에 올리지 마세요.
- 앱은 주소만 로컬 저장하며 토큰은 Preferences/화면 상태/자동완성에 저장하지 않습니다.
  HTTP 리디렉션을 거부하여 다른 서버로 토큰을 전달하지 않습니다.
- Android 권한은 INTERNET/ACCESS_NETWORK_STATE뿐입니다. cleartext HTTP를 명시 허용하고 백업을 끕니다.
- 토큰 소유자는 PC 활성 창을 조작할 수 있습니다. 본인만 사용하세요. 별도 원격 쉘 실행 기능은 없습니다.

## 빌드와 검증

Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk 35, targetSdk 34, minSdk 26입니다.
앱은 Android 프레임워크만 사용합니다. SDK는 환경 변수 또는 Git에 제외되는 local.properties로 지정합니다.
원본 ZIP에는 Gradle Wrapper가 없어 CI에서 Gradle 8.9를 설치합니다. 로컬도 Gradle 8.9를 설치하세요.

```text
cd android-client
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: android-client/app/build/outputs/apk/debug/app-debug.apk

```text
cd desktop-server
gradle --no-daemon serverJar selfTest
java -jar pocketcoder-server.jar --self-test
```

build-server.sh도 Gradle 8.9와 JDK 17 이상을 사용합니다(서버 JSON 파싱 의존성 포함).
CI는 main 푸시/수동 실행 시 APK/lint와 서버 JAR/자체 테스트를 수행하고 두 Artifact를 업로드합니다.
자체 테스트는 Unicode, 한글 1,000자, 키 매핑, 인증/메서드, LAN/Tailscale 주소 경계,
실제 HTTP로 전송한 MJPEG 3프레임의 파싱/디코딩을 확인합니다.
Android 테스트는 multipart 분할/잘못된 크기, 입력 병합/만료/순서/큐 제한과
실제 ScrollView 안의 트랙패드 드래그가 페이지를 스크롤하지 않는 회귀 테스트를 포함합니다.

## 프로토콜

Tailscale 모드 요청은 Authorization 헤더 없이 로그인된 기기 신원으로 인증합니다.
LAN/구형 모드 요청만 `Authorization: Bearer <실행 시 생성된 토큰>`을 요구합니다.

| 요청 | 내용 |
| --- | --- |
| GET /health | JSON: ok, width, height, display, stream, fps, auth |
| GET /frame | JPEG, 최대 1280×720 |
| GET /stream | 인증된 multipart/x-mixed-replace MJPEG, 한 연결에서 연속 프레임 |
| GET /video | PCV1 헤더 및 길이/타임스탬프가 있는 Annex-B H.264 access unit |
| POST /input | UTF-8 application/x-www-form-urlencoded, 최대 32 KiB |
| POST /input-stream | URL 인코딩 입력을 LF로 구분하는 지속 chunked 요청, 이벤트당 최대 32 KiB |
| POST /display | index=0..모니터수-1, 캡처/마우스 경계 동시 전환 |

입력 type: move(dx/dy), tap(x/y: 0..10000), click/double(button), wheel(amount),
key(key/ctrl/alt/shift/meta), text(value). 입력은 순서대로 전송합니다.
한글 1,000자는 URL 인코딩 후 8 KiB를 넘으므로 서버 제한을 32 KiB로 수정했습니다.
재연결 시 이전 세션이 새 주소/토큰을 사용하지 않도록 세션별 값을 고정합니다.
스트림은 한 번에 한 시청자만 허용하고 UI에는 최신 프레임 하나만 대기시킵니다.
화면 캡처와 입력 Robot을 분리하고 화면에 마우스 커서를 합성합니다.
인접 마우스 이동은 병합하며 250ms 넘게 밀린 이동을 버려 입력 지연 누적을 줄입니다.
클릭/키/텍스트 순서는 유지하며 입력 큐는 최대 64개입니다(가득 차면 앱에 지연 경고).
트랙패드 조작 중에는 바깥 ScrollView의 터치 가로채기를 막고 종료/취소 시 해제합니다.

## 제한 및 실기기 확인

실제 PC-휴대폰 화면/마우스/키보드/한글 입력은 사용자 기기에서 최종 확인해야 합니다.
Windows의 FFmpeg/NVENC 및 D3D11 캡처·스케일링 지원 시 H.264 목표 60fps를 사용합니다(기타 경로 30fps).
호환 화면은 MJPEG 기본 목표 15fps이며 서버 `--fps=1..30`은 MJPEG에만 적용됩니다. 실제 속도는 미보장입니다.
구형 서버는 기존 약 2–4fps JPEG 폴링으로 자동 호환합니다.
MJPEG는 H.264보다 대역폭이 큽니다. NVENC/Android 하드웨어 디코더를 사용해도 Parsec 수준의 지연을 보장하지 않으며
오디오, 파일 전송, 서버 자체 TLS는 미구현입니다.
확대는 중앙 기준 1–3배이며 패닝은 없습니다. 앱 모니터 버튼 또는 서버 시작 시 --display=N을 사용합니다.
Windows UAC/보안 데스크톱과 관리자 창 입력, 터미널 붙여넣기는 OS/앱 설정에 따라 제한됩니다.

## 구조

- android-client/: Kotlin Android UI와 HTTP 클라이언트
- desktop-server/: Kotlin/JVM 서버, 실행 스크립트, Gradle 서버 빌드 (생성 JAR은 Git 제외)
- shared/: 클라이언트/서버가 함께 사용하는 multipart 프레이밍 및 입력 버퍼
- .github/workflows/build-android-apk.yml: APK/서버 자동 빌드 및 Artifacts

독립 작성된 MVP 예시이며 특정 상용 원격제어 앱과의 호환성을 보장하지 않습니다.

0.6.3 마우스는 약 125Hz 주기로 최대 16ms 범위의 이동을 분산하여 적용합니다. 클릭 전 대기 이동을 먼저 반영하고 모니터 전환 시 폐기합니다. Robot의 강제 지연도 제거했습니다. 실제 FPS는 캡처·회전·인코딩·네트워크·디코더 성능에 따라 달라집니다.

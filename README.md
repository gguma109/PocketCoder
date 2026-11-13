# PocketCoder — Kotlin 원격 개발 콘솔

Android에서 Windows/macOS PC 화면을 보고 마우스와 키보드를 조작하는 LAN 전용 MVP입니다.
원본 Kotlin UI, JPEG 화면, 트랙패드, 클릭/우클릭/더블클릭/스크롤, 개발자 키보드,
Ctrl/Alt/Shift/Meta 조합키와 한글 붙여넣기를 유지합니다. 쉘 실행 API는 없습니다.

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
4. 출력된 ipconfig에서 현재 Wi-Fi/이더넷의 사설 IPv4 주소를 입력합니다. 예: 192.168.0.10.
5. 방화벽은 신뢰하는 개인 네트워크에만 허용합니다. 공용 네트워크에서는 허용하지 마세요.
6. 창에 표시된 Access token을 휴대폰에 입력합니다. 재시작마다 토큰이 바뀝니다.
7. Ctrl+C 또는 CMD 창 닫기로 서버를 종료합니다.

직접 실행: `java -jar pocketcoder-server.jar --bind=192.168.0.10 --port=8765`
바인딩 생략 시 127.0.0.1만 사용하여 휴대폰 연결은 불가능합니다.
서버는 wildcard(0.0.0.0) 및 공인 IPv4 바인딩을 거부합니다.
macOS는 `sh run-macos.sh`를 실행하고 Java/터미널에 화면 기록/손쉬운 사용 권한을 허용합니다.

## 스마트폰 연결

PC와 스마트폰을 같은 신뢰하는 LAN/Wi-Fi에 연결합니다. 앱에 PC IP:포트(예: 192.168.0.10:8765)와
서버 토큰을 입력하고 PC 연결을 누릅니다. 화면 탭은 해당 위치 클릭, 트랙패드는 마우스 이동,
길게 누르기는 우클릭, 두 손가락 이동은 스크롤입니다. L/R 버튼도 사용할 수 있습니다.
Ctrl/Alt/Shift/CMD는 다음 키 입력과 조합된 뒤 해제됩니다.
한글/여러 줄은 하단 입력창에서 전송합니다(1,000자 이하). PC 활성 창에 클립보드로 붙여넣으므로
대상 창을 먼저 선택하고 필요하면 Enter를 따로 누르세요. PC 클립보드를 일시 사용합니다.

## 보안

- HTTP는 암호화되지 않습니다. 신뢰할 수 있는 LAN 전용입니다. 토큰/화면/입력은 도청될 수 있습니다.
- 공용 Wi-Fi에서 실행하지 마세요. 라우터 포트포워딩/터널로 TCP 8765를 인터넷에 노출하지 마세요.
  사설 주소 바인딩 자체가 라우터의 포트포워딩을 막지는 않습니다.
- 토큰은 서버 시작 시 SecureRandom 256비트로 생성하고 Bearer 헤더에만 전달합니다.
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
gradle --no-daemon :app:assembleDebug :app:lintDebug
```

APK: android-client/app/build/outputs/apk/debug/app-debug.apk

```text
cd desktop-server
gradle --no-daemon serverJar selfTest
java -jar pocketcoder-server.jar --self-test
```

기존 build-server.sh도 kotlinc와 JDK 17 이상으로 사용 가능합니다.
CI는 main 푸시/수동 실행 시 APK/lint와 서버 JAR/자체 테스트를 수행하고 두 Artifact를 업로드합니다.
자체 테스트는 Unicode 폼 인코딩, 한글 1,000자, 키 매핑, 토큰 생성, 사설 바인딩 제한을 확인합니다.

## 프로토콜

모든 요청은 `Authorization: Bearer <실행 시 생성된 토큰>`을 요구합니다.

| 요청 | 내용 |
| --- | --- |
| GET /health | JSON: ok, width, height, display |
| GET /frame | JPEG, 최대 1280×720 |
| POST /input | UTF-8 application/x-www-form-urlencoded, 최대 32 KiB |

입력 type: move(dx/dy), tap(x/y: 0..10000), click/double(button), wheel(amount),
key(key/ctrl/alt/shift/meta), text(value). 입력은 순서대로 전송합니다.
한글 1,000자는 URL 인코딩 후 8 KiB를 넘으므로 서버 제한을 32 KiB로 수정했습니다.
재연결 시 이전 세션이 새 주소/토큰을 사용하지 않도록 세션별 값을 고정합니다.

## 제한 및 실기기 확인

실제 PC-휴대폰 화면/마우스/키보드/한글 입력은 사용자 기기에서 최종 확인해야 합니다.
화면은 약 2–4fps JPEG 폴링입니다. 오디오, 파일 전송, TLS는 미구현입니다.
확대는 중앙 기준 1–3배이며 패닝은 없습니다. 모니터 선택은 서버 --display=N 옵션을 사용합니다.
Windows UAC/보안 데스크톱과 관리자 창 입력, 터미널 붙여넣기는 OS/앱 설정에 따라 제한됩니다.

## 구조

- android-client/: Kotlin Android UI와 HTTP 클라이언트
- desktop-server/: Kotlin/JVM 서버, 실행 스크립트, Gradle 서버 빌드 (생성 JAR은 Git 제외)
- .github/workflows/build-android-apk.yml: APK/서버 자동 빌드 및 Artifacts

독립 작성된 MVP 예시이며 특정 상용 원격제어 앱과의 호환성을 보장하지 않습니다.

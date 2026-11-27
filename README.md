# 드림랜드 앱 (Android WebView)

[드림랜드](https://github.com/geminibug2026/DreamLand)를 안드로이드 앱처럼 쓰기 위한 웹뷰 앱입니다.
드림랜드 서버는 각자 폰의 **터묵스(Termux)** 에서 켜고, 이 앱은 그 주소를 화면에 띄워 줍니다.

- 기본 주소: `http://127.0.0.1:4173` (드림랜드 `server.js` 의 기본 포트)
- 주소는 사람마다 폰에 따로 저장되고, 앱 안에서 바꿀 수 있습니다.
- 패키지 이름: `com.dreamland.webview`

---

## 쓰는 사람용

1. [Releases](https://github.com/IY-0320/webview-app/releases)에서 `dreamland.apk` 를 받아 설치합니다.
2. 터묵스에서 드림랜드를 켭니다: `sh start.sh`
3. 앱을 엽니다.

**주소 바꾸기**
- 연결에 실패하면 나오는 화면에서 **주소 변경**을 누릅니다.
- 또는 앱 아이콘을 길게 눌러 **서버 주소 변경**을 고릅니다.
- `http://` 는 빼고 써도 됩니다. (`127.0.0.1:4180` 처럼)

> 같은 폰에서 실리태번(SillyTavern)도 쓰는 경우: 실리태번은 기본 포트가 8000 이라서 서로 겹치지 않습니다.
> 드림랜드 포트를 바꿨다면(`PORT=4180 sh start.sh`) 앱의 주소도 똑같이 바꿔 주세요.

---

## 개발: Android Studio 에서 열기

1. Android Studio 에서 **File → Open** → 이 저장소 폴더를 엽니다.
2. Gradle 동기화가 끝날 때까지 기다립니다. (Gradle 8.10.2 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17)
3. 폰을 USB로 연결하거나 에뮬레이터를 켜고 ▶ **Run** 을 누릅니다.

명령줄로 빌드할 때:

```sh
./gradlew assembleDebug      # 테스트용 → app/build/outputs/apk/debug/
./gradlew assembleRelease    # 배포용 → app/build/outputs/apk/release/
```

> 에뮬레이터에서는 `127.0.0.1` 이 에뮬레이터 자신을 뜻합니다.
> PC 에서 켠 드림랜드에 붙으려면 앱 주소를 `10.0.2.2:4173` 으로 바꿔 주세요.

### 서명 (중요)

배포용 APK 는 **항상 같은 키**로 서명해야 사용자가 지우지 않고 업데이트할 수 있습니다.

- 키 파일 `dreamland-release.jks` 는 저장소에 **올리지 않습니다** (`.gitignore` 로 막아 둠). 따로 백업해 두세요.
- 키 별칭(alias): `dreamland`, 키 비밀번호 = 저장소(store) 비밀번호
- `app/build.gradle.kts` 는 환경 변수로 키를 받습니다.

| 환경 변수 | 내용 |
|---|---|
| `KEYSTORE_FILE` | `.jks` 파일 경로 |
| `KEYSTORE_PASSWORD` | 키 비밀번호 |
| `VERSION_CODE` | 버전 번호 (정수, 계속 커져야 함) |
| `VERSION_NAME` | 표시용 버전 (예: `1.0.5`) |

환경 변수가 없으면 디버그 키로 서명됩니다. 이 APK 는 배포용 APK 와 서로 덮어 설치할 수 없습니다.

PC 에서 배포용으로 직접 빌드하려면:

```sh
KEYSTORE_FILE=/경로/dreamland-release.jks KEYSTORE_PASSWORD=비밀번호 \
VERSION_CODE=100 VERSION_NAME=1.1.0 ./gradlew assembleRelease
```

> `VERSION_CODE` 는 이미 배포한 번호보다 커야 업데이트가 설치됩니다. GitHub Actions 는 빌드 번호(1, 2, 3…)를 쓰므로,
> PC 에서 빌드할 때는 그보다 충분히 큰 수를 쓰세요.

### 자동 빌드 (GitHub Actions)

`.github/workflows/build.yml`

- **Actions → Build APK → Run workflow**: `build-N` 릴리스로 올라갑니다.
- `v1.2` 같은 태그를 push 해도 같은 빌드가 돌고, 그 태그 이름으로 릴리스됩니다.
- 저장소 Secrets 에 `KEYSTORE_BASE64` (jks 파일을 base64 로 바꾼 것), `KEYSTORE_PASSWORD` 가 있어야 합니다. 없으면 빌드가 실패합니다.

---

## 파일 구조

```
app/src/main/java/com/dreamland/webview/MainActivity.kt   웹뷰, 주소 설정, 오류 화면, 파일 업로드, 뒤로가기
app/src/main/AndroidManifest.xml                           권한, http 허용, 앱 아이콘 길게 누르기 메뉴
app/src/main/res/values/strings.xml                        앱 이름, 메뉴 이름
app/src/main/res/xml/shortcuts.xml                         "서버 주소 변경" 바로가기
app/build.gradle.kts                                       버전, 서명 설정
.github/workflows/build.yml                                자동 빌드 + 릴리스
docs/STATUS.md                                             작업 현황 (새 세션에서 먼저 읽기)
```

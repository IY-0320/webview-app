# Dreamland 앱 (Android WebView)

**드림랜드 본체: <https://github.com/geminibug2026/DreamLand>**

[드림랜드](https://github.com/geminibug2026/DreamLand)를 안드로이드 앱처럼 쓰기 위한 웹뷰 앱입니다.
드림랜드 서버는 각자 폰의 **터묵스(Termux)** 안에서 돌고, 이 앱은 터묵스에 드림랜드를 켜고 끄라고 부탁하고 그 화면을 띄워 줍니다.

- 기본 주소: `http://127.0.0.1:4173` (드림랜드 `server.js` 의 기본 포트)
- 주소 · 포트 · 드림랜드 폴더는 사람마다 폰에 따로 저장되고, 앱의 설정에서 바꿀 수 있습니다.
- 패키지 이름: `com.dreamland.webview`

---

## 쓰는 사람용

### 1. 앱 설치

[Releases](https://github.com/IY-0320/webview-app/releases)에서 `dreamland.apk` 를 받아 설치합니다.

### 2. 처음 열면

앱을 처음 열면 이렇게 묻습니다.

- **드림랜드를 이미 이용하고 있나요? → 네**: 바로 "꿈속으로" 화면으로 갑니다.
- **드림랜드가 처음인가요? → 네**: 설치 안내가 열립니다. 아래 순서를 앱이 하나씩 안내해 줍니다.

### 3. 드림랜드 설치 (처음인 사람)

드림랜드는 폰의 터묵스 안에서 돌아갑니다. 자세한 설명은 [드림랜드 README](https://github.com/geminibug2026/DreamLand#readme)에 있습니다.

1. **터묵스 설치** — [F-Droid](https://f-droid.org/packages/com.termux/) 또는 [GitHub](https://github.com/termux/termux-app/releases/latest) 판을 받습니다. 앱의 버튼을 누르면 무엇을 받아야 하는지 먼저 알려 줍니다.
   - **F-Droid**: 앱 이름 `Termux`, 설명 "Terminal emulator with packages", 패키지 `com.termux`. 페이지 아래 Versions 에서 **suggested** 표시가 붙은 버전의 **Download APK** (모든 폰 공용, F-Droid 앱은 없어도 됨)
   - **GitHub**: 정식판(**Latest**) 의 Assets 에서 `termux-app_v<버전>+github-debug_arm64-v8a.apk` (요즘 폰 대부분). 모르겠으면 `..._universal.apk`. `sha256sums` 는 설치 파일이 아님
   - F-Droid 판과 GitHub 판은 서명이 달라 섞어 쓸 수 없습니다. 한 곳을 골랐으면 업데이트도 그곳에서 받으세요.
2. **터묵스 준비 (한 번만)** — 터묵스를 한 번 열어 첫 설치가 끝나길 기다린 뒤, 아래 명령을 붙여 넣고 엔터를 누릅니다.
   이 앱이 터묵스에 "드림랜드 켜 줘" 하고 부탁할 수 있게 하는 설정입니다.

   ```sh
   mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
   ```

3. **실행 권한** — 앱이 묻는 "터묵스 실행 권한"을 허용합니다.
4. **드림랜드 설치하고 들어가기** — 앱의 버튼을 누르면 터묵스 창에서 드림랜드 README 의 명령을 차례로 실행하고, 끝나면 바로 켜서 들어갑니다.
   직접 하고 싶다면 터묵스에서:

   ```sh
   pkg upgrade
   pkg install git nodejs-lts
   git clone https://github.com/geminibug2026/DreamLand.git dreamland
   ```

   > 드림랜드 README 에는 `pkg update` 라고 되어 있지만, 터묵스를 새로 깐 폰에서는 `pkg upgrade` 를 써야 합니다.
   > `pkg update` 는 목록만 새로 고쳐서, 오래된 기본 라이브러리 때문에 node 가 `CANNOT LINK EXECUTABLE "node"` 오류로 실행되지 않습니다.

### 4. 쓰기

| 화면 · 버튼 | 하는 일 |
|---|---|
| **꿈속으로** (달) | 드림랜드가 꺼져 있으면 터묵스로 켜고 들어갑니다. 켜져 있으면 바로 들어갑니다 |
| **일어나기** (해) | 드림랜드를 끕니다. 두 번 눌러야 합니다. 같은 터묵스의 실리태번 등 다른 서버는 건드리지 않습니다 |
| 드림랜드 맨 앞 화면에서 **뒤로가기** | 꿈속으로 화면으로 돌아옵니다 |
| 오른쪽 위 **새로고침** | 드림랜드가 켜져 있는지 다시 확인합니다 |
| 오른쪽 위 **톱니바퀴** | 설정: 터묵스 화면으로 이동 여부, 화면 꺼져도 유지, 드림랜드 폴더, 포트, 서버 주소, 처음 안내 다시 보기 |
| 앱 아이콘 길게 누르기 → **설정** | 설정을 바로 엽니다 |

**드림랜드 업데이트**는 터묵스에서 `cd dreamland && git pull` 합니다. ([드림랜드 README](https://github.com/geminibug2026/DreamLand#readme) 의 "업데이트")

> 같은 폰에서 실리태번(SillyTavern)도 쓰는 경우: 실리태번은 기본 포트가 8000 이라서 서로 겹치지 않습니다.
> 드림랜드 포트를 바꿨다면(`PORT=4180 sh start.sh`) 앱 설정의 포트도 똑같이 바꿔 주세요.
> 포트가 바뀌면 드림랜드 저장 공간도 따로라서 데이터가 비어 보일 수 있습니다.

---

## 개발: Android Studio 에서 열기

1. Android Studio 에서 **File → Open** → 이 저장소 폴더를 엽니다.
2. Gradle 동기화가 끝날 때까지 기다립니다. (Gradle 8.10.2 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 또는 21)
   - Android Studio 에 든 JDK 가 25 라면 Gradle 이 `25.0.3` 이라는 에러만 내고 멈춥니다.
     **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** 를 21 로 바꿔 주세요.
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
app/src/main/java/com/dreamland/webview/MainActivity.kt   웹뷰 두 장(첫 화면 · 드림랜드), 서버 확인, 터묵스 켜기 · 끄기 · 설치 · 업데이트, 설정 저장, 시스템 막대 색, 뒤로가기, 들어가기 · 돌아오기 애니메이션
app/src/main/java/com/dreamland/webview/Saver.kt           드림랜드의 내보내기 · 백업 받기 → 다운로드/Dreamland
app/src/main/java/com/dreamland/webview/Voice.kt           드림랜드의 글 읽어 주기 · 말로 입력하기 → 안드로이드 음성 엔진
app/src/main/java/com/dreamland/webview/Picker.kt          드림랜드의 고르기 칸 · 입력칸 추천 목록을 드림랜드 테마로
app/src/main/assets/start.html                             첫 화면(꿈속으로 · 일어나기), 설정, 처음 질문, 설치 안내
app/src/main/AndroidManifest.xml                           권한(인터넷 · 터묵스 실행 · 진동 · 마이크), http 허용, 앱 아이콘 길게 누르기 메뉴
app/src/main/res/values/themes.xml                         앱 테마 (Android 15 edge-to-edge 끄기)
app/src/main/res/values/strings.xml                        앱 이름, 메뉴 이름
app/src/main/res/xml/shortcuts.xml                         "설정" 바로가기
app/src/main/res/drawable · mipmap*                        앱 아이콘 (초승달 + 반짝이)
app/build.gradle.kts                                       버전, 서명 설정
.github/workflows/build.yml                                자동 빌드 + 릴리스
docs/STATUS.md                                             작업 현황 (새 세션에서 먼저 읽기)
CLAUDE.md                                                  Claude 세션이 처음에 읽는 안내 (STATUS.md 로 안내 + 규칙)
tools/cdp*.js                                              폰의 WebView 를 개발자 도구로 들여다보는 시험용 스크립트 (디버그 빌드)
```

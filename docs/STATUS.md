# 작업 현황

> 새 세션을 열면 이 파일부터 읽어 주세요. 작업이 끝날 때마다 아래 내용을 갱신합니다.

마지막 갱신: 2026-11-27

## 한 줄 요약

드림랜드(터묵스에서 도는 웹 서버)를 켜고 · 끄고 · 처음 설치까지 해 주는 안드로이드 웹뷰 앱 **Dreamland**.
GitHub Actions 로 서명된 APK 를 빌드해 Releases 에 배포. 노트북(Windows)의 Android Studio 에서 개발 · 폰은 adb 로 시험.

## 지금 상태 (한눈에)

- **최신 릴리스: [v1.0.0](https://github.com/IY-0320/webview-app/releases/tag/v1.0.0)** (Actions 빌드 3번, `versionCode` 3). 저장소는 **공개**
  - 잠깐 v1.1.0 으로 올렸다가 버전을 1.0.0 으로 바꾸기로 해서 v1.1.0 릴리스 · 태그는 지움
- 코드: `main` 의 `0f3930e` 까지 올라가 있음
- 폰 두 대 (둘 다 지금 **디버그 서명** 앱이 깔려 있음 → 배포판을 깔려면 먼저 지워야 하고, 지우면 앱 안의 드림랜드 데이터도 지워짐)
  - **갤럭시 S22 울트라** (Android 15, USB) — 실제로 쓰는 폰. 드림랜드 · 실리태번(:8000) 이 같은 터묵스에 있음
  - **갤럭시 A23 공기계** (Android 14, 무선 디버깅) — 처음 설치 흐름을 처음부터 끝까지 확인한 폰
- **가장 먼저 할 일: 파일 내보내기(다운로드) 지원.** 드림랜드의 백업 내보내기 등이 앱 안에서 안 됨 → 이게 돼야 S22 의 데이터를 백업하고 배포판으로 갈아탈 수 있음 (아래 "다음에 할 일")

### 다음 세션에서 이어서 하는 법

```sh
# 빌드 (Gradle JDK 는 21 — Android Studio 기본 JDK 25 로는 안 됨)
JAVA_HOME="C:/Users/천보람/.jdks/jbr-21.0.11" ./gradlew assembleDebug
# 폰에 설치 (기기가 여러 대면 -s 로 고르기. adb devices -l 로 확인)
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <기기> install -r app/build/outputs/apk/debug/app-debug.apk
# 릴리스: v 로 시작하는 태그를 올리면 Actions 가 빌드해서 Releases 에 올림
git tag -a v1.0.1 -m "..." && git push origin v1.0.1
```

- 공기계 무선 디버깅: 폰의 개발자 옵션 → 무선 디버깅 화면에 나온 주소로 `adb connect <IP:포트>` (페어링은 이미 함. 포트는 껐다 켜면 바뀜)
- 첫 화면 속을 들여다볼 때: 디버그 빌드는 WebView 개발자 도구가 켜져 있음 →
  `adb forward tcp:9333 localabstract:webview_devtools_remote_<앱 pid>` 후 `http://127.0.0.1:9333/json` 의 웹소켓으로 `Runtime.evaluate`

## 관련 저장소

| 저장소 | 역할 |
|---|---|
| `IY-0320/webview-app` (이 저장소) | 안드로이드 웹뷰 앱 |
| [`geminibug2026/DreamLand`](https://github.com/geminibug2026/DreamLand) | 드림랜드 본체 (남의 저장소, 읽기만). 서버 `server.js`, 기본 포트 4173 |
| `IY-0320/SceneBook-add` | 다른 프로젝트 (씬북). 이 앱과 관계없음 |

## 지금까지 한 일

1. 웹뷰 앱 기본 구조 (Kotlin, minSdk 24 / targetSdk 35)
   - 파일 업로드(캐릭터 카드 등), 뒤로가기, 서버가 꺼져 있을 때 안내 화면
2. 서버 주소를 코드에 박지 않고 **폰마다 저장** (SharedPreferences `dreamland` / `server_url`)
   - (지금은) 첫 화면의 톱니바퀴 설정 또는 앱 아이콘 길게 누르기 → "설정" 에서 주소 · 포트를 바꿈 (8번)
3. **고정 서명 키**로 배포용 APK 서명 → 업데이트가 덮어 설치됨
   - 키: `dreamland-release.jks`, alias `dreamland` (저장소에 없음. 사용자가 따로 보관)
   - GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` 등록 완료
   - `versionCode` = Actions 빌드 번호
4. 첫 빌드 `build-1` (1.0.1) 릴리스 성공
5. **버그 수정:** 기본 주소가 `:8000` 이라 같은 폰의 실리태번이 열렸음
   → 드림랜드 기본 포트 `:4173` 으로 변경. 1.0.1 에서 `:8000` 이 저장된 경우도 자동으로 `:4173` 으로 옮김
6. Gradle wrapper(8.10.2) 추가 → Android Studio 에서 바로 열 수 있음. 정리 문서(`README.md`, 이 파일) 작성
7. 노트북(Windows)에서 Android Studio 로 열고 **로컬 디버그 빌드 성공** (2026-11-27)
   - Android Studio 내장 JDK 가 **Java 25** 라서 Gradle 8.10.2 가 `25.0.3` 이라는 에러만 내고 실패함
   - 해결: Gradle 용 JDK 를 **21** 로 지정 (`C:\Users\천보람\.jdks\jbr-21.0.11`, `C:\Program Files\Java\jdk-21` 도 있음)
   - Android Studio: Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK** 를 `jbr-21` 로
   - 결과물: `app/build/outputs/apk/debug/app-debug.apk`

8. **첫 화면 "꿈속으로" + 터묵스로 깨우기** (2026-11-27)
   - `assets/start.html`: 드림랜드 홈의 성도 바퀴(home.css `.hm-hero` / `.hm-wheel`)와 밤하늘·종이 색(fine.css)을 따라 만든 첫 화면
     - 우측 상단: 새로고침 · 설정(톱니바퀴). 설정은 종이 시트: 터묵스 화면으로 이동 여부, wake-lock, 드림랜드 폴더, 포트, 서버 주소, 터묵스 연결 상태
   - 흐름: 앱 켬 → 서버 확인 → 켜져 있으면 바로 드림랜드 / 꺼져 있으면 "꿈속으로" → 누르면 터묵스 `RUN_COMMAND` 로
     `cd ~/dreamland && PORT=<포트> node server.js` 실행 → 0.7초마다 확인 → 켜지면 드림랜드로
     (`start.sh` 는 `--open` 으로 외부 브라우저를 열어서 안 씀)
   - 드림랜드 맨 앞 화면에서 뒤로가기 → 첫 화면. 첫 화면에서 뒤로가기 → 앱 종료
   - WebView 두 개: 첫 화면(앱 기능 다리 `Dream` 연결)과 드림랜드(다리 없음)
   - 저장값: SharedPreferences `dreamland` 의 `host`, `port`, `folder`, `show_termux`, `wake_lock`. 예전 `server_url` 은 자동으로 옮김
   - 같은 포트에 드림랜드가 아닌 서버(실리태번 등)가 있으면 제목(`Dreamland`)으로 알아채고 안내
   - **로그인:** 드림랜드에는 아이디/비밀번호가 없음. 대신 `--lan` 으로 켠 다른 기기에 붙을 때 서버가 401 을 주면 앱의 "접속 키" 시트가 뜸 (키는 서버를 켤 때마다 바뀜)
   - 터묵스 쪽 준비(처음 한 번): 앱 권한 허용 + 터묵스에서 `allow-external-apps=true` (설정 시트에 복사 버튼 있음)
   - 폰에서 확인: 켜져 있으면 바로 들어감, 뒤로가기 → 첫 화면, 꺼진 상태에서 "꿈속으로" → 터묵스가 켜지고 약 5초 뒤 4173 열림
9. **일어나기(드림랜드 끄기)** (2026-11-27, 처음엔 "잠재우기"였다가 이름 · 아이콘 변경)
   - 첫 화면에서 깨어 있을 때(이 폰의 터묵스일 때만) "일어나기" 버튼 (해 아이콘, "꿈속으로"의 달과 짝). 실수 방지로 두 번 눌러야 함
   - 문구: "한 번 더 누르면 꿈에서 깨요" → "꿈에서 깨어나는 중…" → "꿈에서 깨어났어요. 다시 누르면 꿈속으로 돌아가요."
   - 터묵스에 창 없이(background) 명령을 보내서, **실행 파일이 node · 첫 인자가 server.js · 작업 폴더가 드림랜드 폴더 · PORT 가 설정 포트(없으면 4173)** 인 프로세스만 SIGTERM
     → 같은 터묵스의 실리태번(`node server.js`)은 건드리지 않음. 폰에서 확인: 4173 만 닫히고 8000 · 다른 node 는 그대로
   - wake-lock 설정이 켜져 있으면 `termux-wake-unlock` 도 함께. 앱이 켰던 터묵스 창은 종료 코드 143 이면 바로 닫힘
   - 처음 시험 때 한 번은 두 번째 탭 뒤에 아무 반응이 없었음 (로그를 넣기 전이라 원인 모름). 그 뒤로는 계속 정상 (끄기 약 1초, 켜기 2~4초). 다시 생기면 알려 주기
10. **시스템 막대가 드림랜드 테마를 따라감** (2026-11-27)
   - 드림랜드 화면에서 1초마다 화면 맨 아래 · 맨 위 가운데의 배경색을 읽음 (`BAR_COLOR_JS`, 읽기만 함. 못 찾으면 드림랜드가 넣는 `meta theme-color`)
   - 아래 막대 = 그 색(창 바탕을 칠하고 시스템 막(scrim)은 끔), 아이콘 밝기는 색 밝기로. 위 상태 표시줄 아이콘도 맨 위 색에 맞춰 밝기 바꿈
   - 첫 화면에서는 밤하늘색. 앱이 안 보일 때는 읽지 않음
   - 폰에서 확인: 기본(밝은) 테마에서 하단 메뉴와 같은 흰색 막대. **어두운 테마로 바꿨을 때는 아직 직접 확인 못 함**
11. **설정 바로 저장** (2026-11-27)
   - 버그: 스위치를 바꾸고 "저장"을 안 누른 채 닫으면 저장되지 않았음 (터묵스 화면으로 이동을 꺼도 다시 켜져 있던 원인)
   - 지금: 스위치는 누르는 즉시, 입력칸은 칸을 나갈 때, 시트를 닫을 때(X · 바깥 · 뒤로가기) 저장. 버튼은 "완료"
12. **"터묵스 화면으로 이동" 끔이 실제로 동작하게** (2026-11-27)
   - 터묵스 `RUN_COMMAND_SESSION_ACTION=2`(새 창, 화면은 안 엶)는 터묵스 0.119 에서 명령이 아예 실행되지 않았음
   - 지금: 켬 → 터묵스 창에서 실행하고 화면 열기(0) / 끔 → 창 없이 background 로 실행 (터묵스 알림에 task 로 보임)
   - 폰에서 확인: 끔 상태에서 꿈속으로 → 터묵스 안 뜨고 약 4초 뒤 드림랜드. 이렇게 켠 것도 일어나기로 꺼짐
13. **문구:** 꿈속으로 누른 뒤 "드림랜드로 들어가는 중 · N초" (터묵스 창 문구도 같게) (2026-11-27)
14. **처음 질문 + 설치 안내** (2026-11-27)
   - 앱을 처음 열면 "어서 오세요" + FIRST STEPS 카드 (드림랜드 홈의 첫 안내 카드를 따라 만듦)
     - "드림랜드를 이미 이용하고 있나요? 네" → 꿈속으로 화면
     - "드림랜드가 처음인가요? 네" → 설치 안내 시트 "드림랜드 들이기"
   - 설치 안내 4단계: 01 터묵스 설치(자동 감지, F-Droid · GitHub 링크) / 02 외부 앱 허용 명령 붙여 넣기(앱이 알 수 없어 "했어요"를 직접 표시, 이 기기에만 기억) / 03 실행 권한(자동 감지) / 04 버튼 하나로 설치하고 들어가기
   - 04 는 드림랜드 README 의 Termux 순서를 따름: `pkg upgrade`(README 는 `pkg update` — 17번 참고) → `pkg install git nodejs-lts` → `git clone …DreamLand.git` (폴더가 이미 있으면 건너뜀) → 바로 켜기. 언제나 터묵스 창에서 실행해 진행 상황이 보임. 앱은 최대 20분 기다리다 켜지면 들어감
   - 이미 켜져 있으면 설치 없이 바로 들어감 (폰에서 확인)
   - 저장값 `onboarded`. 이 판 전부터 쓰던 사람(설정이 이미 있음)은 자동으로 건너뜀. 설정 > GUIDE > "처음 안내 다시 보기"로 다시 열 수 있음
   - 처음부터 끝까지 실제 설치는 공기계에서 확인함 (17번)
   - 디버그 빌드에서만 WebView 개발자 도구가 켜짐 (`FLAG_DEBUGGABLE`). 배포판에는 영향 없음
   - 한글 줄바꿈을 단어 단위로 (`word-break: keep-all`)

15. **터묵스 받는 곳 안내 팝업** (2026-11-27)
   - 설치 안내 01 의 "F-Droid 에서 받기" / "GitHub 에서 받기" → 무엇을 받을지 알려 주는 팝업 + "이동하기"
   - F-Droid: 앱 이름 Termux · 설명 "Terminal emulator with packages" · Author: Tarek Sander · com.termux, Versions 의 **suggested** 버전 Download APK (공용 약 110MB)
   - GitHub: `/releases/latest`(정식판, v0.118.3) 로 바로 감. 앱이 `Build.SUPPORTED_ABIS[0]` 로 이 폰에 맞는 파일 이름을 보여 줌 (`termux-app_v…+github-debug_arm64-v8a.apk`, 모르면 universal)
   - 위 이름 · 크기 · 버전은 2025-05 릴리스 기준으로 확인한 것. 터묵스가 바뀌면 문구도 확인 필요
   - 공기계(갤럭시 A23, Android 14, 무선 디버깅)에서 처음 질문 → 설치 안내 → 두 팝업 확인
16. **버그: 설치 안내 02 "했어요"가 안 바뀜** (2026-11-27)
   - 첫 화면 WebView 는 DOM 저장소를 켜지 않아 `localStorage` 가 null → 저장이 늘 실패해 "아직"으로 되돌아갔음
   - 지금: 앱 설정(SharedPreferences `setup_done`)에 저장 (`Dream.setSetupDone`, cfg `setupDone`). 공기계에서 실제 탭으로 확인
   - 첫 화면(start.html)에서는 `localStorage` 를 쓰지 말 것
17. **설치가 터묵스를 직접 열어야만 시작되던 문제 + node 실행 오류** (2026-11-27, 공기계 A23 · Android 14)
   - 터묵스는 창(세션)을 만들어도 화면에 한 번 그려지기 전까지 명령을 시작하지 않음. Android 10+ 에서 터묵스가 뒤에서 스스로 화면을 열려면
     "다른 앱 위에 표시" 권한이 필요해서(터묵스 안내 토스트로 확인) 공기계에서는 화면이 안 열렸음 → 사용자가 열기 전까지 멈춰 있었음
     (S22 에서 되던 건 아마 그 권한이 있어서). 조용히 켜기(background)는 창이 없어 이 문제가 없음
   - 지금: 창(세션)으로 보내는 명령(설치, "터묵스 화면으로 이동" 켠 꿈속으로) 직후 **이 앱이 터묵스 화면을 직접 엶** (`showTermuxScreen`)
   - 새로 깐 터묵스에서 `pkg update` 만 하면 node 가 `CANNOT LINK EXECUTABLE "node": cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKmv"` 로 실행 안 됨
     (목록만 갱신하고 기본 라이브러리 libc++ 는 안 올림) → **`pkg upgrade`** 로 바꿈. 드림랜드 README 와 다른 점이라 README · 안내 화면에 적어 둠
   - 설치 스크립트 맨 앞에서 `termux-wake-lock` (배터리 최적화 해제 창이 처음에 뜨게)
   - 드림랜드가 포트를 열면 터묵스에서 `am start` 로 이 앱을 다시 앞으로 불러옴 (90초까지 1초마다 `/dev/tcp` 로 확인)
   - **공기계에서 처음부터 끝까지 확인:** 설치 누름 → 터묵스 저절로 열림 → pkg upgrade · 설치 · 실행 → 52초 뒤 4173 열림 → 앱이 앞으로 돌아와 드림랜드 표시, `onboarded=true`
     (git clone 은 앞선 시도에서 이미 받아 둬서 건너뜀. 완전히 새 폰이면 내려받기 시간이 더 걸림)
   - Android 14 이하는 상태 표시줄이 따로라 그 색도 드림랜드 맨 위 색에 맞춤 (`statusBarColor`)
18. **보안 점검 + 릴리스 v1.0.0** (2026-11-27)
   - 점검: 앱 기능 다리(`Dream`)는 첫 화면(앱 안 파일)에만, 첫 화면은 `file:///android_asset/` 밖으로 못 나감 / 터묵스 명령은 정해진 틀에 폴더(작은따옴표로 감쌈) · 포트(숫자 확인)만 넣음 /
     링크는 정해 둔 3곳만 (`GUIDE_URLS`) / 개발자 도구는 디버그 빌드만 (배포판 APK 에 debuggable 없음 확인) / 권한은 인터넷 · 터묵스 실행뿐
   - 고친 것: 드림랜드 WebView 의 `allowFileAccess` 를 끔
     - 이건 WebView 가 `file:///sdcard/...` 같은 **폰 경로를 주소로 직접 여는 것**만 막음. 드림랜드는 http 로만 열리고,
       파일 불러오기(png · json · zip)는 파일 선택 창이 주는 `content://` 를 쓰므로 영향 없음 (불러오기를 실제로 눌러 본 시험은 아직)
   - 그대로 둔 것: `allowBackup="true"`(앱 데이터가 구글 백업에 포함될 수 있음), `usesCleartextTraffic`(127.0.0.1 · 같은 와이파이의 http 접속에 필요)
   - 알고 쓰는 것: 터묵스 `allow-external-apps=true` 는 "터묵스 실행 권한"을 허락받은 앱이면 터묵스에 명령을 보낼 수 있게 함 (권한은 앱마다 따로 허락)

## 다음에 할 일 (후보)

- [ ] **파일 내보내기(다운로드) 지원** — 가장 먼저
  - 드림랜드는 백업 등을 `URL.createObjectURL(blob)` + `<a download>` 로 내보냄 (`src/datafolder/Panel.tsx` 등). 지금 앱은 `DownloadListener` 가 없어서 아무 일도 안 일어남
  - `blob:` 주소는 WebView 밖에서 못 받으므로, 페이지 안에서 blob 을 읽어(JS) 앱에 넘기고 저장(다운로드 폴더 또는 "저장할 곳 고르기")하는 방식이 필요
  - 이게 돼야 S22 의 드림랜드 데이터를 백업 → 디버그 앱 지우기 → 배포판 설치 → 복원 가능 (크롬으로 같은 주소를 열어도 저장 공간이 달라 앱의 데이터는 안 보임)
- [ ] 파일 불러오기(png · json · zip)를 폰에서 실제로 눌러 확인
- [ ] 드림랜드를 어두운 테마로 바꿨을 때 시스템 막대 색 확인
- [ ] 드림랜드 화면이 상태 표시줄 밑까지 그려짐 (Android 15 edge-to-edge). 위쪽 여백 처리 검토
- [ ] (선택) Gradle/AGP 를 올려서 Java 25 에서도 빌드되게 하기. 지금은 JDK 21 지정으로 충분
- [ ] 필요하면: 화면 회전 · 키보드 처리 점검, 당겨서 새로고침

끝난 것 (참고):
- [x] 터묵스로 깨우기 · 일어나기 · 처음 설치를 폰에서 확인 (S22, A23)
- [x] 앱 아이콘(연핑크 배경 `#FFE4EA` · 달 `#D4879C` · 반짝이 `#E6A3B4`, 벡터 적응형 + monochrome), 앱 이름 `Dreamland`. 홈 화면에서 확인함
- [x] Android Studio 로컬 빌드, Actions 배포 빌드 (v1.0.0)
- [x] 다른 사람에게 배포: 저장소가 공개라 Releases 에서 누구나 받을 수 있음

## 노트북(Windows) 개발 환경

- 프로젝트 위치: `C:\dev\webview-app` (Android Studio 로 GitHub 에서 Clone)
- Windows 사용자 이름이 한글이라 경로에 한글이 들어가지 않게 맞춤
  - 환경 변수 `GRADLE_USER_HOME=C:\gradle`
  - Android SDK 는 `C:\Android\Sdk` 권장 (설정 → Android SDK Location)
- Gradle JDK: **21** (Android Studio 기본 JDK 25 는 Gradle 8.10.2 와 안 맞음)
- Claude 데스크톱 앱에서 "이 컴퓨터 + webview-app 폴더"로 세션을 열어 이어서 작업. 워크트리는 끔
- 이전 대화(클라우드 세션 "Webview-app 연결 문제")의 내용은 이 파일에 모두 정리됨

## 알아 둘 것

- 삼성 **보안 폴더**에 있는 터묵스도 같은 폰의 `127.0.0.1` 을 씀. 그래서 보안 폴더의 실리태번(:8000)이 열렸던 것
- 드림랜드 서버는 `Host` 헤더가 `localhost` / `127.0.0.1` 일 때만 받음 (`server.js` `hostOk`). 다른 기기에서 접속하려면 드림랜드를 `--lan` 으로 켜야 함
- 드림랜드 데이터는 웹뷰 저장소에 저장되고, 주소(포트)별로 따로임. 주소를 바꾸면 다른 데이터로 보일 수 있음
- 디버그 APK 와 배포용 APK 는 서명이 달라서 서로 덮어 설치할 수 없음
- 빌드할 때 `aapt2 ... Failed to stat file ... android.jar: Illegal byte sequence` 경고가 뜨는데, 사용자 폴더 이름이 한글이라서임. 빌드는 정상 완료되므로 무시해도 됨
- `MainActivity.kt` 의 `databaseEnabled` 는 deprecated 경고만 뜸 (동작에는 문제 없음)

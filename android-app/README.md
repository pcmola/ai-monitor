# Code Spend — Android

Claude Code와 Codex 비용을 확인하는 독립형 Android 앱. PC 서버, LAN 연결, Azure 계정은 필요하지 않습니다. 기존 Python 모니터와 별도로 실행합니다.

[프로젝트 전체 안내](../README.md)

## 설치

빌드된 APK: `CodeSpend.apk` (동일 원본: `app/build/outputs/apk/debug/app-debug.apk`). 개인 설치용 디버그 서명이며 Play 스토어 배포용 서명은 아닙니다.

Android 8.0 이상 휴대폰에 APK를 전달해 설치합니다. 직접 빌드하려면 JDK 17, Android SDK 35가 필요합니다.

```powershell
cd F:\sandbox\ai-monitor
.\android-app\build-apk.ps1
# USB 디버깅으로 연결한 기기에 설치:
.\android-app\build-apk.ps1 -Install
```

Android Studio에서는 이 폴더를 열고 실행할 수 있습니다. Gradle Wrapper 8.9와 Android Gradle Plugin 8.7.3을 사용합니다.

## 에뮬레이터에서 실행

이 PC에는 Android Emulator와 Google Play 포함 Android 15(API 35) 이미지가 설치되어 있습니다. 다음 명령으로 가상 휴대폰을 열고 APK를 설치·실행합니다.

```powershell
cd F:\sandbox\ai-monitor
.\android-app\run-emulator.ps1
# 소스를 수정한 뒤 다시 빌드·실행:
.\android-app\run-emulator.ps1 -Rebuild
# 실행 중인 프로젝트 에뮬레이터의 기본 UI 자동 검사:
.\android-app\test-emulator.ps1
```

첫 부팅은 시간이 걸릴 수 있습니다. 스크립트가 부팅 완료를 기다린 뒤 앱을 실행합니다. 이미 열려 있는 프로젝트 에뮬레이터는 재사용하며, `-Rebuild`를 생략하면 기존 APK를 사용합니다(APK가 없으면 빌드).

가상기기 이름은 `CodeSpend_API35`, 기본 기기 번호는 `emulator-5554`입니다. 데이터는 `%LOCALAPPDATA%\CodeSpend\avd`에 유지합니다. 별도 경로는 `CODE_SPEND_AVD_HOME` 환경변수로 지정할 수 있습니다. CPU는 WHPX 가속, 그래픽은 이 PC의 OpenGL 호환성을 위해 소프트웨어 렌더링을 사용합니다. 그래픽 소프트웨어 렌더링은 CPU 가속을 끄는 `-Software` 옵션과 다릅니다.

실행 창을 닫으면 에뮬레이터가 종료됩니다. APK와 로그인 데이터는 가상기기에 남습니다. 공식 계정 로그인은 에뮬레이터의 Chrome에서 직접 진행하세요. 자동 검사는 실제 키나 비밀번호를 입력하지 않습니다.

### 다른 PC에서 준비할 것

설치된 도구와 가상기기는 Git에 포함되지 않으며, 실행 스크립트가 SDK를 자동 다운로드하지는 않습니다. 다른 PC에서는 다음을 먼저 준비해야 합니다.

- JDK 17 및 Android SDK 위치를 가리키는 `ANDROID_HOME`
- SDK 패키지: `platform-tools`, `emulator`, `cmdline-tools;latest`, `platforms;android-35`, `build-tools;34.0.0`
- 가상기기 이미지: `system-images;android-35;google_apis_playstore;x86_64`
- Windows에서 BIOS/UEFI 가상화와 Windows Hypervisor Platform(WHPX)을 사용할 수 있는 환경

현재 스크립트의 가상기기 이미지는 Windows x86_64 PC용입니다. 처음 빌드할 때는 Gradle과 빌드 의존성을 내려받을 인터넷 연결도 필요합니다.

### 실행 오류 확인

| 증상 | 확인할 내용 |
|---|---|
| `still starting` / `offline` | 기존 에뮬레이터의 부팅을 기다린 뒤 다시 실행합니다. |
| `Hardware acceleration is unavailable` | Windows 가상화 기능을 확인합니다. 기능을 방금 켰다면 재부팅이 필요할 수 있습니다. 스크립트는 PC를 재부팅하지 않습니다. |
| OpenGL 오류 / 창이 바로 종료됨 | 기본 소프트웨어 그래픽 설정으로 실행하고 `android-app/.emulator/emulator.stdout.log`, `emulator.stderr.log`를 확인합니다. |
| 기본 포트를 다른 기기가 사용 중 | `run-emulator.ps1 -Port 5556`으로 실행하고, UI 검사는 `test-emulator.ps1 -Device emulator-5556`으로 실행합니다. |
| PowerShell에서 스크립트 실행이 차단됨 | 신뢰하는 프로젝트 코드인지 확인한 뒤 `powershell -NoProfile -ExecutionPolicy Bypass -File .\android-app\run-emulator.ps1`로 해당 실행에만 적용합니다. |

위 경로와 명령은 프로젝트 루트(`F:\sandbox\ai-monitor`) 기준입니다. 이 PC의 실행 로그와 UI 검사 자료는 `android-app/.emulator/`에 저장되며 Git에서 제외됩니다.

## 구독 계정

Claude Code Pro·Max, Codex ChatGPT 구독 사용자는 기본 화면의 **사용량·크레딧 보기** 또는 **구독·결제 보기**를 누르고 공식 사이트에 로그인합니다. 공식 브라우저 탭의 세션을 사용하므로 Google/SSO 로그인도 브라우저가 처리합니다. Codex의 구독·결제 버튼은 ChatGPT 설정을 엽니다.

- Claude: <https://claude.ai/settings/usage>
- Codex: <https://chatgpt.com/codex/settings/usage>

이 모드는 공식 계정 페이지를 여는 기능입니다. 개인 구독의 실시간 금액·한도 수치를 네이티브 카드로 자동 수집하지 않습니다. 앱은 구독 한도 %나 토큰의 추정 가치를 실제 청구액으로 환산하지 않습니다. 공식 페이지에서 확인할 수 있는 항목은 계정·플랜에 따라 달라집니다.

## API 청구 비용 직접 조회

**API 비용** 탭에서 각 서비스의 **API 연결**을 누릅니다.

| 서비스 | 필요한 인증 | 비용 구분 |
|---|---|---|
| Claude Code | Anthropic 관리자 API 키 | Claude Code만 사용하는 워크스페이스 ID |
| Codex | OpenAI 관리자 API 키 | Codex만 사용하는 프로젝트 ID |

일반 추론용 키나 구독 로그인만으로 조직 비용 API를 사용할 수 있는 것은 아닙니다. 관리자 키와 보고서 조회 권한이 필요합니다. 앱에는 계정마다 한 개의 전용 공간을 지정합니다. 여러 도구가 같은 공간을 사용하면 그 비용이 섞이므로, 전용 공간이라는 확인 체크 후 연결합니다.

- 최근 7일 / 30일, 합계, 일별 차트와 내역
- USD / UTC 기준. 오늘은 부분 집계이며 제공자가 과거 비용을 수정할 수 있습니다.
- Claude의 비용 API는 센트 단위 문자열이므로 USD로 변환합니다.
- OpenAI는 프로젝트 필터로 요청하며, Claude는 워크스페이스로 그룹화한 응답에서 지정 ID만 선택합니다.
- 다음 페이지까지 전부 조회하고, 중간 실패 시 부분 합계를 완료값으로 표시하지 않습니다.
- 키는 Android Keystore AES-GCM으로 암호화하여 기기에 저장하며 백업에서 제외합니다.
- 비용 API로 GET 요청만 보냅니다. 추론이나 결제를 실행하지 않습니다.

구독료·추가 크레딧 결제와 API 청구는 다른 데이터입니다. API 합계에는 개인 구독료가 들어가지 않습니다. 실계정 검증에는 사용자 소유의 관리자 키가 필요하며, 키를 소스나 채팅에 붙여넣지 말고 설치된 앱에서 입력하세요.

## 공식 근거

- [OpenAI Codex 구독·크레딧](https://developers.openai.com/codex/pricing/)
- [OpenAI 조직 비용 API](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs)
- [Anthropic Usage and Cost API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api)
- [Claude Code 비용과 구독 사용량 구분](https://code.claude.com/docs/en/costs)

## 테스트 범위

- `build-apk.ps1`: 비용 단위, 전용 공간 필터, 페이지네이션, 부분 실패, UTC 기간, 빈 응답, 통화 검증의 단위 테스트 11개와 Android Lint, APK 빌드를 실행합니다.
- `test-emulator.ps1`: 탭 전환, 미연결 상태, 기간 저장, 빈 키 입력 검증, 재실행 시 탭 복원, Claude 공식 페이지 연결과 앱 크래시 기록을 검사합니다. 테스트 중 탭·조회 기간 설정이 변경될 수 있습니다.
- 실제 계정 로그인과 실금액 대조는 자동 검사에 포함되지 않습니다. 계정 인증은 사용자가 직접 진행해야 합니다.

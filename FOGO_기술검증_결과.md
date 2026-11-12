# FOGO 기술 검증 결과

작성: 2026-11-10 · 2차 갱신: 2026-11-10 · 3차 갱신(우리 실측 반영): 2026-11-11 · 기술 조사·QA 담당 · 기준: FOGO 기획서 v3 21쪽 '기술 검증' 항목, Pleos 개발자 문서(document.pleos.ai), 공식 샘플 저장소, 같은 에뮬레이터(Pleos Connect v2.1.0)로 실측한 다른 공모전 팀의 공개 저장소, 우리 실측 앱 FogoProbe(원본 로그 `probe_결과.txt`)

판정 기호: **문서** = 공식 문서·샘플로 확인 / **타팀 실측** = 다른 팀이 v2.1.0 에뮬레이터에서 확인 / **우리 실측** = 우리 에뮬레이터에서 FogoProbe로 확인(2026-11-11) / ☐ = 아직 실측 필요

## 0. 3차 갱신에서 바뀐 결론 (팀장 공유용)

1. **시동 후 기록은 자동으로 시작할 수 있다. 화면은 못 띄운다.** 부팅 신호(`BOOT_COMPLETED`)를 받아 foreground service를 띄우면 위치 기록이 바로 시작된다. 앱 화면(Activity)을 띄우는 것은 시스템이 막는다(`BAL_BLOCK`). → 기획서 13쪽 '앱을 열지 않은 주행은 기록되지 않는다'를 고칠 수 있다. 단 에뮬레이터 재부팅을 시동으로 본 결과라 실차 확인이 필요하다(우리 실측).
2. **백그라운드 기록은 foreground service가 필요하다.** 서비스가 있으면 내비·설정 화면이 앞에 있어도 위치가 계속 온다. 서비스 없이 화면에서만 받으면 화면이 내려가고 약 5초 뒤 끊기고, 돌아올 때 밀린 좌표가 한꺼번에 들어온다(우리 실측).
3. **내비 목적지 좌표는 실제 값으로 온다.** 내비 화면에서 직접 고른 목적지도, 앱이 경로 요청으로 건 목적지도 `getDestinationInfo`에 실제 위경도가 온다. 내비 목적지를 '목적지 미리보기'에 쓸 수 있다. 단 목적지가 바뀌어도 콜백이 오지 않아 앱이 직접 읽어야 한다(우리 실측).
4. **앱 자체 도착 판정이 동작한다.** 목적지 반경 300m 안 + 기어 P에서 바로 도착 판정 → 앰비언트 보라색 변경까지 확인. 내비 `onDestinationArrived`는 오지 않았다(우리 실측, 2차 갱신 결론과 같음).
5. **주행 중 화면 가리기는 앱이 직접 해야 한다.** 기어 D에서 화면 제한 신호(`CarUxRestrictions` DO=true)는 오지만, 에뮬레이터는 `distractionOptimized` 선언이 없는 앱도 막지 않는다. 2차 PT 시연에서 'D단에서는 기록 화면만'을 보여 주려면 앱이 신호를 받아 P단 전용 화면을 가려야 한다(우리 실측).
6. **Pleos 위치 권한은 한 번 '다시 묻지 않음'으로 거부되면 되돌릴 길이 없다.** 위치 수신에 필요한 건 Pleos `FUSED_LOCATION` 하나인데, 차량 설정의 앱 권한 화면에 이 항목이 없다. 요청 전 안내 화면과 '기록 없이 둘러보기' 흐름이 필요하다(우리 실측).
7. **에뮬레이터는 `-gpu host`로 켠다.** `-gpu swiftshader_indirect`로 켜면 기어 D에서 속도가 붙은 뒤 수십 초 안에 에뮬레이터가 죽는다(5회 재현). `-gpu host`에서는 2분 주행 시연이 끝까지 된다. 시연 PC 설정에 꼭 반영(우리 실측).

2차 갱신의 결론(위치 재생 가능, 내비 도착 이벤트 없음, 주행 규제 ADB 미동작(대신 Virtual Console 기어로 제한 신호가 바뀜), 위치 권한 런타임 요청, Google Play 서비스 없음)은 그대로다. 이 중 위치 재생·내비 도착 이벤트·위치 권한은 우리 실측으로도 확인했다.

### 팀이 정할 것

1. 시동 후 서비스가 기록을 자동 시작하도록 기획서 13쪽 '기록 공백 대응'을 고칠지 (결론 1)
2. 위치 권한 요청 전 안내 화면과, 거부한 사용자를 위한 '기록 없이 둘러보기' 흐름을 넣을지 (결론 6)
3. 내비에서 정한 목적지도 '목적지 미리보기'에 넣을지 (결론 3)

### 주최 측에 문의할 것

- Pleos 계정·프로필로 운전자를 식별할 수 있는지 (기록 이어가기, 1순위)
- 실차에서만 확인할 수 있는 것: 시동 = 부팅인지, 주행 중 앱 차단이 되는지, 설정에 Pleos 위치 권한 항목이 있는지, 외기·현재 온도가 바뀌는지

## 1. 결과 요약

| 기획서 검증 항목 | 결과 | 근거 | 남은 실측 | 기획서 반영 |
| --- | --- | --- | --- | --- |
| Pleos 계정·프로필로 운전자 식별 (1순위) | API 없음 (문서) | Vehicle·NaviHelper·FusedLocation·TTS·STT에 사용자·계정 API 없음 | 해당 없음 | 주최 측 문의. 안 되면 자체 계정 + 폰 QR 로그인 |
| 백그라운드 위치 기록, 시동 후 자동 실행 (1순위) | **기록 ○, 화면 자동 실행 ×** (우리 실측) | foreground service(유형 location)로 설정·내비 화면이 앞에 있어도 위치 계속 수신. 부팅 시 `BOOT_COMPLETED` 수신(사용자 10, 부팅 완료 약 70초 뒤) → 서비스 시작 성공(유형 specialUse) → 1~2초 뒤 위치 수신. Activity 실행은 `Background activity launch blocked … (BAL_BLOCK)`. 6번 부팅 모두 같음 | 실차에서 시동 = 부팅인지 | 13쪽 '기록 공백 대응' 수정: 시동 후 서비스가 기록을 자동 시작, 화면은 운전자가 열 때. 기록 중 상단 알림 표시 |
| 에뮬레이터 GPX 경로 재생 | **가능** (우리 실측) | `fogo_drive.py gangneung-local --minutes 2`로 120점 재생, FusedLocation `onChanged` 약 1초 간격, 지연 0.0초 | 해당 없음 | 14쪽 시연 계획: Extended Controls GPX 대신 스크립트 사용 |
| NaviHelper 목적지 좌표 | **실제 좌표로 옴** (우리 실측) | 내비 화면에서 고른 목적지: `latitude=36.10911111, longitude=128.4173, name=삼성전자 구미2사업장, remainDistance=230000`. 앱이 `requestRoute`로 건 목적지: `37.771, 128.948`. 목적지가 없을 때만 0.0. 새 목적지 설정 시 `onDestinationChanged`·`onDestinationInfo` 콜백 없음(약 2분 관찰). 안내 종료 시에는 요청 없이 `DestinationInfo(0.0, 0.0)` 콜백이 옴 | 해당 없음 | 내비에서 정한 목적지도 미리보기에 사용. 앱이 화면에 돌아올 때(`onStart`)마다 `getDestinationInfo`로 다시 읽기 |
| 도착 이벤트 `onDestinationArrived` | **에뮬레이터에서 안 옴** (우리 실측·타팀 실측 2곳) | 내비 안내 중 도착점 도착 + 기어 P 후 약 40초 관찰, 콜백 없음. 내비 지도 위치는 geo fix를 따라오지 않음(도착 후에도 남은 거리 200km 그대로) | 해당 없음 | 앱 자체 판정: 목적지 반경 300m 안 + 기어 P. 우리 실측에서 기어 P 수신과 같은 순간 판정 성공 |
| 앰비언트 라이트 제어·표시 | **가능** (우리 실측) | 접근 READ_WRITE, 영역 1개(1·2열 전체). `setAmbientLightBrightnessColor`로 보라 #AA00FF·파랑 #2979FF·청록 #00BCD4·주황 #FF6D00·흰색 #FFFFFF 모두 쓰기 성공, 다시 읽은 값과 Virtual Console 값 일치. Virtual Console 세션이 끊긴 상태에서는 '쓰기 완료'가 와도 값이 안 바뀜 | 해당 없음 | 색은 Cyan·Blue·Purple·White 4가지로 맞출지 결정. 시연 중 Virtual Console 세션 유지(`fogo_vc.py`가 대신 보냄) |
| CarInfo로 그랜저·아반떼 구분 | **불가** (문서·우리 실측) | CarInfo는 `getVINInfo`, `getCRNInfo`만. 에뮬레이터 `getVINInfo` = `"invalid"` | 해당 없음 | 앱 내 차종 선택·디버그 설정으로 대체 |
| Door 동승석 상태 | **가능** (우리 실측) | 지원 영역 6개(ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, REAR, HOOD). `registerDoorPosition` 콜백으로 동승석(ROW_1_RIGHT) 열림 1, 닫힘 0 수신. Virtual Console `door fr` 조작으로 바뀜 | 해당 없음 | 그대로 사용 |
| HVAC 외기온도 | Pleos HVAC에는 없음 (문서). **AAOS로는 읽힘** (우리 실측) | AAOS `ENV_OUTSIDE_TEMPERATURE`(권한 `android.car.permission.CAR_EXTERIOR_ENVIRONMENT`) = 25.0 °C. Virtual Console에 외기온도 카드가 없어 값을 바꿀 수 없음. Pleos `getHvacTemperatureCurrent`(권한 `pleos.car.permission.CAR_CLIMATE`, 접근 READ, 영역 운전석·동승석·전체) = 19.1 °C. Virtual Console 설정 온도를 22→운전석 26·동승석 18→22로 바꾸는 동안 `getHvacTemperature`(설정 온도)는 그대로 따라왔지만 현재 온도는 19.1 고정. 외기온도와도 달라 실내 센서 값으로 보이나 에뮬레이터에서는 바뀌지 않음. AAOS `HVAC_TEMPERATURE_CURRENT`는 앱에 권한이 안 줘져(`CONTROL_CAR_CLIMATE` 거부) 읽을 수 없음 | ☐ 실차에서 외기·현재 온도가 바뀌는지 | 시연에서는 25.0 고정이라 날씨 연출은 와이퍼 상태 또는 외부 날씨 API |
| 주행 중 화면 제한 | **제한 신호는 옴, 앱 차단은 안 함** (우리 실측) | Virtual Console 기어로 `CarUxRestrictions`가 바뀜: P → DO=false, 0x0, 주행 상태 0(parked). D 속도 0 → DO=true, 0x50(NO_VIDEO, NO_SETUP), 상태 1(idling). D 30km/h → 0x50, 상태 2(moving). 하지만 30km/h 주행 중에도 `distractionOptimized` 선언이 없는 앱(같은 앱에서 선언만 뺀 사본, 달력, 갤러리)이 그대로 열리고 차단 기록도 없음. 시스템 차단 설정은 켜져 있음(`mEnableActivityBlocking:true`, 'Display 0 is restricted') | ☐ 실차에서 차단되는지 | 에뮬레이터는 차단해 주지 않으므로 **앱이 직접** `CarUxRestrictionsManager`를 구독해 DO=true면 P단 전용 화면을 가린다. 시연은 Virtual Console 기어로. 실차 대비 `distractionOptimized`는 기록 화면에만 선언 |
| 관광정보 API 키·약관 | 미확인 | Pleos 문서 범위 밖 | 해당 없음 | MVP는 3개 지역 목업 JSON |
| 권한 등급 | 확인 (문서·우리 실측) | 조회 `CAR_*`, 제어 `CONTROL_CAR_*`, 내비 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`, AAOS `CAR_POWERTRAIN`·`CAR_EXTERIOR_ENVIRONMENT`는 설치만으로 부여. 런타임 요청 창은 3개가 차례로 뜸: 위치('앱 사용 중에만 허용'), 알림, '알 수 없는 작업 실행'(Pleos `FUSED_LOCATION`. 이 창 이름이 사용자에게 어색하므로 안내 화면에서 미리 설명). **거부 시(우리 실측)**: FusedLocation은 Pleos `FUSED_LOCATION`만 있으면 동작하고 Android 위치 권한은 필요 없음. `FUSED_LOCATION`이 없으면 `registerFusedLocationCallback`에서 `SecurityException: need Permission pleos.car.permission.FUSED_LOCATION`(onError가 아니라 예외, `isSupported()`는 true). 두 번째 요청부터 버튼이 '허용하지 않고 다시 묻지 않음'으로 바뀌고, 이걸 누르면 이후 요청 창이 안 뜨고 바로 거부. 차량 설정 > 앱 > 권한에는 '위치'·'알림' 스위치만 있고, '위치'를 켜도 `FUSED_LOCATION`은 꺼진 채 → 사용자가 되살릴 화면이 없음 | ☐ 실차 설정에 Pleos 위치 항목이 있는지 | 위치 권한 요청 전 '무엇을 왜 쓰는지' 안내 화면 추가. 첫 거부 뒤 다시 요청할 때는 '한 번 더 거부하면 되돌릴 수 없음'을 먼저 알림. 거부 상태면 '기록 없이 둘러보기'로 진행. 위치 수신 실패는 예외로 잡기 |

## 2. 추가로 확인된 것

위치·내비
- FusedLocation 최신 버전은 **1.1.3**(데이터 모듈 `fused-location-data` 0.0.3 함께 추가). 패키지 `ai.pleos.playground.location.fused`. `isSupported()`로 지원 여부 확인 후 `initialize()` → `registerFusedLocationCallback(listener)`
- `LocationData`에 latitude, longitude, timestampMillis가 있고 **속도 값은 없다**(타팀은 위치 변화로 계산). FOGO는 속도를 안 쓰므로 영향 없음
- `onError`는 예외 메시지로 오류 코드를 준다. 예: `800002` = 차량 개인정보 설정에서 위치가 꺼짐. 문제가 이어지는 동안 약 1초마다 반복됨
- 앱이 멈춰 있던 동안의 좌표가 한꺼번에 들어올 수 있어 `timestampMillis`로 판단(타팀 메모). **우리 실측에서도 확인**: 화면 복귀 순간 지연 27.6초·16.6초·4.6초짜리 좌표가 한꺼번에 들어옴
- NaviHelper는 `NaviHelper(context).initialize(onConnected, onFailed)` → `onConnected` 안에서 `addListener`. 요청 API는 반환값 없이 콜백으로 결과가 온다
- `getDestinationInfo` 결과는 약 1초 뒤 `onDestinationInfo` 콜백으로 온다. 앱이 경로를 요청하면 내비 화면이 앞으로 나온다(앱은 `onStop`)
- 에뮬레이터 내비 서비스(3.0.4)는 경로 탐색 완료 시 `onRoutingCompleted` 대신 예전 `onRouteEnded`를 보낸다 → 둘 다 처리
- NaviHelper는 경로 좌표열(polyline)을 주지 않는다. 내비 지도를 앱 안에 넣는 API도 없다
- `requestTrip(TripRequest)`로 안내를 시작하지 않고 예상 거리·시간 조회 가능 → '목적지 미리보기'에 활용 가능

화면·플랫폼
- 서드파티 앱 알림은 알림 센터에는 오지만 Heads-up(화면 상단 팝업)은 안 뜬다(타팀 실측). 도착 반응을 알림에 의존하지 말 것
- 분할 화면에서 앱 창을 닫으면 Pleos가 앱 프로세스를 종료한다 → 기록 상태는 매번 기기에 저장
- `launchMode` 기본값이면 런처 아이콘으로 열 때마다 화면이 쌓임 → `singleTask` 권장
- `screenOrientation="portrait"` 지정 시 레터박스(호환 모드)로 떠서 심사 기준 위반 소지
- 부팅 직후 서비스는 백그라운드 시작이라 foreground service 유형을 specialUse로 시작했다(우리 실측 성공). location 유형으로 시작되는지는 확인하지 않았다

차량 값
- 기어: Vehicle SDK 2.3.2에 `getGear().registerGearSelection(GearSelectionListener)` → `onSelected(VehicleGear)`, `getCurrentGearSelectionState`가 있다(권한 `pleos.car.permission.CAR_DRIVE`). 공식 문서와 샘플 앱 카탈로그에는 없고, SDK에서 deprecated("use the Google GEAR_SELECTION property")지만 에뮬레이터에서 GEAR_PARK 수신 확인(타팀 실측 3곳 이상). → **기본은 AAOS `CarPropertyManager` `GEAR_SELECTION`(권한 `android.car.permission.CAR_POWERTRAIN`, 값 P=4·N=1·R=2·D=8), 실패하면 Pleos `getGear()`로 대체**. 우리 실측에서 AAOS로 PARK·DRIVE 수신, 대체 경로는 쓰이지 않음
- Virtual Console REST로 기어를 D로 바꿀 때는 브레이크를 먼저 밟아야 앱에 GEAR_DRIVE가 온다(`PUT /vehicle/controls?brake=100&speed=0&accelerator=0` 후 D, 타팀 실측)
- 누적 주행거리: Virtual Console Odometer 카드로 '차 생일' 시연 가능. 초기값 0.0
- 안전벨트 API 있음. Virtual Console에 벨트 카드는 없음
- Virtual Console에 없는 값: 위치, 외기온도, 시트 위치, VIN. 차량 속도는 REST(`PUT /vehicle/controls?speed=60`)로 넣을 수 있지만 2초마다 다시 보내야 유지된다

환경
- 그래픽카드: 공식 최소 RTX 3060 Laptop 6GB급이지만, 타팀은 Intel 내장 GPU(Windows 11)에서도 약 90초에 부팅·동작 확인
- **에뮬레이터 실행 옵션(우리 실측)**: `emulator -avd Pleos_Connect_v2.1.0 -gpu host -no-snapshot`
  - `-gpu swiftshader_indirect`: 기어 D + 속도 유지 시 에뮬레이터가 죽음(qemu 접근 위반 0xc0000005, 직전 `Failed to find ColorBuffer`). 60km/h 약 10~27초, 20km/h 38초. 속도 0인 기어 D는 문제없음
  - `-wipe-data`: 켤 때마다 설치한 앱과 권한이 지워진다. 부팅 자동 실행(③)을 보려면 빼야 한다
- `adb emu geo fix`가 `KO: missing authentication token`으로 실패하면 `%USERPROFILE%\.emulator_console_auth_token`이 깨진 것. 에뮬레이터를 끄고 이 파일을 지운 뒤 다시 켜면 된다(우리 PC에서 실제로 발생). `adb shell cmd location` 테스트 공급자는 MOCK_LOCATION 권한 거부로 대안이 안 된다
- `adb reboot`은 에뮬레이터를 멈추게 할 수 있다(우리 PC에서 9분 넘게 응답 없음). 재시작은 에뮬레이터를 끄고 다시 켠다
- 빌드: Pleos Vehicle SDK 2.3.2가 AndroidX(hilt-android, core-ktx)를 끌어오므로 `gradle.properties`에 `android.useAndroidX=true` 필요. 없으면 `checkDebugAarMetadata`에서 실패
- AVD Boot option은 반드시 Cold boot. 메모리 4GB 권장(타팀)
- Gleo Interface, LLM SDK는 공모전 허용 목록에 없음(팀장 확인 완료 사항은 CLAUDE.md 기준)
- 공식 Vehicle SDK 샘플 앱(`Pleos-Playground/pleos-sample-vehicle-app`, SDK 2.3.2)은 19개 도메인 API를 화면에서 바로 읽고 쓸 수 있다. 이번 실측은 이 앱 대신 FogoProbe로 했다

## 3. 위치 재생 스크립트 `fogo_drive.py`, 시연 스크립트 `fogo_vc.py`

윈도우(강의장)·맥북(집) 공통. Python 3.8 이상, 추가 설치 없음. adb는 Android SDK에서 자동으로 찾는다.

```
python fogo_drive.py --list                       # 내장 경로: seoul-gangneung, seoul-busan, seoul-local, gangneung-local, busan-local
python fogo_drive.py seoul-gangneung --minutes 5  # 서울→강릉 188km를 5분에 (1초마다 약 630m)
python fogo_drive.py gangneung-local --kmh 60     # 강릉 시내 10km를 실제 속도로
python fogo_drive.py my_route.gpx --minutes 3     # 실제 도로 GPX 파일
python fogo_drive.py --fix 37.7519 128.8761       # 한 점으로 순간이동
python fogo_vc.py demo gangneung-local --minutes 2 --no-wait   # 기어 P → D(60km/h) → 경로 재생 → 도착 후 기어 P
```

- 내장 경로는 고속도로 주요 지점을 직선으로 이은 근사 경로(시연용). 실제 도로를 따라가야 하면 GPX를 쓴다
- 1초당 이동 거리가 1km를 넘으면 블록이 건너뛰어진다. 서울→강릉은 3분 이상(--minutes 3)으로 재생
- 시연 순서: 스크립트로 출발점 `--fix` → 앱 실행 → Virtual Console 기어 D → 스크립트 주행 → 끝나면 기어 P → 도착 반응. `fogo_vc.py demo`가 이 순서를 한 번에 돌린다(우리 실측, `-gpu host`)

## 4. 에뮬레이터 실측 결과 (2026-11-11)

환경: Pleos Connect Emulator v2.1.0, 실측 앱 FogoProbe(`FogoProbe/`), 원본 로그 `probe_결과.txt`. 표의 번호는 이 문서의 번호이고, 괄호 안은 FogoProbe 버튼 번호다.

| # | 항목 | 결과 | 실측 내용 |
| --- | --- | --- | --- |
| ① | 위치 수신 재확인 | ✅ | 2분 재생 동안 약 1초 간격 120개 좌표 수신, 지연 0.0초 (FogoProbe ①) |
| ② | 백그라운드 지속 | ✅ (a) 끊김 (b) 계속 | (a) Activity에서만: 화면이 내려가고 약 5초 뒤 수신이 멈춤, 복귀 시 밀린 좌표가 한꺼번에 들어옴 (b) foreground service: 설정·내비 화면이 앞에 있는 동안 계속 수신. 서비스가 있으면 Activity 쪽 구독도 끊기지 않음 (FogoProbe ②) |
| ③ | 시동 후 자동 실행 | ✅ 서비스 ○, 화면 × | `BOOT_COMPLETED` 수신 → 서비스 시작 → 위치 기록. 앱 화면은 `BAL_BLOCK`으로 안 뜸 (FogoProbe ③) |
| ④ | 도착 판정 | ✅ | 강릉 안목 목적지 + `fogo_vc.py demo` 주행 → 기어 P 수신과 같은 순간 '도착 판정 성공, 거리 0m' → 앰비언트 보라 반영. 내비 `onDestinationArrived` 없음 (FogoProbe ④) |
| ⑤ | 목적지 좌표 | ✅ 실제 좌표 | 내비에서 직접 고른 목적지 36.10911111, 128.4173. 0.0이 아니므로 미리보기 대상에 포함. 목적지 변경 콜백은 안 옴 (FogoProbe ⑧) |
| ⑥ | 앰비언트 | ✅ | 5색 모두 Virtual Console에 반영 (FogoProbe ⑤, 샘플 앱 대신) |
| ⑦ | VIN·HVAC | ✅ | VIN `"invalid"`. 외기온도는 AAOS로 25.0 °C. HVAC 현재 온도 19.1 °C로 고정(설정 온도를 바꿔도, 시간이 지나도 그대로) → 실내 온도로 보이지만 시연에서 바뀌는 값으로 쓸 수 없음 (FogoProbe ⑦, '⑦ HVAC 온도' 버튼) |
| ⑧ | 주행 제한 | ✅ 신호 ○, 차단 × | 기어 D에서 `CarUxRestrictions` DO=true·0x50, P에서 DO=false·0x0(`dumpsys car_service`로 확인). 30km/h 주행 중에도 `distractionOptimized` 없는 앱이 막히지 않음 → P단 전용 화면은 앱이 직접 가려야 함 |
| 추가 | 동승석 문 | ✅ | 열림 1, 닫힘 0 (FogoProbe ⑥) |
| 추가 | 위치 권한 거부 | ✅ | Pleos `FUSED_LOCATION` 없으면 화면·서비스 모두 위치 시작 시 `SecurityException`, 받은 좌표 0. Android 위치만 없고 `FUSED_LOCATION`이 있으면 정상 수신(이때 foreground service 유형은 location 대신 specialUse로 시작). '다시 묻지 않음' 뒤에는 설정에서도 `FUSED_LOCATION`을 되살릴 수 없음 |

에뮬레이터 실측은 모두 끝났다. 실차에서 확인할 것: 시동 = 부팅인지, 주행 중 앱 차단이 되는지, 설정에 Pleos 위치 권한 항목이 있는지, 외기·현재 온도가 바뀌는지. 실차에서 시동 = 부팅인지, 실차에서 주행 중 앱 차단이 되는지.

## 5. 출처

공식
- [Pleos Connect SDK 개요](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/)
- [Vehicle SDK 소개](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/intro) · [프로젝트 설정](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/project-setup)
- [FusedLocation 프로젝트 설정](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/FusedLocation/project-setup) · [첫 API 호출](https://document.pleos.ai/api-reference/connect-sdk-pleos/FusedLocation/make-the-first-api-call)
- [NaviHelper 소개](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro)
- [공식 Vehicle SDK 샘플 앱](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)
- [Pleos Connect Virtual Console 사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)

우리 실측
- `FogoProbe/probe_결과.txt`: FogoProbe 앱 실측 요약과 원본 Logcat(태그 `FOGO_PROBE`), 2026-11-11

다른 공모전 팀의 실측 기록(같은 에뮬레이터 v2.1.0)
- [PairCar — Pleos 구현 가능성 검증](https://github.com/hyundai-full-accel/paircar-vehicle/blob/main/docs/PLEOS_FEASIBILITY.md): NaviHelper 22개 메서드 전수 점검, 내비 위치 고정, 주행 규제 ADB 미동작, MapLibre 지도, Heads-up 미표시
- [Docentrip](https://github.com/WhoisHOO/pleos-app-challenge): FusedLocation 1.1.3 + `adb emu geo fix` 주행 스크립트, 런타임 위치 권한, 내비 NMEA 체크섬 오류
- [Roadio DEV-02](https://github.com/joooon28/Roadio/blob/main/DEV-02-validation.md): FusedLocation 콜백이 geo fix 좌표를 받음
- [HanGil](https://github.com/HanGilLab/Android): foreground service 차량 기록
- 기어 API: [대동차지도 CarDriveStateSources.kt](https://github.com/geunbonGit/daedongchajido/blob/main/app/src/main/java/com/odomap/app/drive/CarDriveStateSources.kt) · [convoy sdk-findings](https://github.com/SeungEEE/convoy/blob/main/docs/sdk-findings.md) · [같이가개 tech_notes](https://github.com/xuxtaku7610-del/every-moment-of-pause/blob/main/docs/tech_notes.md) · [storydrive SDK_NOTES](https://github.com/ZestfulPulse/storydrive/blob/main/docs/SDK_NOTES.md)

# 구르는 가이드 (Rolling Guide)

**버튼 하나면 차가 통째로 여행 어드벤처가 됩니다.**

차의 조명과 소리, 화면, 내비게이션이 함께 움직여 지나는 곳마다 이야기를 들려주고, 그날 하루를 기록으로 남겨 주는 Pleos Connect 여행 가이드 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

| 출발 전 좌석 점호 | 달리는 동안 (소리로 안내) | 차가 서면 명소 장면과 QR |
|---|---|---|
| ![좌석 점호](docs/images/rollcall.png) | ![주행 중](docs/images/driving.png) | ![정차 중](docs/images/parked.png) |

| 휴대폰 그룹 페이지 | 휴대폰에서 고른 장소가 차 내비게이션으로 | 하루 기록 카드 |
|---|---|---|
| ![휴대폰](docs/images/phone_now.png) | ![장소 고르기](docs/images/pick.png) | ![기록](docs/images/record.png) |

## APK

- 파일: [apk/개인참가_구르는가이드_release.apk](apk/)
- 대상: Pleos Connect 에뮬레이터 (Android 14, API 34, Car 이미지). minSdk 28

## 무엇을 하나요

[여행 시작]을 누르면 아래가 한꺼번에 움직입니다.

1. **내비게이션**: 오늘 목적지와 경유지 두 곳을 순정 내비게이션에 넣습니다 (NaviHelper).
2. **좌석 점호**: 앉은 자리가 모두 벨트를 매면 출발 안내를 시작합니다 (Vehicle 좌석, 벨트).
3. **도시 오프닝과 명소 안내**: 여행지와 명소마다 영역을 정해 두고 차가 들어서는 순간 실내 조명 색이 바뀌고 이야기가 시작됩니다. "잠시 후 오른쪽에 첨성대가 보여요"처럼 어느 쪽에 보일지도 먼저 알려 줍니다 (FusedLocation 근접 알림, Vehicle 실내 조명, TextToSpeech).
4. **길 안내와 겹치지 않게**: 다음 길 안내까지 남은 거리를 보고 이야기 길이(짧게, 보통, 길게)를 고르고, 안내 지점이 가까우면 잠시 쉬었다가 이어 갑니다 (NaviHelper TBT).
5. **차 상태에 맞춘 이야기**: 방향지시등을 켜면 멈추고 끄면 이어서, 와이퍼가 움직이면 비 오는 날 이야기, 전조등이 켜지면 밤 이야기로 바꿉니다 (Vehicle).
6. **주행 규제**: 달리는 동안에는 차 화면의 영상과 QR, 긴 글을 숨기고 소리로만 안내합니다. 차가 서면 명소 장면과 QR을 크게 보여 줍니다 (CarUxRestrictions).
7. **운전대 버튼**: 이야기를 미디어 세션으로 만들어 운전대의 이전, 다음 버튼으로 다시 듣거나 넘깁니다.
8. **창문**: 바닷가 명소에 차를 세우면 창문을 조금 열지 먼저 묻고, 확인을 누를 때만 한 뼘쯤 내립니다 (Vehicle 창문).
9. **함께 보는 휴대폰 화면**: 차 화면의 QR을 찍으면 앱 설치 없이 그룹 페이지가 열립니다. 차가 이야기를 시작하는 순간 휴대폰에도 같은 명소의 짧은 영상과 손으로 돌려 보는 3D 모형, 자막이 뜹니다.
10. **차 여러 대**: 네 자리 그룹 번호로 차들을 묶습니다. 휴대폰 지도에서 고른 카페나 주차장이 운전자 확인 한 번으로 그룹 차 모두의 내비게이션에 들어갑니다. 차의 실시간 위치는 주고받지 않습니다.
11. **영어 안내**: 안내 언어를 영어로 바꾸면 차 음성과 휴대폰 화면이 모두 영어로 바뀝니다 (서울 시나리오).
12. **하루 기록 카드**: 지나온 길, 명소를 지난 시각, 들은 이야기를 모아 보여 줍니다. 휴대폰 사진은 찍은 시각에 맞춰 길 위에 붙고, 빌트인 캠 영상을 올리면 명소를 지난 장면만 골라 짧은 여행 영상으로 만듭니다.

## 구성

```
app/                  차 안 앱 (Kotlin, Jetpack Compose, Pleos Connect SDK)
companion/server.js   여행 그룹 서버 (Node.js, WebSocket)
companion/web/        휴대폰 그룹 페이지, 3D 뷰어 (three.js, Leaflet)
tools/demo/           시연 영상 자동 녹화 도구 (선택)
```

### 쓰는 Pleos Connect SDK

| SDK | 쓰는 곳 |
|---|---|
| Vehicle 2.3.2 | 기어, 방향지시등, 와이퍼, 전조등, 좌석 착석, 안전벨트, 창문 위치 읽기. 실내 조명 색과 밝기, 창문 위치 바꾸기 |
| NaviHelper 3.0.5 | 목적지와 경유지 넣기, 다음 길 안내(TBT)까지 남은 거리, 안내 상태, 도착 알림 |
| FusedLocation 1.1.3 | 여행지와 명소마다 근접 알림(Proximity Alert) 등록, 현재 위치 |
| TextToSpeech 3.3.1 | 이야기 읽기 (ON_DEVICE 모드, 한국어 ko-KR, 영어 en-US) |
| CarUxRestrictions (AAOS) | 달리는 동안 영상과 QR, 긴 글 숨기기 |

코드 위치: [VehicleBridge](app/src/main/java/app/gureuneun/guide/sdk/VehicleBridge.kt), [NaviBridge](app/src/main/java/app/gureuneun/guide/sdk/NaviBridge.kt), [LocationBridge](app/src/main/java/app/gureuneun/guide/sdk/LocationBridge.kt), [SpeechBridge](app/src/main/java/app/gureuneun/guide/sdk/SpeechBridge.kt), [UxBridge](app/src/main/java/app/gureuneun/guide/sdk/UxBridge.kt), 여행 흐름은 [TripEngine](app/src/main/java/app/gureuneun/guide/engine/TripEngine.kt)

## 실행 방법

### 1. 차 앱 설치

```bash
adb install -r -g apk/개인참가_구르는가이드_release.apk
```

처음 실행할 때 위치 권한(pleos.car.permission.FUSED_LOCATION)을 허용해 주세요. 나머지 권한은 설치할 때 함께 허용됩니다.

### 2. 여행 그룹 서버 실행 (휴대폰 연동을 볼 때)

차 안 안내(음성, 조명, 내비게이션, 차 상태 연동)는 서버 없이도 동작합니다. 휴대폰 그룹 페이지와 차 여러 대 묶기를 보려면 서버를 켭니다.

```bash
cd companion
npm install
npm start
# 구르는 가이드 그룹 서버: http://<PC IP>:8787 (에뮬레이터에서는 http://10.0.2.2:8787)
```

- 에뮬레이터 안의 앱은 `http://10.0.2.2:8787`로 접속합니다. 다른 주소를 쓰려면 앱 처음 화면의 [점검]에서 바꿉니다.
- 휴대폰은 PC와 같은 와이파이에서 차 화면의 QR을 찍거나 `http://<PC IP>:8787`에서 그룹 번호를 넣습니다. PC 브라우저로 열어도 됩니다.
- 빌트인 캠 하이라이트 영상을 만들려면 PC에 ffmpeg가 있어야 합니다. 자막은 ffmpeg drawtext 필터가 있으면 그걸 쓰고, 없으면 Python Pillow로 그립니다.

### 3. 시연 순서

1. 처음 화면에서 여행지(경주, 제주, 서울)와 안내 언어를 고르고 [여행 시작]을 누릅니다. 순정 내비게이션에 경로가 들어간 뒤 앱으로 돌아옵니다.
2. 좌석 점호 화면에서 [모두 맸어요, 출발]을 누릅니다. 에뮬레이터는 좌석별 벨트 신호를 바꿀 수 없어 화면 버튼으로 확인합니다. 실차에서는 벨트 신호가 오면 자동으로 넘어갑니다.
3. Virtual Console에서 기어를 D로 바꾸면 시연 주행이 시작됩니다. P로 바꾸면 멈추고 명소 장면과 QR이 떠요.
4. Virtual Console에서 방향지시등, 와이퍼, 전조등을 바꿔 보세요. 이야기가 멈추거나 비 오는 날, 밤 이야기로 바뀝니다.
5. 제주 시나리오는 협재해수욕장에서 P로 바꾸면 창문을 열지 묻습니다.
6. [여행 끝]을 누르면 하루 기록 카드가 나옵니다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 AVD (Android 14, API 34), 2560x1440, 160dpi |
| 차량 상태 바꾸기 | Pleos Connect Virtual Console (`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088) |
| 빌드 | JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compileSdk 34 |
| 그룹 서버 | Node.js 20 이상, ffmpeg (하이라이트 영상) |
| 확인한 동작 | 한국어, 영어 음성 / 실내 조명 색과 밝기 / 창문 위치 / 경유지 두 곳 경로 요청 / TBT 거리 수신 / 기어, 방향지시등, 와이퍼, 전조등 수신 / 주행 규제 수신 |

### 에뮬레이터에서 다르게 동작하는 부분

- **위치**: 에뮬레이터는 GPS 위치가 움직이지 않습니다. 그래서 [시연 경로] 모드에서 실제 도로 경로(OSRM으로 만든 경로)를 따라 위치를 흘려 넣고, 근접 알림과 같은 판정을 앱 안에서 합니다. 실차에서는 [실제 위치] 모드로 FusedLocation 근접 알림을 그대로 씁니다.
- **다음 길 안내 거리**: 시연 경로 모드에서는 시연 경로의 회전 지점까지 남은 거리로 계산합니다. 실제 위치 모드에서는 NaviHelper가 주는 TBT 거리를 씁니다.
- **명소 장면**: 차 화면은 3D를 실시간으로 그리지 않고 같은 3D 장면에서 뽑은 장면 세 컷을 천천히 보여 줍니다. 에뮬레이터 그래픽이 CPU로 돌아가 실시간 3D가 무겁기 때문입니다. 휴대폰은 짧은 영상과 실시간 3D를 모두 씁니다.

## 직접 빌드

```bash
# local.properties 에 sdk.dir 지정
./gradlew :app:assembleDebug
# 릴리스 서명: 저장소 밖에 keystore.properties(storeFile, storePassword, keyAlias, keyPassword)를 두면 그 키로 서명
./gradlew :app:assembleRelease
```

시연 영상 녹화용으로 adb 명령도 받습니다. 화면 버튼과 같은 동작만 합니다.

```bash
adb shell am start -n app.gureuneun.guide/.MainActivity --es demo start --es scenario jeju --es lang ko
adb shell am start -n app.gureuneun.guide/.MainActivity --es demo go      # 출발
adb shell am start -n app.gureuneun.guide/.MainActivity --es demo jump    # 다음 명소 앞으로
```

## 이야기 출처

이야기의 사실은 아래 자료에서 확인한 것만 썼습니다. 길이와 말투만 다르게 다듬었고 자료에 없는 내용은 넣지 않았습니다. 틀린 곳은 휴대폰 화면의 [틀린 내용 신고]로 받습니다.

- 위키백과: 첨성대, 대릉원, 천마총, 황남대총, 동궁과 월지, 경주역사유적지구, 63빌딩, 여의도, 한강공원, 국회의사당 (대한민국), 협재해수욕장, 애월읍
- 대한민국 구석구석 (한국관광공사): 이호테우 말 등대
- 지도와 경로: © OpenStreetMap 기여자, OSRM

## 사용한 오픈소스

- three.js r158 (MIT), Leaflet 1.9.4 (BSD-2-Clause): `companion/web/vendor`
- ZXing (Apache-2.0), OkHttp (Apache-2.0), Jetpack Compose (Apache-2.0), ws (MIT)

© 2026 구르는 가이드. 공모전 심사용으로 공개한 저장소입니다.

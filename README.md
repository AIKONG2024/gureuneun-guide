# 구르는 가이드

**버튼 하나면 차가 여행 가이드가 됩니다.**

차가 지나는 곳마다 어느 쪽 창밖에 무엇이 보이는지 먼저 알려 주고 그곳 이야기를 들려줍니다. 궁금한 건 말로 물어보면 되고, 함께 탄 사람은 QR을 찍어 휴대폰으로 같이 봅니다. Pleos Connect 앱이고 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 시연 영상

[![시연 영상](docs/demo/poster.jpg)](https://aikong2024.github.io/gureuneun-guide/)

[2분짜리 영상](https://aikong2024.github.io/gureuneun-guide/)입니다. Pleos Connect 에뮬레이터에서 앱이 돌아가는 화면과 차가 낸 안내 음성을 그대로 담았습니다. 녹화 중에는 마이크를 쓸 수 없어서 질문 목소리는 음성 파일을 SpeechToText SDK에 넣었습니다.

## 화면

| 여행지를 고르고 [여행 시작] | 출발 전에는 QR과 오늘 코스 | 명소 앞에서는 보이는 쪽부터 |
|---|---|---|
| ![처음 화면](docs/images/car_home.jpg) | ![출발 전](docs/images/car_before.jpg) | ![명소 안내](docs/images/car_story.jpg) |

| 물어보면 코스 밖 볼거리도 답함 | 휴대폰에서 고른 곳은 "응" 한마디로 | 하루가 엽서 한 장으로 |
|---|---|---|
| ![말로 묻기](docs/images/car_ask.jpg) | ![고른 장소](docs/images/car_pick.jpg) | ![하루 기록](docs/images/car_record.jpg) |

내비게이션 옆 절반 창에서도 그대로 씁니다.

<img src="docs/images/split_story.jpg" width="720" alt="내비게이션 옆 절반 창">

함께 탄 사람의 휴대폰입니다. 앱을 깔지 않고 QR로 들어옵니다.

<p>
<img src="docs/images/phone_story.jpg" width="200" alt="이야기">
<img src="docs/images/phone_quiz.jpg" width="200" alt="퀴즈">
<img src="docs/images/phone_pick.jpg" width="200" alt="장소 고르기">
<img src="docs/images/phone_record.jpg" width="200" alt="기록">
</p>

## 하는 일

- [여행 시작]을 누르면 목적지와 경유지가 순정 내비게이션에 들어갑니다. 기어를 D로 옮기면 안내가 시작됩니다.
- 명소에 가까워지면 "잠시 후 오른쪽에 첨성대가 보여요" 하고 알려 준 뒤 이야기를 들려줍니다. 길 안내가 나오거나 방향지시등을 켜면 잠깐 멈춥니다.
- [물어보기]를 누르고 "오른쪽에 보이는 거 뭐야?" 하고 물으면 그쪽에 보이는 곳을 찾아 답합니다.
- 뒷자리에 아이가 타면 아이 눈높이 이야기를 덧붙이고 휴대폰으로 퀴즈를 보냅니다. 맞히면 실내 조명이 잠깐 금빛이 됩니다.
- 함께 탄 사람이 휴대폰에서 카페나 주차장을 고르면 차가 운전자에게 묻습니다. "응" 한마디면 내비게이션에 들어갑니다.
- 안내 언어를 영어로 바꾸면 이야기와 묻고 답하기가 영어로 바뀝니다. 운전자 화면은 그대로입니다.
- 목적지에 세우면 여행을 끝낼지 묻고, 그날 지나온 길과 명소를 엽서 한 장으로 남깁니다.

앱이 차에서 바꾸는 것은 실내 조명 하나이고 여행이 끝나면 원래대로 돌려 둡니다. 온도나 창문 같은 차 조작은 차의 음성 비서가 할 일이라 넣지 않았습니다. 차의 실시간 위치는 서버로 보내지 않고, 하루 기록은 운전자가 [휴대폰으로 보내기]를 눌러야 올라갑니다.

## 쓰는 Pleos Connect SDK

| SDK | 쓰는 곳 |
|---|---|
| Vehicle 2.3.2 | 기어, 방향지시등, 와이퍼, 전조등, 뒷좌석 착석을 읽고 실내 조명 색과 밝기를 바꿈 |
| NaviHelper 3.0.5 | 목적지와 경유지 넣기, 다음 길 안내까지 남은 거리 |
| FusedLocation 1.1.3 | 명소마다 근접 알림 |
| TextToSpeech 3.3.1 | 이야기 읽기 (한국어, 영어) |
| SpeechToText 3.3.1 | 질문과 "응" 알아듣기 (한국어, 영어) |

SDK마다 [app/src/main/java/app/gureuneun/guide/sdk](app/src/main/java/app/gureuneun/guide/sdk)에 파일이 하나씩 있고, 여행 흐름은 [TripEngine.kt](app/src/main/java/app/gureuneun/guide/engine/TripEngine.kt)에 있습니다.

## 실행

```bash
adb install -r -g apk/개인참가_구르는가이드_release.apk
adb forward tcp:8088 tcp:8088               # Virtual Console: http://127.0.0.1:8088
cd companion && npm install && npm start    # 휴대폰 화면을 볼 때만
```

1. 여행지를 고르고 [여행 시작]을 누릅니다.
2. Virtual Console에서 기어를 D로 옮기면 출발합니다. 에뮬레이터는 GPS가 움직이지 않아서 실제 도로를 따라 위치를 흘려 넣습니다.
3. 차 화면의 QR 주소를 PC 브라우저나 같은 와이파이의 휴대폰으로 엽니다.
4. 휴대폰 [장소]에서 카페를 골라 보내면 차가 묻습니다.
5. 기어를 P로 옮기면 정차 화면이 나오고, 마지막 목적지라면 여행을 끝낼지 묻습니다.

달리는 화면 오른쪽 위 단추에 다음 명소 앞으로 가기, 질문 음성 넣기 같은 시연 도구가 있습니다.

## 확인한 것

- Pleos Connect v2.1.0 에뮬레이터(Android 14, 2560x1440)의 넓은 창과 절반 창
- 단위 시험 28개: `./gradlew :app:testDebugUnitTest`
- 세 코스를 에뮬레이터에서 처음부터 끝까지 돌리는 흐름 시험 53항목: [tools/demo/flow.mjs](tools/demo/flow.mjs)
- 실차에서는 돌려 보지 못했습니다.

## 빌드

```bash
./gradlew :app:assembleRelease    # JDK 17, local.properties 에 sdk.dir
```

영상은 [tools/demo](tools/demo)의 record.mjs로 녹화하고 compose.py로 편집했습니다. 녹화 도구는 adb로 앱을 움직이는데, release 빌드에서는 처음 화면 오른쪽 위 [점검]에서 [시연 명령 받기]를 켜야 받습니다.

## 출처

- 이야기: 위키백과와 한국관광공사 대한민국 구석구석에서 확인한 사실만 썼습니다. 생성형 AI는 길이와 말투를 다듬는 데 썼습니다.
- 사진: 위키미디어 공용. 찍은 사람과 라이선스는 [docs/PHOTO_CREDITS.md](docs/PHOTO_CREDITS.md)
- 지도와 경로: © OpenStreetMap 기여자, OSRM
- 영상 음악: Pixabay, Acoustic Guitar (saavane)
- 오픈소스: Leaflet (BSD-2-Clause), ZXing, OkHttp, Jetpack Compose (Apache-2.0), ws (MIT), Asta Sans (OFL)

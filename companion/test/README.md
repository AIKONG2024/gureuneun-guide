# companion/test

시연 연습과 확인용 파일이에요. 서버를 켠 뒤(node companion/server.js) 아래처럼 씁니다.

- car_drive.json  : 차량이 경주 시내를 달리며 첨성대를 설명하는 순간의 trip 전체(PUT /api/trip/GJ1016/state 본문)
- car_record.json : 숙소에 도착해 여행 영상과 티켓이 준비된 trip 전체
- smoke.sh        : 차량 상태 보내기, 휴대폰 담기·들르기·사진 올리기, 링크 속 장소 찾기를 curl 로 시험
- shots.mjs       : 휴대폰 화면 4개 탭(390x844@3x)과 여행 영상 장면(1280x720)을 찍음

처음 상태로 되돌리기: curl -X POST localhost:8787/api/trip/GJ1016/reset

차량 앱이 시연 서버(8787)에 붙어 있을 때는 그 상태를 덮어쓰지 않게 시험 서버를 따로 띄워서 하세요.

    PORT=8797 STATE_DIR=/tmp/gg/state UPLOAD_DIR=/tmp/gg/uploads node companion/server.js
    sh companion/test/smoke.sh http://localhost:8797
    BASE=http://localhost:8797 node companion/test/shots.mjs /tmp/gg/shots

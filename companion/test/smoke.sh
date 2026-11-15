#!/bin/sh
# 구르는 가이드 동반 서버 API 시험. 사용: sh companion/test/smoke.sh [http://localhost:8787]
set -e
BASE=${1:-http://localhost:8787}
DIR=$(cd "$(dirname "$0")" && pwd)
J='Content-Type: application/json'
say() { printf '\n== %s\n' "$1"; }

say '서버 주소'
curl -sf "$BASE/api/info"
say '처음 상태로 되돌림'
curl -sf -X POST "$BASE/api/trip/GJ1016/reset"
say '링크 속 장소 찾기: 블로그 경주 황리단길 카페 후기'
curl -sf -X POST "$BASE/api/resolve" -H "$J" -d '{"link":"https://blog.naver.com/gureun_family/%EA%B2%BD%EC%A3%BC-%ED%99%A9%EB%A6%AC%EB%8B%A8%EA%B8%B8-%EC%B9%B4%ED%8E%98-%ED%9B%84%EA%B8%B0"}'
say '링크 속 장소 찾기: 못 찾는 링크'
curl -sf -X POST "$BASE/api/resolve" -H "$J" -d '{"link":"https://blog.naver.com/x/seoul-trip"}'
say '이름으로 다시 찾기'
curl -sf -X POST "$BASE/api/resolve" -H "$J" -d '{"link":"https://blog.naver.com/x/seoul-trip","name":"황리단"}'
say '휴대폰 담기(엄마, 황리단길 카페)'
curl -sf -X POST "$BASE/api/trip/GJ1016/inbox" -H "$J" -d '{"type":"add","by":"mom","place":{"id":"hwangridan","name":"황리단길 카페","kind":"cafe","area":"경주 황남동","lat":35.8372,"lon":129.2096,"photo":"hwangridan_1.jpg","source":"블로그"}}'
say '차량이 가져가기 전 GET trip 의 places 끝(pending)'
curl -sf "$BASE/api/trip/GJ1016" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);const p=j.trip.places;console.log(p.map(x=>x.name+":"+x.status).join(", "));console.log("inbox", j.inbox.length, "seq", j.seq)})'
say '차량이 inbox 가져감(after=0)'
curl -sf "$BASE/api/trip/GJ1016/inbox?after=0"
say '차량 상태 보내기(첨성대 설명 중)'
curl -sf -X PUT "$BASE/api/trip/GJ1016/state" -H "$J" --data-binary @"$DIR/car_drive.json"
say '들르기(첫째, 계림)'
curl -sf -X POST "$BASE/api/trip/GJ1016/inbox" -H "$J" -d '{"type":"visit","by":"kid1","id":"gyerim"}'
say '위치 공유 켜기(엄마)'
curl -sf -X POST "$BASE/api/trip/GJ1016/inbox" -H "$J" -d '{"type":"share","by":"mom","on":true}'
say '사진 올리기(엄마)'
curl -sf -X POST "$BASE/api/trip/GJ1016/photo?by=mom" -H 'Content-Type: image/jpeg' --data-binary @"$DIR/../web/photos/gyerim_night_1.jpg"
say '차량이 새 항목 가져감(after=1)'
curl -sf "$BASE/api/trip/GJ1016/inbox?after=1"
say '여행 영상 준비됨'
curl -sf -X PUT "$BASE/api/trip/GJ1016/state" -H "$J" --data-binary @"$DIR/car_record.json"
say '확인'
curl -sf "$BASE/api/trip/GJ1016" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log("rev",j.trip.rev,"record",!!(j.trip.record&&j.trip.record.ready),"photos",j.trip.record.photos.length,"mom sharing",j.trip.members[1].sharing)})'
printf '\n완료\n'

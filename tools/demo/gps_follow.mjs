// 시연 녹화용: 동반 서버에 올라온 차량 위치를 에뮬레이터 GPS 로 넣어, 순정 내비게이션도 시연 주행과 같은 자리를 따라가게 함
// 출발 직후(내비가 집에서 경주까지 경로를 찾는 동안)는 그대로 두고, 시연 주행이 움직이기 시작하면 따라감
// 실행: node tools/demo/gps_follow.mjs
import { execFile } from 'node:child_process';

const ADB = process.env.ADB || process.env.HOME + '/Library/Android/sdk/platform-tools/adb';
const BASE = process.env.BASE || 'http://localhost:8787';
const CODE = process.env.CODE || 'GJ1016';
let last = '';
let following = false;

async function tick() {
  try {
    const r = await fetch(BASE + '/api/trip/' + CODE);
    const j = await r.json();
    const car = j.trip && j.trip.car;
    if (!car || typeof car.lat !== 'number') return;
    if (!following && car.speedKmh > 0) {
      following = true;
      console.log('따라가기 시작', car.lat, car.lon);
    }
    if (!following) return;
    const key = car.lat.toFixed(6) + ',' + car.lon.toFixed(6);
    if (key === last) return;
    last = key;
    execFile(ADB, ['emu', 'geo', 'fix', String(car.lon), String(car.lat)], () => {});
  } catch (e) {
    // 서버가 잠깐 없어도 계속 시도
  }
}

setInterval(tick, 600);
console.log('GPS 따라가기 대기 중', BASE, CODE);


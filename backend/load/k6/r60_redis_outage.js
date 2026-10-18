// Redis 멈춤 연습의 부하 — 백엔드 1대를 nginx 뒤에서 작게 계속 두드리고, Redis 를 멈추고 얼리는 동안 사용자에게 보이는 것을 센다.
// infra/scripts/check-redis-outage.sh 가 스택을 띄우고, 이 스크립트를 돌리는 동안 Redis 를 멈추고(kill) 얼린다(pause). 판정은 쉘 스크립트가 한다.
//
//  - rest_write: 기사가 위치를 POST 한다(위치 송신). 위도의 마지막 자리에 초를 실어, 읽는 쪽이 얼마나 낡은 값을 받는지 잴 수 있다.
//  - rest_read: 학부모가 버스 위치를 GET 한다(Redis 최신 좌표 → 실패하면 DB 최신 행). 받은 위도로 값의 나이(초)를 잰다.
//  - rest_other: 학부모 회차 목록 GET — Redis 와 무관한 읽기. 멈춤 동안 5xx 가 0 이어야 하는 쪽의 기준.
//  - approval: 관계자가 같은 승인 건 상세를 1초마다 GET — 미리보기 계산이 지도 API(스텁)를 부른다. Redis 가 멈춰도 호출 수가 늘지 않아야 한다.
//  - sessions: STOMP 구독자가 학생 채널을 구독해 방송 사이 간격(ws_msg_gap_ms)을 센다.
// 모든 요청은 이름(name) 태그를 달아 --out json 의 점들을 구간별로 가를 수 있게 한다.
//
// 환경변수: NGINX_URL(기본 https://localhost:38443) · BE_URL(로그인용, 기본 http://localhost:38081) · SESSIONS(기본 10) · DURATION_SEC(기본 120)
//          · RUN_ID(기본 3) · STUDENT_ID(기본 2) · APPROVAL_ID(기본 1)
import http from 'k6/http';
import ws from 'k6/ws';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { connectFrame, subscribeFrame } from './lib/stomp.js';

const NGINX = __ENV.NGINX_URL || 'https://localhost:38443';
const BE = __ENV.BE_URL || 'http://localhost:38081';
const SESSIONS = Number(__ENV.SESSIONS || 10);
const DURATION_SEC = Number(__ENV.DURATION_SEC || 120);
const RUN_ID = Number(__ENV.RUN_ID || 3);
const STUDENT_ID = Number(__ENV.STUDENT_ID || 2);
const APPROVAL_ID = Number(__ENV.APPROVAL_ID || 1);

// 위도 = 37.5 + (초 % 100000) × 1e-6 — 좌표는 소수 6자리까지 저장되므로 1초가 마지막 한 자리로 남는다.
const LAT_BASE = 37.5;
const SECOND_WRAP = 100000;

export const options = {
    insecureSkipTLSVerify: true,
    scenarios: {
        rest_write: { executor: 'constant-vus', vus: 3, duration: `${DURATION_SEC}s`, exec: 'restWrite' },
        rest_read: { executor: 'constant-vus', vus: 3, duration: `${DURATION_SEC}s`, exec: 'restRead' },
        rest_other: { executor: 'constant-vus', vus: 2, duration: `${DURATION_SEC}s`, exec: 'restOther' },
        approval: { executor: 'constant-vus', vus: 1, duration: `${DURATION_SEC}s`, exec: 'approvalView' },
        sessions: { executor: 'per-vu-iterations', vus: SESSIONS, iterations: 1, exec: 'session', maxDuration: `${DURATION_SEC + 30}s` },
    },
};

const snapshotAgeSec = new Trend('snapshot_age_sec');
const wsMsgGapMs = new Trend('ws_msg_gap_ms');
const wsMsgs = new Counter('ws_msgs');
const wsCloses = new Counter('ws_closes');

function login(loginId) {
    const res = http.post(`${BE}/api/v1/auth/login`, JSON.stringify({ login_id: loginId, password: 'password' }),
        { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' } });
    if (res.status !== 200) throw new Error(`로그인 실패 ${loginId} status=${res.status}`);
    return res.json().data.access_token;
}

export function setup() {
    return { parent: login('parentA1'), driver: login('driverA2'), staff: login('staffA') };
}

function nowSecWrapped() {
    return Math.floor(Date.now() / 1000) % SECOND_WRAP;
}

export function restWrite(tokens) {
    const lat = (LAT_BASE + nowSecWrapped() * 1e-6).toFixed(6);
    http.post(`${NGINX}/api/v1/runs/${RUN_ID}/position`,
        JSON.stringify({ lat: Number(lat), lng: 127.0, recorded_at: new Date().toISOString() }),
        { headers: { Authorization: `Bearer ${tokens.driver}`, 'Content-Type': 'application/json' }, timeout: '10s', tags: { name: 'position_post' } });
    sleep(0.3);
}

export function restRead(tokens) {
    const res = http.get(`${NGINX}/api/v1/students/${STUDENT_ID}/bus-position`,
        { headers: { Authorization: `Bearer ${tokens.parent}` }, timeout: '10s', tags: { name: 'bus_position' } });
    if (res.status === 200) {
        const lat = res.json().data.lat;
        const sentSec = Math.round((lat - LAT_BASE) * 1e6);
        snapshotAgeSec.add((nowSecWrapped() - sentSec + SECOND_WRAP) % SECOND_WRAP);
    }
    sleep(0.3);
}

export function restOther(tokens) {
    http.get(`${NGINX}/api/v1/students/${STUDENT_ID}/runs`,
        { headers: { Authorization: `Bearer ${tokens.parent}` }, timeout: '10s', tags: { name: 'student_runs' } });
    sleep(0.3);
}

export function approvalView(tokens) {
    http.get(`${NGINX}/api/v1/staff/approvals/${APPROVAL_ID}`,
        { headers: { Authorization: `Bearer ${tokens.staff}` }, timeout: '10s', tags: { name: 'approval_detail' } });
    sleep(1);
}

// 세션 하나 = 재접속 루프(클라이언트의 정상 재접속 규칙을 흉내 낸다, API_SPEC §7.2). 방송이 올 때마다 직전 방송과의 간격을 센다.
export function session(tokens) {
    const endAt = Date.now() + DURATION_SEC * 1000;
    const backoffs = [500, 1000, 2000, 4000, 5000];
    let attempt = 0;
    let lastMsgAt = 0;
    while (Date.now() < endAt) {
        ws.connect(`${NGINX.replace('https', 'wss')}/ws/location`, {}, function (socket) {
            socket.on('open', () => socket.send(connectFrame(tokens.parent)));
            socket.on('message', (raw) => {
                if (raw.startsWith('CONNECTED')) {
                    attempt = 0;
                    socket.send(subscribeFrame('sub-1', `/topic/students/${STUDENT_ID}/run`));
                } else if (raw.startsWith('MESSAGE')) {
                    const now = Date.now();
                    if (lastMsgAt > 0) wsMsgGapMs.add(now - lastMsgAt);
                    lastMsgAt = now;
                    wsMsgs.add(1);
                }
            });
            socket.on('close', () => wsCloses.add(1));
            socket.setTimeout(() => socket.close(), Math.max(1, endAt - Date.now()));
        });
        if (Date.now() >= endAt) break;
        sleep(backoffs[Math.min(attempt, backoffs.length - 1)] / 1000);
        attempt++;
    }
}

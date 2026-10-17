// 한 대 정지 확인 — nginx 를 거치는 REST 연속 요청과 WebSocket 세션이 백엔드 한 대가 멈춰도 남은 대로 이어지는지 본다.
// infra/scripts/check-instance-failover.sh 가 부른다(스택을 띄우고, 이 스크립트를 돌리는 동안 backend 한 대를 멈춘다).
//
//  - REST: 기사(POST 위치 · 요청이 이미 나갔으면 nginx 가 다시 보내지 않는 쓰기)와 학부모(GET 조회)가 nginx 로 초당 약 10건씩 보낸다.
//    성공 = 2xx 이고 응답이 SLOW_MS 안. 실패한 요청은 시작 시각(ms)과 함께 FAILURES 에 남는다.
//  - WebSocket: SESSIONS 개를 nginx 로 붙여 학생 채널을 구독한다. 끊기면 0.5·1·2·4·5초 간격으로 다시 붙는다(클라이언트의 정상 재접속 규칙을 흉내 낸 것).
//    세션이 닫힌 횟수(CLOSES)와 다시 붙은 횟수(REOPENS)를 센다.
// 결과는 RESULT_FILE(JSON)에 쓴다. 판정은 쉘 스크립트가 한다(정지 시각과 비교).
//
// 환경변수: NGINX_URL(기본 https://localhost:28443) · BE1_URL(로그인용, 기본 http://localhost:28081) · SESSIONS(기본 20) · DURATION_SEC(기본 60) · SLOW_MS(기본 3000) · RESULT_FILE
import http from 'k6/http';
import ws from 'k6/ws';
import { sleep } from 'k6';
import { connectFrame, subscribeFrame } from './lib/stomp.js';

const NGINX = __ENV.NGINX_URL || 'https://localhost:28443';
const BE1 = __ENV.BE1_URL || 'http://localhost:28081';
const SESSIONS = Number(__ENV.SESSIONS || 20);
const DURATION_SEC = Number(__ENV.DURATION_SEC || 60);
const SLOW_MS = Number(__ENV.SLOW_MS || 3000);
const RUN_ID = Number(__ENV.RUN_ID || 3);
const STUDENT_ID = Number(__ENV.STUDENT_ID || 2);

export const options = {
    insecureSkipTLSVerify: true,
    scenarios: {
        rest_write: { executor: 'constant-vus', vus: 3, duration: `${DURATION_SEC}s`, exec: 'restWrite' },
        rest_read: { executor: 'constant-vus', vus: 3, duration: `${DURATION_SEC}s`, exec: 'restRead' },
        sessions: { executor: 'per-vu-iterations', vus: SESSIONS, iterations: 1, exec: 'session', maxDuration: `${DURATION_SEC + 30}s` },
    },
};

// VU 마다 자기 상태를 쓴다(k6 VU 는 서로 메모리를 공유하지 않는다) — 요약은 VU 가 끝나며 남긴 숫자를 handleSummary 가 합친다.
import { Counter } from 'k6/metrics';
const restSent = new Counter('rest_sent');
const restFailed = new Counter('rest_failed');
const wsCloses = new Counter('ws_closes');
const wsReopens = new Counter('ws_reopens');
// 실패한 REST 요청의 시작 시각(ms)을 담는다. 개수가 적어(0이어야 한다) 태그 대신 로그 줄로 남기고, 쉘이 k6 의 stderr 에서 읽는다.
function logFailure(kind, startedAtMs, status, durationMs) {
    console.error(`REST_FAIL ${kind} start=${startedAtMs} status=${status} ms=${Math.round(durationMs)}`);
}

function login(loginId) {
    const res = http.post(`${BE1}/api/v1/auth/login`, JSON.stringify({ login_id: loginId, password: 'password' }),
        { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' } });
    if (res.status !== 200) throw new Error(`로그인 실패 ${loginId} status=${res.status}`);
    return res.json().data.access_token;
}

export function setup() {
    return { parent: login('parentA1'), driver: login('driverA2') };
}

function timed(kind, fn) {
    const startedAt = Date.now();
    const res = fn();
    const ms = Date.now() - startedAt;
    restSent.add(1);
    const ok = (res.status >= 200 && res.status < 300) && ms < SLOW_MS;
    if (!ok) { restFailed.add(1); logFailure(kind, startedAt, res.status, ms); }
}

export function restWrite(tokens) {
    timed('POST', () => http.post(`${NGINX}/api/v1/runs/${RUN_ID}/position`,
        JSON.stringify({ lat: 37.5, lng: 127.0, recorded_at: new Date().toISOString() }),
        { headers: { Authorization: `Bearer ${tokens.driver}`, 'Content-Type': 'application/json' }, timeout: '10s' }));
    sleep(0.3);
}

export function restRead(tokens) {
    timed('GET', () => http.get(`${NGINX}/api/v1/students/${STUDENT_ID}/runs`,
        { headers: { Authorization: `Bearer ${tokens.parent}` }, timeout: '10s' }));
    sleep(0.3);
}

// 세션 하나 = 재접속 루프. 끊기면 backoff 후 다시 붙고, 시험 시간이 끝나면 닫는다.
export function session(tokens) {
    const endAt = Date.now() + DURATION_SEC * 1000;
    const backoffs = [500, 1000, 2000, 4000, 5000];
    let attempt = 0;
    let opened = false;
    while (Date.now() < endAt) {
        let connectedThisTime = false;
        ws.connect(`${NGINX.replace('https', 'wss')}/ws/location`, {}, function (socket) {
            socket.on('open', () => socket.send(connectFrame(tokens.parent)));
            socket.on('message', (raw) => {
                if (raw.startsWith('CONNECTED')) {
                    connectedThisTime = true;
                    if (opened) wsReopens.add(1);
                    opened = true;
                    attempt = 0;
                    socket.send(subscribeFrame('sub-1', `/topic/students/${STUDENT_ID}/run`));
                }
            });
            socket.on('close', () => { if (connectedThisTime) wsCloses.add(1); });
            socket.setTimeout(() => socket.close(), Math.max(1, endAt - Date.now()));
        });
        if (Date.now() >= endAt) break;
        sleep(backoffs[Math.min(attempt, backoffs.length - 1)] / 1000);
        attempt++;
    }
}

export function handleSummary(data) {
    const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
    const result = { rest_sent: count('rest_sent'), rest_failed: count('rest_failed'), ws_closes: count('ws_closes'), ws_reopens: count('ws_reopens'), sessions: SESSIONS };
    return { [__ENV.RESULT_FILE || '/tmp/r59-failover.json']: JSON.stringify(result) };
}

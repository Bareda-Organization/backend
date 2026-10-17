// 인스턴스 간 전달 확인 — 백엔드 2대 구성에서 한 대에 보낸 위치 · 비상 이벤트를 다른 대에 붙은 구독자가 받는지 센다.
// infra/scripts/check-cross-instance.sh 가 부른다(스택을 띄우고 이 스크립트를 돌려 판정한다).
//
//  - 보내는 쪽: 기사 계정이 BE1 에 위치 POST(N건) + 비상 POST(1건)를 직접 보낸다(nginx 를 거치지 않는다).
//  - 받는 쪽(교차): 학부모가 BE2 의 학생 채널을, 관계자가 BE2 의 학원 채널을 구독한다.
//  - 받는 쪽(대조): 같은 두 구독을 BE1 에도 둔다 — 보낸 쪽 인스턴스 자신은 받아야 하므로, 대조가 모자라면 확인 자체가 깨진 것이다.
//  - 건수는 STOMP 본문의 `event` 필드(position · emergency_raised)로 센다. 결과는 RESULT_FILE(JSON)에 쓴다.
//
// 환경변수: BE1_URL · BE2_URL(기본 http://localhost:28081 · 28082) · POSITIONS(기본 20) · RUN_ID(3) · STUDENT_ID(2) · ACADEMY_ID(1) · RESULT_FILE
import http from 'k6/http';
import ws from 'k6/ws';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const BE1 = __ENV.BE1_URL || 'http://localhost:28081';
const BE2 = __ENV.BE2_URL || 'http://localhost:28082';
const POSITIONS = Number(__ENV.POSITIONS || 20);
const RUN_ID = Number(__ENV.RUN_ID || 3);
const STUDENT_ID = Number(__ENV.STUDENT_ID || 2);
const ACADEMY_ID = Number(__ENV.ACADEMY_ID || 1);
const SEND_START_SEC = 8;
const SEND_INTERVAL_SEC = 0.5;
const LISTEN_SEC = SEND_START_SEC + Math.ceil(POSITIONS * SEND_INTERVAL_SEC) + 8;

const sent = { position: new Counter('sent_position'), emergency_raised: new Counter('sent_emergency_raised') };
// 수신 카운터 이름 = recv_<인스턴스>_<채널>_<이벤트>
const received = {};
for (const inst of ['be1', 'be2']) {
    for (const [channel, events] of [['student', ['position']], ['academy', ['position', 'emergency_raised']]]) {
        for (const ev of events) received[`${inst}_${channel}_${ev}`] = new Counter(`recv_${inst}_${channel}_${ev}`);
    }
}
const subscribeFailures = new Counter('subscribe_failures');
const sendFailures = new Counter('send_failures');

export const options = {
    scenarios: {
        subscribers: { executor: 'per-vu-iterations', vus: 4, iterations: 1, exec: 'subscriber', maxDuration: '2m' },
        sender: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'sender', startTime: '0s', maxDuration: '2m' },
    },
};

function login(baseUrl, loginId) {
    const res = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({ login_id: loginId, password: 'password' }),
        { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' } });
    if (res.status !== 200) throw new Error(`로그인 실패 ${loginId} status=${res.status}`);
    return res.json().data.access_token;
}

export function setup() {
    return { parent: login(BE1, 'parentA1'), staff: login(BE1, 'staffA'), driver: login(BE1, 'driverA2') };
}

// VU 1~4 = (BE2 학부모, BE2 관계자, BE1 학부모, BE1 관계자)
export function subscriber(tokens) {
    const idx = (__VU - 1) % 4;
    const inst = idx < 2 ? 'be2' : 'be1';
    const channel = idx % 2 === 0 ? 'student' : 'academy';
    const baseUrl = inst === 'be2' ? BE2 : BE1;
    const token = channel === 'student' ? tokens.parent : tokens.staff;
    const destination = channel === 'student' ? `/topic/students/${STUDENT_ID}/run` : `/topic/academy/${ACADEMY_ID}/live`;
    ws.connect(`${baseUrl.replace('http', 'ws')}/ws/location`, {}, function (socket) {
        socket.on('open', () => socket.send(connectFrame(token)));
        socket.on('message', (raw) => {
            for (const frame of parseFrames(raw)) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-1', destination));
                } else if (frame.command === 'ERROR') {
                    subscribeFailures.add(1);
                } else if (frame.command === 'MESSAGE') {
                    let body = null;
                    try { body = JSON.parse(frame.body); } catch (e) { /* 본문이 JSON 이 아니면 센 것에서 뺀다 */ }
                    const counter = body && received[`${inst}_${channel}_${body.event}`];
                    if (counter) counter.add(1);
                }
            }
        });
        socket.on('error', () => subscribeFailures.add(1));
        socket.setTimeout(() => socket.close(), LISTEN_SEC * 1000);
    });
}

export function sender(tokens) {
    sleep(SEND_START_SEC);
    const headers = { Authorization: `Bearer ${tokens.driver}`, 'Content-Type': 'application/json' };
    for (let i = 0; i < POSITIONS; i++) {
        const res = http.post(`${BE1}/api/v1/runs/${RUN_ID}/position`,
            JSON.stringify({ lat: 37.5 + i * 0.0001, lng: 127.0 + i * 0.0001, recorded_at: new Date().toISOString() }), { headers });
        if (res.status === 204) sent.position.add(1); else sendFailures.add(1);
        sleep(SEND_INTERVAL_SEC);
    }
    const res = http.post(`${BE1}/api/v1/runs/${RUN_ID}/emergency`,
        JSON.stringify({ type: 'accident', client_key: uuid() }), { headers });
    if (res.status === 201) sent.emergency_raised.add(1); else sendFailures.add(1);
}

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}

export function handleSummary(data) {
    const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
    const result = { sent: { position: count('sent_position'), emergency_raised: count('sent_emergency_raised') },
        subscribe_failures: count('subscribe_failures'), send_failures: count('send_failures'), received: {} };
    for (const key of Object.keys(received)) result.received[key] = count(`recv_${key}`);
    return { [__ENV.RESULT_FILE || '/tmp/r59-cross-instance.json']: JSON.stringify(result) };
}

// 시나리오 6 — 학생 목적지 3,000곳 이상 (R59 L-3). 학생 채널(/topic/students/{id}/run) 구독이 3,000곳을 넘어도
// 위치 수신 응답과 방송 지연이 기준 안인지 본다. 서버의 심플 브로커 구독 조회 보관 상한은 8,192(WebSocketConfig.SUBSCRIPTION_CACHE_LIMIT)이고,
// 위치 방송 목록 보관(RunBroadcastCache)은 회차 2,000개 상한이다 — 목적지가 늘어도 두 값 안에서 도는지가 이 시험의 대상이다.
//
// 구성 — ① 학부모 세션 DESTINATIONS 개: 학부모 1명이 자기 학생 채널 1곳을 구독하고 끝까지 받는다(목적지 = 학생이므로 세션 수 = 목적지 수).
//        ② 위치 송신: 위치용 회차 CSV 의 기사마다 INTERVAL_SEC 고정 주기(앱의 Timer.periodic 과 같게 도착률 고정 — 응답이 늦어도 송신 시각은 안 밀린다).
//        ③ 관계자 관측자: 학원마다 학원 채널 1곳 — 학생 채널과 다른 종류의 채널 지연을 함께 본다.
// 위치용 회차마다 학생 32명(r46_link_position_riders.sql 20 + destination_link_riders.sql 12)이 붙어 있어 위치 1건이 학생 채널 32곳 + 학원 채널으로 나간다.
//
// 임계(실패 시 k6 종료 코드 99) — 위치 POST 실패 0 · POST p95 < 2,000ms · 학생 채널·학원 채널 방송 지연 p95 ≤ 5,000ms ·
// 위치를 1건 이상 받은 학생 목적지(중복 제외) ≥ MIN_DESTINATIONS · 연결 실패 0 · 버려진 반복 0(k6 가 병목이면 시험이 무효).
// 서버(심플 브로커)는 SUBSCRIBE 의 receipt 헤더에 RECEIPT 를 돌려주지 않는다(2026-10-18 실측 — 0건). 그래서 "구독이 섰다" 는 그 채널의 방송을 실제로 1건 받은 것으로 센다 —
// 학생 한 명 = 세션 한 개 = 목적지 한 곳(반복 번호로 학부모를 고르므로 겹치지 않는다). 구독 요청을 보낸 수는 따로 참고값으로 남긴다.
//
// 환경변수: STUDENT_TOKENS(r46_mint_tokens.py 출력) · SCENARIO6_CSV(scenario2_prep.sql 출력) · DESTINATIONS(기본 3200) · MIN_DESTINATIONS(기본 3000)
//          · SCENARIO6_RAMP_SEC(60) · SCENARIO6_POS_SEC(120) · SCENARIO6_INTERVAL_SEC(2)
import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { scenario } from 'k6/execution';
import { login } from './lib/auth.js';
import { BASE_URL, WS_URL, SEED_PASSWORD } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const num = (name, fallback) => (__ENV[name] === undefined ? fallback : Number(__ENV[name]));
const DESTINATIONS = num('DESTINATIONS', 3200);
const MIN_DESTINATIONS = num('MIN_DESTINATIONS', 3000);
const RAMP_SEC = num('SCENARIO6_RAMP_SEC', 60);
const POS_SEC = num('SCENARIO6_POS_SEC', 120);
const INTERVAL_SEC = num('SCENARIO6_INTERVAL_SEC', 2);
const POS_START_SEC = RAMP_SEC + 15; // 목적지 구독이 다 선 뒤에 위치를 보낸다 — 받을 대상이 있어야 팬아웃 비용이 실제로 든다
const PART = __ENV.SCENARIO6_PART || 'all'; // students: 학생 세션만 · positions: 위치 송신 + 학원 관측자 · all: 한 프로세스
const LATENCY_SAMPLE_EVERY = num('LATENCY_SAMPLE_EVERY', 4); // 방송 N건에 1건만 JSON 을 읽어 지연을 잰다(건수는 전부 센다) — 수신 처리 CPU 가 k6 병목이 되지 않게
const HEARTBEAT_MS = 10000;
const TOTAL_SEC = POS_START_SEC + POS_SEC + 15;

const tokens = JSON.parse(open(__ENV.STUDENT_TOKENS));
const parents = new SharedArray('l3_parents', () => tokens.parents);
const staff = tokens.staff.filter((s) => s.runId !== null); // 학원마다 1개(LOADCAP 학원 10곳) — staffA(runId 없음)는 뺀다
const runs = new SharedArray('l3_runs', () => open(__ENV.SCENARIO6_CSV).trim().split('\n').filter((l) => l.length > 0).map((line) => {
    const [tag, loginId, runId, academyId] = line.split(',');
    return { loginId, runId: Number(runId), academyId: Number(academyId) };
}));

const positionPostMs = new Trend('position_post_duration_ms', true);
const positionFailures = new Counter('position_post_failures');
const positionsSent = new Counter('positions_sent_total');
const viewerLatencyMs = new Trend('viewer_ws_latency_ms', true);
const academyLatencyMs = new Trend('academy_ws_latency_ms', true);
const viewerMessages = new Counter('viewer_ws_messages_received');
const academyMessages = new Counter('academy_ws_messages_received');
const subscribeSent = new Counter('destinations_subscribe_sent');
const destinationsReceived = new Counter('destinations_received');
const wsConnectFailures = new Counter('ws_connect_failures');
const errorFrames = new Counter('error_frames');
const statusCounters = {};
for (const st of [0, 101, 200, 400, 401, 403, 404, 429, 500, 502, 503, 504]) statusCounters[st] = new Counter(`ws_connect_status_${st}`);
const statusOther = new Counter('ws_connect_status_other');

const allScenarios = {
        student_sessions: {
            // 학생 한 명 = 반복 한 번 = 세션 한 개 — 도착률로 RAMP_SEC 동안 고르게 올린다(반복 번호가 0..DESTINATIONS-1 로 겹치지 않는다)
            executor: 'constant-arrival-rate', exec: 'studentSession', rate: DESTINATIONS, timeUnit: `${RAMP_SEC}s`,
            duration: `${RAMP_SEC}s`, preAllocatedVUs: DESTINATIONS + 10, maxVUs: DESTINATIONS + 10, // 도착률 경계에서 반복이 하나 더 잡히는 경우를 위한 여유 VU
            gracefulStop: `${TOTAL_SEC + 20}s`, // 반복(= 세션)이 시험 끝까지 붙어 있어야 하므로 k6 의 기본 정지 유예 30초로 끊기지 않게 한다
        },
        academy_sessions: { executor: 'per-vu-iterations', exec: 'academySession', vus: Math.min(staff.length, 10), iterations: 1, maxDuration: `${TOTAL_SEC + 30}s` },
        position_ticks: {
            executor: 'constant-arrival-rate', exec: 'positionTick', startTime: `${POS_START_SEC}s`,
            rate: runs.length, timeUnit: `${INTERVAL_SEC}s`, duration: `${POS_SEC}s`, preAllocatedVUs: runs.length, maxVUs: runs.length * 2,
        },
    };
const partOf = { student_sessions: 'students', academy_sessions: 'positions', position_ticks: 'positions' };
const allThresholds = {
    students: {
        viewer_ws_latency_ms: ['p(95)<=5000'],
        destinations_received: [`count>=${MIN_DESTINATIONS}`],
        ws_connect_failures: ['count==0'],
        dropped_iterations: ['count==0'],
    },
    positions: {
        position_post_failures: ['count==0'],
        position_post_duration_ms: ['p(95)<2000'],
        academy_ws_latency_ms: ['p(95)<=5000'],
        dropped_iterations: ['count==0'],
    },
};
const parts = PART === 'all' ? ['students', 'positions'] : [PART];
export const options = {
    scenarios: Object.fromEntries(Object.entries(allScenarios).filter(([name]) => parts.includes(partOf[name]))),
    thresholds: Object.assign({}, ...parts.map((p) => allThresholds[p])),
};

export function setup() {
    const drivers = {};
    for (const r of runs) drivers[r.runId] = login(r.loginId, SEED_PASSWORD);
    return { drivers };
}

// 클라이언트 하트비트 — 서버는 하트비트를 협상하지 않은 세션을 무수신 60초에 닫는다(API_SPEC §7.2). 실제 앱 · 웹은 10초마다 보내므로 같게 한다(실측: 이것 없이는 세션이 60~70초에 끊겼다).
// 세션 하나 — CONNECT → SUBSCRIBE → 끝까지 수신. 처음 받은 방송에서 "받은 목적지" 를 한 번만 센다(학생 채널만).
function hold(token, destination, kind, messages, latency) {
    let gotFirst = false; let seen = 0;
    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', () => socket.send(connectFrame(token)));
        socket.setInterval(() => socket.send('\n'), HEARTBEAT_MS);
        socket.on('message', function (raw) {
            const now = Date.now();
            for (const frame of parseFrames(raw)) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-0', destination));
                    subscribeSent.add(1);
                } else if (frame.command === 'ERROR') {
                    errorFrames.add(1);
                    wsConnectFailures.add(1);
                } else if (frame.command === 'MESSAGE') {
                    messages.add(1);
                    if (!gotFirst && kind === 'student') { gotFirst = true; destinationsReceived.add(1); }
                    if (++seen % LATENCY_SAMPLE_EVERY === 1 || LATENCY_SAMPLE_EVERY === 1) {
                        try {
                            const body = JSON.parse(frame.body);
                            if (body && body.occurred_at) latency.add(now - new Date(body.occurred_at).getTime());
                        } catch (e) { /* 본문이 JSON 이 아니면 지연만 못 잰다 */ }
                    }
                }
            }
        });
        socket.on('error', () => wsConnectFailures.add(1));
        socket.setTimeout(() => socket.close(), (TOTAL_SEC + 5) * 1000);
    });
    check(res, { 'ws 연결 성공(101)': (r) => r && r.status === 101 });
    const status = res ? res.status : 0;
    (statusCounters[status] || statusOther).add(1);
    if (status !== 101) wsConnectFailures.add(1);
}

export function studentSession() {
    if (scenario.iterationInTest >= DESTINATIONS) return; // 경계에서 하나 더 잡힌 반복은 세션을 열지 않는다 — 학생 한 명에 세션 한 개
    const parent = parents[scenario.iterationInTest % parents.length];
    hold(parent.token, `/topic/students/${parent.studentId}/run`, 'student', viewerMessages, viewerLatencyMs);
}

export function academySession() {
    const tab = staff[scenario.iterationInTest % staff.length];
    hold(tab.token, `/topic/academy/${tab.academyId}/live`, 'academy', academyMessages, academyLatencyMs);
}

export function positionTick(data) {
    const run = runs[scenario.iterationInTest % runs.length];
    const res = http.post(`${BASE_URL}/runs/${run.runId}/position`,
        JSON.stringify({ lat: 37.5 + (scenario.iterationInTest % 1000) * 0.00001, lng: 127.0, recorded_at: new Date().toISOString() }),
        { headers: { Authorization: `Bearer ${data.drivers[run.runId]}`, 'Content-Type': 'application/json' } });
    positionsSent.add(1);
    positionPostMs.add(res.timings.duration);
    if (res.status !== 204) positionFailures.add(1);
}

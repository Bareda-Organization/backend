// 시나리오 8 — 토큰 만료 전 계획된 재접속 (R59 L-6, Ruling 901 · 921 E). 접근 토큰 수명 15분마다 세션 하나가 만료 60초 전에
//   ① 재발급(POST /auth/refresh, API_SPEC §2.6 — 재발급 토큰이 회전한다) → ② 지금 연결을 닫고 → ③ 새 토큰으로 CONNECT → ④ 구독 복원 → ⑤ REST 스냅샷 1회
// 를 한다(API_SPEC §7.2 「만료 전 재접속」). 세션 TOTAL(2,000)개가 15분 주기로 흩어져 있으면 초당 약 2.2회 재접속이다.
//   실제 비율 — 세션마다 주기 PERIOD_SEC(기본 TOTAL ÷ RATE) · 시작 위치를 세션 번호 순으로 고르게 흩어 초당 RATE 회가 처음부터 일정하게 나온다.
//   5배 — RATE_FACTOR=5 로 주기를 1/5 로 줄인다(초당 약 11회).
// 뒷배경 부하(r3_mixed.sh 의 관리자 50 · 시청 570 · 위치 2초)는 이 스크립트 밖에서 겹친다.
//
// 잰 것 — 재접속 한 번의 공백: 닫은 순간 → 새 연결의 구독이 서서 첫 방송을 받을 때까지(`reconnect_gap_ms` = `resubscribe_ms`, 같은 값을 두 이름으로 남긴다).
// 위치 방송이 2초마다 오는 채널(학생 · 학원 · 관제)이라 첫 메시지 공백은 구독 복원 시간 + 최대 2초다.
// 임계(종료 코드 99) — 재발급 실패 0 · 계획된 재접속 중 5초 안에 구독을 못 복원한 수 0(§7.2 「새 연결이 5초 안에 서지 못하면 일반 재연결」) · 재접속 횟수 ≥ 기대의 95%.
// 세션 이름표(학생 · 학원 · 관제)는 시나리오마다 고정하고 시작 전에 구독 수를 한 줄로 찍는다(Ruling 921 F) — scenario7 과 같은 방식.
// 서버는 SUBSCRIBE 의 receipt 에 RECEIPT 를 돌려주지 않으므로(실측 0건) "구독이 섰다" 는 그 채널의 첫 방송을 받은 것으로 센다(뒷배경 위치 방송이 2초마다 모든 채널에 닿는다).
// 그래서 구독 복원 시간에는 방송 주기(최대 2초)가 들어 있고, 구독 복원 = 공백(첫 메시지까지)이다.
//
// 환경변수: STORM_TOKENS(mint_tokens.py 출력 · STAFF_SESSIONS · ADMIN_SESSIONS 필요) · START_AT_MS(재접속 일정 시작 · epoch ms) · RECONNECT_SEC(재접속을 거는 시간)
//          · TOTAL_STUDENTS(1900) · TOTAL_STAFF(90) · TOTAL_ADMIN(10) · RATE_FACTOR(1) · TOKEN_PERIOD_SEC(900) · STORM_RAMP_SEC(60) · API_PORT(6571)
import http from 'k6/http';
import ws from 'k6/ws';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { scenario } from 'k6/execution';
import { BASE_URL, WS_URL } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const HEARTBEAT_MS = 10000; // 실제 앱 · 웹의 STOMP 하트비트 간격 — 이것 없이는 서버가 60초 무수신 세션을 닫는다(API_SPEC §7.2)
const num = (name, fallback) => (__ENV[name] === undefined ? fallback : Number(__ENV[name]));
const N = { student: num('TOTAL_STUDENTS', 1900), staff: num('TOTAL_STAFF', 90), admin: num('TOTAL_ADMIN', 10) };
const TOTAL = N.student + N.staff + N.admin;
const FACTOR = num('RATE_FACTOR', 1);
const PERIOD_MS = (num('TOKEN_PERIOD_SEC', 900) / FACTOR) * 1000; // 세션 하나의 재접속 주기
const RAMP_SEC = num('STORM_RAMP_SEC', 60);
const START_AT = num('START_AT_MS', 0);
const RECONNECT_SEC = num('RECONNECT_SEC', 600);
const END_AT = START_AT + RECONNECT_SEC * 1000;
const API_PORT = num('API_PORT', 6571);
const RESUBSCRIBE_LIMIT_MS = 5000;
const EXPECTED_CYCLES = (TOTAL * RECONNECT_SEC * 1000) / PERIOD_MS; // 세션 시작 위치가 고르게 흩어져 있으므로 구간 길이 ÷ 주기 × 세션 수
const EXPECTED_RATE = TOTAL / (PERIOD_MS / 1000);

const tokens = JSON.parse(open(__ENV.STORM_TOKENS));
const parents = new SharedArray('l6_parents', () => tokens.parents);
const staffSessions = new SharedArray('l6_staff', () => tokens.staff_sessions);
const adminSessions = new SharedArray('l6_admin', () => tokens.admin_sessions);

const KINDS = ['student', 'staff', 'admin'];
const subscribed = {};
for (const k of KINDS) subscribed[k] = new Counter(`subscribed_${k}`);
// REST 요청은 `Connection: close` — 운영은 nginx 가 톰캣과 HTTP/1.0 으로 말해 REST 한 건마다 연결이 닫힌다(scenario7 머리 주석).
const reconnectCycles = new Counter('reconnect_cycles');
const reissueMs = new Trend('reissue_ms', true);
const reissueFailures = new Counter('reissue_failures');
const initialReissueFailures = new Counter('initial_reissue_failures');
const resubscribeMs = new Trend('resubscribe_ms', true);
const reconnectGapMs = new Trend('reconnect_gap_ms', true);
const overLimit = new Counter('resubscribe_over_5s');
const noResubscribe = new Counter('reconnect_without_resubscribe');
const snapshotMs = new Trend('snapshot_ms', true);
const snapshotFailures = new Counter('snapshot_failures');
const errorFrames = new Counter('error_frames');
const precheckFailed = new Counter('precheck_failed');

const sessionScenario = (exec, vus) => ({ executor: 'ramping-vus', exec, startVUs: 0, gracefulRampDown: '5s',
    stages: [{ duration: `${RAMP_SEC}s`, target: vus }, { duration: `${Math.ceil((END_AT - Date.now()) / 1000) + 30}s`, target: vus }] });
export const options = {
    // REST 연결을 VU 마다 붙들어 두지 않는다 — 운영은 nginx 가 클라이언트의 REST 를 작은 업스트림 연결 묶음으로 모아 톰캣에 보내므로(톰캣 동시 연결 상한 4,000 은 WebSocket 위주로 찬다),
    // k6 가 클라이언트 2,000개의 REST 연결을 각자 유지하면 상한에 닿는 것이 시험 방식 때문에 앞당겨진다.
    noVUConnectionReuse: true,
    scenarios: {
        student_sessions: sessionScenario('studentSession', N.student),
        staff_sessions: sessionScenario('staffSession', N.staff),
        admin_sessions: sessionScenario('adminSession', N.admin),
        precheck: { executor: 'per-vu-iterations', exec: 'precheck', vus: 1, iterations: 1, maxDuration: '10m' },
    },
    thresholds: {
        reissue_failures: ['count==0'],
        reconnect_without_resubscribe: ['count==0'],
        resubscribe_over_5s: ['count==0'],
        reconnect_cycles: [`count>=${Math.floor(EXPECTED_CYCLES * 0.95)}`],
        precheck_failed: ['count==0'],
        // 스냅샷 REST 의 응답 시간 · 실패율을 요약에 남기기 위한 하위 지표 임계(통과 · 미통과를 가르는 값이 아니다)
        'http_req_duration{name:snapshot}': ['max>=0'],
        'http_req_failed{name:snapshot}': ['rate>=0'],
        'http_req_duration{name:refresh}': ['max>=0'],
        'http_req_failed{name:refresh}': ['rate>=0'],
    },
};

// 세션 하나 — session.slot(0..종류별 수-1)이 시작 위치를 정한다. 종류가 섞인 TOTAL 개를 한 줄로 세워 흩는다(종류별 오프셋이 겹치지 않게 base 를 더한다).
function lifecycle(kind, base, slot, session, destination, snapshotPath) {
    if (Date.now() >= END_AT) return; // 시험이 끝난 뒤 VU 가 새 반복을 시작해도 재발급 · 세션을 열지 않는다
    errorFrames.add(0); // 0건이어도 요약에 지표가 남게 한다(없음과 0건을 가르기 위해)
    let token = session.token; let refresh = session.refresh;
    // 시작할 때 한 번 재발급해 접근 토큰 나이를 0 으로 맞춘다 — 발급 뒤 시험 시작까지의 시간이 길어 첫 재접속 전에 토큰이 만료되는 것을 막는다(재접속 일정 밖의 요청이다).
    // 실제 클라이언트처럼 실패하면 다시 시도한다(최대 5번, 1초 간격). 이 첫 재발급의 실패는 재접속 일정 밖이라 initial_reissue_failures 로 따로 센다.
    for (let i = 0; i < 5; i++) {
        const first = http.post(`${BASE_URL}/auth/refresh`, JSON.stringify({ refresh_token: refresh }),
            { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' }, tags: { name: 'refresh_initial' } });
        if (first.status === 200) { const d = first.json().data; token = d.access_token; refresh = d.refresh_token || refresh; break; }
        initialReissueFailures.add(1);
        sleep(1);
    }
    const offsetMs = ((base + slot) / TOTAL) * PERIOD_MS;
    let nextAt = START_AT + offsetMs;
    let closedAt = 0; // 계획된 재접속으로 닫은 시각 — 0 이면 첫 접속이다
    while (Date.now() < END_AT) {
        let ok = true;
        let resubscribedAt = 0;
        let gotSubscribe = false;
        const res = ws.connect(WS_URL, {}, function (socket) {
            socket.on('open', () => socket.send(connectFrame(token)));
        socket.setInterval(() => socket.send('\n'), HEARTBEAT_MS);
            socket.on('message', function (raw) {
                const now = Date.now();
                for (const frame of parseFrames(raw)) {
                    if (frame.command === 'CONNECTED') {
                        socket.send(subscribeFrame('sub-0', destination));
                    } else if (frame.command === 'MESSAGE' && !gotSubscribe) {
                        gotSubscribe = true;
                        if (closedAt === 0) {
                            subscribed[kind].add(1);
                        } else {
                            resubscribedAt = now;
                            resubscribeMs.add(now - closedAt);
                            reconnectGapMs.add(now - closedAt);
                            if (now - closedAt > RESUBSCRIBE_LIMIT_MS) overLimit.add(1);
                            // 비동기 — 동기로 부르면 응답이 느릴 때 이 VU 의 하트비트가 멈춰 서버가 세션을 끊는다(실제 앱 · 웹은 재구독과 REST 를 따로 돌린다).
                            http.asyncRequest('GET', `${BASE_URL}${snapshotPath}`, null, { headers: { Authorization: `Bearer ${token}`, Connection: 'close' }, tags: { name: 'snapshot' } }).then((snap) => {
                                snapshotMs.add(snap.timings.duration);
                                if (snap.status !== 200) snapshotFailures.add(1);
                            });
                        }
                    } else if (frame.command === 'ERROR') {
                        errorFrames.add(1);
                    }
                }
            });
            // 재접속 시각까지 유지 → 재발급 → 닫기. 재발급이 실패하면 연결을 그대로 두고 다음 주기를 기다린다(재시도 없음 — Ruling 901).
            const wait = Math.max(Math.min(nextAt, END_AT) - Date.now(), 0);
            socket.setTimeout(function () {
                if (Date.now() >= END_AT) { socket.close(); return; } // 재접속 구간이 끝났다 — 구간 밖의 재접속은 세지 않는다
                const t0 = Date.now();
                const r = http.post(`${BASE_URL}/auth/refresh`, JSON.stringify({ refresh_token: refresh }),
                    { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' }, tags: { name: 'refresh' } });
                reissueMs.add(Date.now() - t0);
                if (r.status === 200) {
                    const d = r.json().data;
                    token = d.access_token; refresh = d.refresh_token || refresh;
                    closedAt = Date.now();
                    reconnectCycles.add(1);
                    nextAt += PERIOD_MS;
                    socket.close();
                } else {
                    reissueFailures.add(1);
                    nextAt += PERIOD_MS;
                    socket.setTimeout(() => socket.close(), Math.max(Math.min(nextAt, END_AT) - Date.now(), 0));
                }
            }, wait);
        });
        if (!res || res.status !== 101) ok = false;
        if (closedAt !== 0 && resubscribedAt === 0 && !gotSubscribe && Date.now() < END_AT - 3000) noResubscribe.add(1);
        if (!ok) sleep(0.25);
    }
}

export function studentSession() {
    const slot = scenario.iterationInTest; // 시나리오 안에서 0부터 매겨지는 반복 번호 — VU 번호(__VU)는 시나리오를 가로질러 매겨져 이름표가 어긋난다(Ruling 921 F)
    const parent = parents[slot % parents.length];
    lifecycle('student', 0, slot, parent, `/topic/students/${parent.studentId}/run`, `/students/${parent.studentId}/bus-position`);
}

export function staffSession() {
    const slot = scenario.iterationInTest;
    const s = staffSessions[slot % staffSessions.length];
    lifecycle('staff', N.student, slot, s, `/topic/academy/${s.academyId}/live`, '/staff/runs/live');
}

export function adminSession() {
    const slot = scenario.iterationInTest;
    lifecycle('admin', N.student + N.staff, slot, adminSessions[slot % adminSessions.length], '/topic/admin/live', '/admin/academies/1/runs/live');
}

// 첫 재접속 일정 3초 전 — 이름표별 구독 수를 k6 REST 에서 읽어 한 줄로 찍고 기대 수와 비교한다.
export function precheck() {
    sleep(Math.max(START_AT - 3000 - Date.now(), 0) / 1000);
    const read = (k) => {
        const r = http.get(`http://localhost:${API_PORT}/v1/metrics/subscribed_${k}`);
        return r.status === 200 ? r.json().data.attributes.sample.count : -1;
    };
    const got = { student: read('student'), staff: read('staff'), admin: read('admin') };
    const sum = got.student + got.staff + got.admin;
    const ok = got.student === N.student && got.staff === N.staff && got.admin === N.admin && sum === TOTAL;
    console.log(`PRECHECK subscribed = ${sum} (student ${got.student}/${N.student} · staff ${got.staff}/${N.staff} · admin ${got.admin}/${N.admin}) — 기대 재접속 ${EXPECTED_RATE.toFixed(2)}회/초 · ${Math.round(EXPECTED_CYCLES)}회 — ${ok ? '통과' : '실패'}`);
    if (!ok) precheckFailed.add(1);
}

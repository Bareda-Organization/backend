// 시나리오 7 — 전원 동시 재접속 (R59 L-2, Ruling 921 D). 연결 · 구독을 마친 세션 TOTAL(2,000)개를 같은 순간(CLOSE_AT_MS)에 닫고,
// 대기 없이 곧바로 다시 접속 · 재구독 · REST 스냅샷 1회(API_SPEC §7.2 재접속 직후 보충)를 하게 한다. 1대 운영의 배포 · 재기동이 모든 세션을 한꺼번에 끊는 최악 경우다.
// 실제 앱은 재접속 대기에 0~100% 무작위 폭(Ruling 899 G)이 있어 이보다 흩어진다. 뒷배경 부하(r3_mixed.sh 의 관리자 50 · 시청 570 · 위치 2초)는 이 스크립트 밖에서 겹친다.
//
// 세션 종류(이름표)는 시나리오마다 따로 고정한다 — 학생 채널 / 학원 채널 / 관제 채널. VU 번호로 가르지 않는다(단계 1 에서 VU 번호가 시나리오를 가로질러 매겨져
// 이름표가 어긋난 적이 있다). 서버(심플 브로커)는 SUBSCRIBE 의 receipt 에 RECEIPT 를 돌려주지 않으므로(실측 0건) "구독이 섰다" 는 그 채널의 방송을 1건 받은 것으로 센다 —
// 뒷배경의 위치 방송(2초 주기)이 학생 2,000명 · 학원 10곳 · 관제 채널 모두에 닿는다. 닫은 순간 → 새 연결의 첫 방송까지가 "재구독 시간" 이고 구독 요청 뒤 방송이 올 때까지의
// 최대 2초(방송 주기)가 포함된다.
//
// 닫기 전 확인(Ruling 921 F) — 닫기 3초 전에 확인 VU 가 k6 자신의 REST(localhost:API_PORT/v1/metrics)에서 이름표별 구독 수를 읽어 한 줄로 찍고,
// 기대 수(학생 · 학원 · 관제 · 합)와 다르면 `precheck_failed` 를 올린다(임계 count==0 이라 종료 코드 99).
//
// 임계 — 전원 재구독(`resubscribed_total >= TOTAL`) · 가장 늦은 세션도 닫은 뒤 60초 안(`resubscribe_ms max < 60000`) · 닫기 전 확인 통과.
// 기록만 하는 값 — 재구독 시간 분포 · ERROR 프레임 수 · 재접속 시도 실패 수 · REST 스냅샷 응답 시간. 커넥션 대기는 서버 지표(Hikari)로 회차 스크립트가 뽑는다.
//
// 환경변수: STORM_TOKENS(r46_mint_tokens.py 출력) · CLOSE_AT_MS(닫는 순간 · epoch ms) · STORM_STUDENTS(1900) · STORM_STAFF(90) · STORM_ADMIN(10)
//          · STORM_RAMP_SEC(연결 올리는 시간 · 60) · STORM_HOLD_AFTER_SEC(닫은 뒤 유지 · 90) · API_PORT(6570, k6 실행 때 --address localhost:6570)
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
const CLOSE_AT = num('CLOSE_AT_MS', 0);
const N = { student: num('STORM_STUDENTS', 1900), staff: num('STORM_STAFF', 90), admin: num('STORM_ADMIN', 10) };
const TOTAL = N.student + N.staff + N.admin;
const RAMP_SEC = num('STORM_RAMP_SEC', 60);
const HOLD_AFTER_SEC = num('STORM_HOLD_AFTER_SEC', 90);
const API_PORT = num('API_PORT', 6570);
const END_AT = CLOSE_AT + HOLD_AFTER_SEC * 1000;
const RESUBSCRIBE_LIMIT_MS = 60000;
const RETRY_FLOOR_MS = 250; // 대기 없는 재접속이 CPU 를 태우지 않을 최소 간격(백오프가 아니다 — 실패한 시도 사이에만 둔다)

const tokens = JSON.parse(open(__ENV.STORM_TOKENS));
const parents = new SharedArray('storm_parents', () => tokens.parents);
const staff = tokens.staff.filter((s) => s.runId !== null);

const KINDS = ['student', 'staff', 'admin'];
const subscribed = {}; const resubscribed = {};
for (const k of KINDS) { subscribed[k] = new Counter(`subscribed_${k}`); resubscribed[k] = new Counter(`resubscribed_${k}`); }
const resubscribedTotal = new Counter('resubscribed_total');
const resubscribeMs = new Trend('resubscribe_ms', true);
const errorFrames = new Counter('error_frames');
const errorFramesAfterClose = new Counter('error_frames_after_close');
const attemptFailures = new Counter('reconnect_attempt_failures');
// 재접속 시도 실패의 실제 이유 — 업그레이드 응답 상태(0 = 응답 없음 · 연결 오류) · 닫힘 코드 · 소켓 오류 문구 · 걸린 시간. 앞 FAIL_LOG_LIMIT 건은 로그에 한 줄씩 남긴다.
// REST 요청은 `Connection: close` 로 보낸다 — 운영은 nginx 가 업스트림(톰캣)과 HTTP/1.0 으로 말해(`proxy_http_version` 미지정 · keepalive 없음) REST 한 건마다 연결이 닫힌다.
// k6 가 톰캣에 직접 HTTP/1.1 로 붙으면 스냅샷 2,000건의 연결이 유휴 유지 시간(약 60초) 동안 톰캣 동시 연결 수(상한 4,000)를 채워 재접속이 막힌다(시험 방식이 만든 현상).
const failStatus = {};
for (const st of [0, 101, 400, 401, 403, 429, 500, 502, 503, 504]) failStatus[st] = new Counter(`reconnect_fail_status_${st}`);
const failStatusOther = new Counter('reconnect_fail_status_other');
const failNoSubscribe = new Counter('reconnect_fail_connected_no_message'); // 101 로 붙었지만 닫을 때까지 방송을 못 받은 시도
const FAIL_LOG_LIMIT = 60;
let failLogged = 0;
const initialAttemptFailures = new Counter('initial_attempt_failures');
const earlyClose = new Counter('closed_before_close_time');
const snapshotMs = new Trend('snapshot_ms', true);
const snapshotFailures = new Counter('snapshot_failures');
const snapshotsDone = new Counter('snapshots_done');
const precheckFailed = new Counter('precheck_failed');

const sessionScenario = (exec, vus) => ({ executor: 'ramping-vus', exec, startVUs: 0, gracefulRampDown: '5s',
    stages: [{ duration: `${RAMP_SEC}s`, target: vus }, { duration: `${Math.ceil((END_AT - Date.now()) / 1000) + 20}s`, target: vus }] });
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
        resubscribed_total: [`count>=${TOTAL}`],
        resubscribe_ms: [`max<${RESUBSCRIBE_LIMIT_MS}`],
        precheck_failed: ['count==0'],
        'http_req_duration{name:snapshot}': ['max>=0'],
        'http_req_failed{name:snapshot}': ['rate>=0'],
    },
};

// (세션 번호는 scenario.iterationInTest — 시나리오 안에서 0부터 매겨진다. VU 번호는 시나리오를 가로질러 매겨져 쓰지 않는다.)
// 세션 한 개의 일생 — 접속 · 구독 → 닫는 순간까지 유지 → 닫히면 곧바로 재접속 · 재구독 · 스냅샷 → 끝까지 유지.
function lifecycle(kind, token, destination, snapshotPath, who) {
    if (Date.now() >= END_AT) return; // 시험이 끝난 뒤 VU 가 새 반복을 시작해도 세션을 열지 않는다
    // 첫 접속은 실제 클라이언트처럼 실패하면 다시 시도한다(올리는 단계의 실패는 시험 대상이 아니다 — 시도 실패 수는 initial_attempt_failures 로 남긴다).
    // 닫기 3초 전까지 구독을 못 세운 세션은 닫기 전 확인에서 걸린다.
    while (Date.now() < CLOSE_AT - 4000) {
        const ready = openSession(kind, token, destination, null, CLOSE_AT, true);
        if (ready) break;
        initialAttemptFailures.add(1);
        sleep(1);
    }
    if (Date.now() < CLOSE_AT - 500) { earlyClose.add(1); sleep(Math.max(CLOSE_AT - Date.now(), 0) / 1000); } // 닫는 순간 전에 서버가 끊은 세션 — 센 뒤 끊겼다는 표시(재접속은 닫는 순간부터)
    // 닫힌 뒤 곧바로 — 대기 없이 재접속한다. 실패한 시도는 RETRY_FLOOR_MS 만 쉬고 다시 시도한다.
    while (Date.now() < END_AT) {
        const ok = openSession(kind, token, destination, snapshotPath, END_AT);
        if (ok) return;
        attemptFailures.add(1);
        sleep(RETRY_FLOOR_MS / 1000);
    }
    console.log(`NEVER_RESUBSCRIBED kind=${kind} who=${who} destination=${destination}`); // 시험이 끝날 때까지 재구독을 못 한 세션 — 목록으로 남긴다
}

// 접속해 구독하고 untilMs 까지 붙들고 있다. 닫는 순간에 도달해 정상 종료하거나(첫 접속) 구독까지 마쳤으면 true.
function openSession(kind, token, destination, snapshotPath, untilMs, requireSubscribed = false) {
    const phase2 = snapshotPath !== null;
    let subscribedOk = false;
    const attemptStart = Date.now();
    let closeCode = null; let errText = null; let connectedAt = null;
    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('close', (code) => { closeCode = code; });
        socket.on('error', (e) => { errText = String(e && e.error ? e.error() : e); });
        socket.on('open', () => socket.send(connectFrame(token)));
        socket.setInterval(() => socket.send('\n'), HEARTBEAT_MS);
        socket.on('message', function (raw) {
            for (const frame of parseFrames(raw)) {
                if (frame.command === 'CONNECTED') {
                    connectedAt = Date.now();
                    socket.send(subscribeFrame('sub-0', destination));
                } else if (frame.command === 'MESSAGE' && !subscribedOk) {
                    subscribedOk = true;
                    if (!phase2) {
                        subscribed[kind].add(1);
                    } else {
                        resubscribeMs.add(Date.now() - CLOSE_AT);
                        resubscribed[kind].add(1);
                        resubscribedTotal.add(1);
                        // REST 스냅샷은 소켓 처리를 막지 않게 비동기로 보낸다 — 실제 앱 · 웹도 재구독과 REST 를 따로 돌린다. 동기로 부르면 응답이 느릴 때(최대 60초)
                        // 이 VU 의 하트비트가 멈춰 서버가 세션을 끊고, 그 끊김이 서버 탓처럼 기록된다.
                        http.asyncRequest('GET', `${BASE_URL}${snapshotPath}`, null, { headers: { Authorization: `Bearer ${token}`, Connection: 'close' }, tags: { name: 'snapshot' } }).then((snap) => {
                            snapshotsDone.add(1);
                            snapshotMs.add(snap.timings.duration);
                            if (snap.status !== 200) snapshotFailures.add(1);
                        });
                    }
                } else if (frame.command === 'ERROR') {
                    errorFrames.add(1);
                    if (phase2) errorFramesAfterClose.add(1);
                }
            }
        });
        socket.setTimeout(() => socket.close(), Math.max(untilMs - Date.now(), 0) + 1);
    });
    if (phase2 && !subscribedOk) {
        const st = res ? res.status : 0;
        (failStatus[st] || failStatusOther).add(1);
        if (st === 101) failNoSubscribe.add(1);
        if (failLogged++ < FAIL_LOG_LIMIT) {
            console.log(`RECONNECT_FAIL kind=${kind} status=${st} err=${errText} close=${closeCode} elapsed_ms=${Date.now() - attemptStart} connected=${connectedAt !== null} since_close_ms=${Date.now() - CLOSE_AT}`);
        }
    }
    if (requireSubscribed) return subscribedOk; // 첫 접속: 끝까지(닫는 순간까지) 붙어 있었으면 구독은 선 것이다
    return phase2 ? subscribedOk : true;
}

export function studentSession() {
    const parent = parents[scenario.iterationInTest % parents.length];
    lifecycle('student', parent.token, `/topic/students/${parent.studentId}/run`, `/students/${parent.studentId}/bus-position`, `student:${parent.studentId}`);
}

export function staffSession() {
    const tab = staff[scenario.iterationInTest % staff.length];
    lifecycle('staff', tab.token, `/topic/academy/${tab.academyId}/live`, '/staff/runs/live', `academy:${tab.academyId}`);
}

export function adminSession() {
    lifecycle('admin', tokens.admin, '/topic/admin/live', '/admin/academies/1/runs/live', 'admin');
}

// 닫기 3초 전 — 이름표별 구독 수를 k6 REST 에서 읽어 한 줄로 찍고 기대 수와 비교한다.
export function precheck() {
    sleep(Math.max(CLOSE_AT - 3000 - Date.now(), 0) / 1000);
    const read = (k) => {
        const r = http.get(`http://localhost:${API_PORT}/v1/metrics/subscribed_${k}`);
        return r.status === 200 ? r.json().data.attributes.sample.count : -1;
    };
    const got = { student: read('student'), staff: read('staff'), admin: read('admin') };
    const sum = got.student + got.staff + got.admin;
    const ok = got.student === N.student && got.staff === N.staff && got.admin === N.admin && sum === TOTAL;
    console.log(`PRECHECK subscribed = ${sum} (student ${got.student}/${N.student} · staff ${got.staff}/${N.staff} · admin ${got.admin}/${N.admin}) — ${ok ? '통과' : '실패'}`);
    if (!ok) precheckFailed.add(1);
}

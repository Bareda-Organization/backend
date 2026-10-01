// 시나리오 5 — 폴링과 시청 세션 (R46-LOAD, 조사 D #7·#9·"다음 부하 측정 1·3").
// 지금까지의 시나리오는 위치 수신·WS·승인 상세뿐이라 열린 앱·웹이 주기적으로 치는 REST 가 시험에 없었다.
// 이 스크립트가 그 REST 와, 실제 구독 분포를 따르는 WS 시청 세션을 더한다. 값은 전부 환경변수이고 0 이면 그 갈래를 끈다.
//
// ① 학부모 앱 홈 폴링 — 앱 코드에서 센 요청 묶음(`frontend/apps/parent-app`):
//    홈의 `VisiblePoller`(90초)가 `runsForStudentProvider`·`changeRequestsProvider` 를 무효화 → GET /students/{id}/runs ·
//    GET /students/{id}/change-requests, 탭 막대 `AppShell` 의 `VisiblePoller`(90초)가 알림 목록을 다시 받음 → GET /notifications.
//    한 번에 3요청이고 동시에 나간다. 열린 앱 POLL_PARENT_APPS 대가 각자 90초마다 치는 총량을 도착률 하나로 모사한다
//    (constant-arrival-rate = 앱 수 ÷ 주기). VU 를 앱 수만큼 만들지 않아 k6 자신이 서버와 CPU 를 다투는 것을 줄인다.
// ② 관계자 웹 폴링 — `usePolling`(응답을 받은 뒤 다음 요청을 예약 · 7초): 대시보드 탭은 GET /staff/runs/live + /staff/dashboard,
//    금일 운행 탭은 GET /staff/dashboard + /staff/runs/{id}/roster + /staff/runs/live. 모든 관계자 화면의 공통 폴링 둘도 탭마다 돈다 —
//    비상 목록(5초) GET /staff/emergencies?status=open, 승인 대기(30초) GET /staff/signup-requests + /staff/approvals.
//    탭 하나 = VU 하나(탭 안의 타이머마다 VU 하나). 응답 뒤 예약은 `http 호출 → sleep(주기)` 로 모사한다.
// ③ 시청 세션 — 학부모 WS_VIEWERS 명이 자기 학생 채널(/topic/students/{id}/run), 관계자 WS_STAFF 명이 학원 채널
//    (/topic/academy/{id}/live), 메인 관리자 WS_ADMIN 명이 /topic/admin/live 를 구독한다. 09-09 R3 은 세션 전원을 관제 채널에 몰았다.
//
// 토큰은 r46_mint_tokens.py 가 회차 직전에 발급한다(로그인 CPU 를 측정 구간 밖에 둔다).
//
// 실행: k6 run -e POLL_TOKENS=<토큰.json> -e SCENARIO5_DURATION_SEC=60 scenario5_polling.js
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { BASE_URL, WS_URL } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const num = (name, fallback) => (__ENV[name] === undefined ? fallback : Number(__ENV[name]));

const PARENT_APPS = num('POLL_PARENT_APPS', 570);
const PARENT_INTERVAL_SEC = num('POLL_PARENT_INTERVAL_SEC', 90);
// ADMIN_USERS — 관계자 웹 동시 사용자 수(Ruling 484: 통과 기준 50). 켜면(>0) 아래 갈래 수를 이 하나로 정한다:
//   메인 관리자 = 10%(50명이면 5) — 전체 관제(ADMIN_LIVE WS) + 폴링, 학원 관계자 = 나머지(45) — 절반은 대시보드 탭, 절반은 금일 운행 탭
//   (각자 비상 5초·승인 30초 공통 폴링과 학원 채널 WS 를 가진다). 끄면(0) 아래 개별 변수를 쓴다(R3 B·C 회차의 옛 가정 12명).
const ADMIN_USERS = num('ADMIN_USERS', 0);
const MAIN_ADMINS = ADMIN_USERS > 0 ? Math.max(1, Math.round(ADMIN_USERS / 10)) : 0;
const STAFF_USERS = ADMIN_USERS - MAIN_ADMINS;
const STAFF_DASH_TABS = ADMIN_USERS > 0 ? Math.ceil(STAFF_USERS / 2) : num('POLL_STAFF_DASH_TABS', 11);
const STAFF_TODAY_TABS = ADMIN_USERS > 0 ? Math.floor(STAFF_USERS / 2) : num('POLL_STAFF_TODAY_TABS', 10);
const STAFF_INTERVAL_SEC = num('POLL_STAFF_INTERVAL_SEC', 7);
const EMERGENCY_INTERVAL_SEC = num('POLL_EMERGENCY_INTERVAL_SEC', 5);
const APPROVAL_INTERVAL_SEC = num('POLL_APPROVAL_INTERVAL_SEC', 30);
const WS_VIEWERS = num('WS_VIEWERS', 0);
const WS_STAFF = ADMIN_USERS > 0 ? STAFF_USERS : num('WS_STAFF', 0);
const WS_ADMIN = ADMIN_USERS > 0 ? MAIN_ADMINS : num('WS_ADMIN', 0);
const RAMP_SEC = num('SCENARIO5_RAMP_SEC', 60);
const DURATION_SEC = num('SCENARIO5_DURATION_SEC', 250);

const tokens = JSON.parse(open(__ENV.POLL_TOKENS));
const parents = new SharedArray('r46_parents', () => tokens.parents);
const staff = tokens.staff;
const staffWithRun = staff.filter((s) => s.runId !== null);
// 위치용 회차 100대가 전부 학원 1(staffA)에 있다. 실제는 학원 10곳이 각 10대씩이라, 학원 1 에 탭의 1/9 만 두면 학원 1 응답
// (회차당 약 28KB — 100대면 2.8MB)의 총량이 "45탭 × 10대" 와 같아지고 학원 채널 방송 수신 건수도 같아진다.
const staffA = staff.filter((s) => s.runId === null);
const staffLoadcap = staffWithRun;
const pickStaff = () => (__VU % 9 === 0 && staffA.length > 0 ? staffA[0] : staffLoadcap[__VU % staffLoadcap.length]);

// 요청 종류별 서버 응답 시간 — 지연 분포를 종류마다 따로 본다(명단 조회만 감사 기록을 남기는 식으로 비용이 다르다).
const trend = (name) => new Trend(name, true);
const parentRunsMs = trend('poll_parent_runs_ms');
const parentChangeMs = trend('poll_parent_change_requests_ms');
const parentNotifMs = trend('poll_parent_notifications_ms');
const staffDashboardMs = trend('poll_staff_dashboard_ms');
const staffLiveMs = trend('poll_staff_live_ms');
const staffRosterMs = trend('poll_staff_roster_ms');
const staffEmergencyMs = trend('poll_staff_emergency_ms');
const staffSignupMs = trend('poll_staff_signup_ms');
const staffApprovalsMs = trend('poll_staff_approvals_ms');
// 나간 요청 수와 실패 수(200 이 아닌 응답) — 시험이 실제로 요청을 냈는지를 세는 지표.
const parentRequests = new Counter('poll_parent_requests');
const parentFailures = new Counter('poll_parent_failures');
const staffRequests = new Counter('poll_staff_requests');
const staffFailures = new Counter('poll_staff_failures');
// 시청 세션이 받은 방송과 지연(서버 occurred_at → k6 수신 시각, 같은 호스트라 시계 보정 없음).
const viewerReceived = new Counter('viewer_ws_messages_received');
const viewerLatencyMs = trend('viewer_ws_latency_ms');
const controlReceived = new Counter('control_ws_messages_received');
const controlLatencyMs = trend('control_ws_latency_ms');
const wsConnectFailures = new Counter('ws_connect_failures');
const adminMonitorMs = trend('poll_admin_live_ms');
const adminEmergencyMs = trend('poll_admin_emergency_ms');
const adminAttentionMs = trend('poll_admin_attention_ms');

const scenarios = {};
if (PARENT_APPS > 0) {
    scenarios.parent_home_poll = {
        executor: 'constant-arrival-rate',
        exec: 'parentHome',
        rate: Math.max(1, Math.round((PARENT_APPS * 1000) / PARENT_INTERVAL_SEC)),
        timeUnit: '1000s', // 소수 도착률을 정수로 — 570 ÷ 90 = 6.33/s = 6,333/1000s
        duration: `${DURATION_SEC}s`,
        preAllocatedVUs: 30,
        maxVUs: 200,
    };
}
const loopScenario = (exec, vus) => ({ executor: 'constant-vus', exec, vus, duration: `${DURATION_SEC}s` });
if (STAFF_DASH_TABS > 0) scenarios.staff_dashboard_tab = loopScenario('staffDashboardTab', STAFF_DASH_TABS);
if (STAFF_TODAY_TABS > 0) scenarios.staff_today_run_tab = loopScenario('staffTodayRunTab', STAFF_TODAY_TABS);
if (STAFF_DASH_TABS + STAFF_TODAY_TABS > 0) {
    scenarios.staff_emergency_poll = loopScenario('staffEmergencyPoll', STAFF_DASH_TABS + STAFF_TODAY_TABS);
    scenarios.staff_approval_poll = loopScenario('staffApprovalPoll', STAFF_DASH_TABS + STAFF_TODAY_TABS);
}
if (MAIN_ADMINS > 0) {
    scenarios.admin_monitor_tab = loopScenario('adminMonitorTab', MAIN_ADMINS);
    scenarios.admin_emergency_poll = loopScenario('adminEmergencyPoll', MAIN_ADMINS);
}
const wsScenario = (exec, target) => ({
    executor: 'ramping-vus',
    exec,
    startVUs: 0,
    stages: [
        { duration: `${RAMP_SEC}s`, target },
        { duration: `${Math.max(DURATION_SEC - RAMP_SEC, 1)}s`, target },
    ],
    gracefulRampDown: '5s',
});
if (WS_VIEWERS > 0) scenarios.viewer_sessions = wsScenario('viewerSession', WS_VIEWERS);
if (WS_STAFF > 0) scenarios.staff_sessions = wsScenario('staffSession', WS_STAFF);
if (WS_ADMIN > 0) scenarios.admin_sessions = wsScenario('adminSession', WS_ADMIN);

// 관측만 한다 — 임계를 걸지 않는다(포화 지점을 찾는 시험이라 임계 위반으로 중단되면 안 된다).
export const options = { scenarios };

const authHeaders = (token) => ({ headers: { Authorization: `Bearer ${token}` } });
const get = (path, token) => ({ method: 'GET', url: `${BASE_URL}${path}`, params: authHeaders(token) });

function record(res, trendMetric, requests, failures) {
    requests.add(1);
    trendMetric.add(res.timings.duration);
    if (res.status !== 200) failures.add(1);
}

// 열린 학부모 앱 하나의 90초 틱 — 무작위 학부모 한 명의 3요청을 동시에 낸다.
export function parentHome() {
    const parent = parents[Math.floor(Math.random() * parents.length)];
    const [runs, change, notif] = http.batch([
        get(`/students/${parent.studentId}/runs`, parent.token),
        get(`/students/${parent.studentId}/change-requests`, parent.token),
        get('/notifications', parent.token),
    ]);
    record(runs, parentRunsMs, parentRequests, parentFailures);
    record(change, parentChangeMs, parentRequests, parentFailures);
    record(notif, parentNotifMs, parentRequests, parentFailures);
}

// 탭이 같은 순간에 나란히 치지 않게 첫 요청을 주기 안에서 무작위로 늦춘다 — 실제 탭은 각자 열린 시각이 다르다.
const stagger = (intervalSec) => sleep(Math.random() * intervalSec);
const tabOf = () => pickStaff();

// 대시보드 탭 — 응답을 받은 뒤 7초를 기다려 다음 요청(`usePolling`).
export function staffDashboardTab() {
    const tab = tabOf();
    if (__ITER === 0) stagger(STAFF_INTERVAL_SEC);
    const [live, dashboard] = http.batch([get('/staff/runs/live', tab.token), get('/staff/dashboard', tab.token)]);
    record(live, staffLiveMs, staffRequests, staffFailures);
    record(dashboard, staffDashboardMs, staffRequests, staffFailures);
    sleep(STAFF_INTERVAL_SEC);
}

// 금일 운행 탭 — 회차 목록·명단(호출마다 감사 기록 대상)·실시간 위치를 함께 받는다.
export function staffTodayRunTab() {
    // 금일 운행 탭은 회차 하나를 고정해 열어 둔다 — 회차가 100개인 학원 1 은 고를 수 없어 R0 학원에서만 고른다.
    const tab = staffLoadcap[__VU % staffLoadcap.length];
    if (__ITER === 0) stagger(STAFF_INTERVAL_SEC);
    const [dashboard, roster, live] = http.batch([
        get('/staff/dashboard', tab.token),
        get(`/staff/runs/${tab.runId}/roster`, tab.token),
        get('/staff/runs/live', tab.token),
    ]);
    record(dashboard, staffDashboardMs, staffRequests, staffFailures);
    record(roster, staffRosterMs, staffRequests, staffFailures);
    record(live, staffLiveMs, staffRequests, staffFailures);
    sleep(STAFF_INTERVAL_SEC);
}

// 관계자 전 화면 공통 — 비상 목록(5초).
export function staffEmergencyPoll() {
    const tab = tabOf();
    if (__ITER === 0) stagger(EMERGENCY_INTERVAL_SEC);
    record(http.get(`${BASE_URL}/staff/emergencies?status=open`, authHeaders(tab.token)), staffEmergencyMs,
        staffRequests, staffFailures);
    sleep(EMERGENCY_INTERVAL_SEC);
}

// 관계자 전 화면 공통 — 승인 대기 건수(30초).
export function staffApprovalPoll() {
    const tab = tabOf();
    if (__ITER === 0) stagger(APPROVAL_INTERVAL_SEC);
    const [signup, approvals] = http.batch([
        get('/staff/signup-requests?status=pending&page=0&size=1', tab.token),
        get('/staff/approvals?status=pending&page=0&size=20', tab.token),
    ]);
    record(signup, staffSignupMs, staffRequests, staffFailures);
    record(approvals, staffApprovalsMs, staffRequests, staffFailures);
    sleep(APPROVAL_INTERVAL_SEC);
}

// 메인 관리자 전체 관제 화면 — 선택한 학원의 실시간 회차를 7초마다(응답 뒤 예약), 전 학원 비상 요약·지연 집계를 30초마다
// (`MonitoringPage` usePolling). 학원은 탭마다 다르게 고른다 — 5개 중 1개가 학원 1(위치용 회차 100대)이다.
const ADMIN_VIEW_ACADEMIES = [1, ...tokens.staff.filter((x) => x.runId !== null).slice(0, 4).map((x) => x.academyId)];
export function adminMonitorTab() {
    const academyId = ADMIN_VIEW_ACADEMIES[__VU % ADMIN_VIEW_ACADEMIES.length];
    if (__ITER === 0) stagger(STAFF_INTERVAL_SEC);
    const requests = [get(`/admin/academies/${academyId}/runs/live`, tokens.admin)];
    if (__ITER % 4 === 0) requests.push(get('/admin/emergencies?status=open', tokens.admin), get('/admin/runs/attention', tokens.admin));
    const [live, emergency, attention] = http.batch(requests);
    record(live, adminMonitorMs, staffRequests, staffFailures);
    if (emergency) record(emergency, adminEmergencyMs, staffRequests, staffFailures);
    if (attention) record(attention, adminAttentionMs, staffRequests, staffFailures);
    sleep(STAFF_INTERVAL_SEC);
}

// 관리자 화면 전체 공통 비상 알림(5초) — 관계자 웹과 같은 Provider 가 `/admin/emergencies` 로 돈다.
export function adminEmergencyPoll() {
    if (__ITER === 0) stagger(EMERGENCY_INTERVAL_SEC);
    record(http.get(`${BASE_URL}/admin/emergencies?status=open`, authHeaders(tokens.admin)), adminEmergencyMs,
        staffRequests, staffFailures);
    sleep(EMERGENCY_INTERVAL_SEC);
}

// 세션 하나 — 토큰으로 STOMP 연결해 목적지 하나를 구독하고 시나리오가 끝날 때까지 받는다.
function holdSession(token, destination, receivedCounter, latencyTrend) {
    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', () => socket.send(connectFrame(token)));
        socket.on('message', function (raw) {
            const now = Date.now();
            for (const frame of parseFrames(raw)) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-0', destination));
                } else if (frame.command === 'ERROR') {
                    wsConnectFailures.add(1);
                } else if (frame.command === 'MESSAGE') {
                    receivedCounter.add(1);
                    try {
                        const body = JSON.parse(frame.body);
                        if (body && body.occurred_at) latencyTrend.add(now - new Date(body.occurred_at).getTime());
                    } catch (e) {
                        // 본문이 JSON 이 아니면 지연만 못 잰다 — 수신 수는 이미 셌다.
                    }
                }
            }
        });
        socket.on('error', () => wsConnectFailures.add(1));
        socket.setTimeout(() => socket.close(), (DURATION_SEC + 5) * 1000);
    });
    check(res, { 'ws 연결 성공(101)': (r) => r && r.status === 101 });
    if (!res || res.status !== 101) wsConnectFailures.add(1);
    sleep(1);
}

export function viewerSession() {
    const parent = parents[(__VU - 1) % parents.length];
    holdSession(parent.token, `/topic/students/${parent.studentId}/run`, viewerReceived, viewerLatencyMs);
}

export function staffSession() {
    const tab = tabOf();
    holdSession(tab.token, `/topic/academy/${tab.academyId}/live`, controlReceived, controlLatencyMs);
}

export function adminSession() {
    holdSession(tokens.admin, '/topic/admin/live', controlReceived, controlLatencyMs);
}

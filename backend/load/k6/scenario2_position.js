// 시나리오 2 — "위치 수신 처리량" (IMPLEMENTATION_PLAN §5.2 #2).
// 재는 것: 위치 수신 처리 지연(POST /runs/{runId}/position 응답 시간) · WS 팬아웃 지연
// (송신 시각 → /topic/academy/{academyId}/live 로 그 위치가 방송되기까지). §5.3 은 이 시나리오에
// 수치 임계를 정하지 않았다(NFR-02·03 은 반영시간·송신주기 "규정"이지 이 시험의 pass/fail 기준이
// 아니다) — 그래서 threshold 는 실패율 0 하나만 걸고, 지연 분포는 summary 로 관측만 한다.
//
// ⚠ F5 S2 목표 2 정정(2026-09-05, 2회) — 1차 정정은 매니저 채널이 position 을 안 받는다는 점만
// 고쳐 academy live 로 구독을 옮겼으나, 그 구독 주체를 여전히 기사(driver) 계정으로 뒀다.
// StompAuthChannelInterceptor#authorizeAcademyChannel 은 academy live 를 role=STAFF 만 구독하게
// 허용한다(API_SPEC §7 권한 열 "해당 학원 관계자") — driver 계정이 구독을 시도하면 SUBSCRIBE 가
// FORBIDDEN 으로 막혀 소켓이 4403 으로 즉시 닫힌다(1차 정정 후 N=20 실측 — ws_session_duration
// 평균 13ms, ws_connect_failures=40=VU 수×2, position_post 자체는 204 로 성공). 그래서 "올리는
// 사람"과 "받는 사람"을 분리한다 — 위치는 기사 계정으로 REST POST 하고, 방송 관측은 STAFF 계정
// (STAFF_OBSERVER_LOGIN_ID, academy 1 고정 시드 계정)으로 별도 WS 커넥션을 열어 구독한다.
//
// VU 하나 = 시드된 회차(run) 하나 = 그 회차에 배치된 기사 하나. 그 VU 가 기사 토큰으로 위치를
// 올리고, 동시에 staffA 토큰으로 연 별도 WS 커넥션이 academy live 채널을 구독한다 — 이 채널은
// 학원 소속 모든 회차의 position 을 함께 받으므로(시드가 academy_id 를 고정값 하나로 몰아 VU
// 전원이 같은 채널을 공유), 봉투의 run_id(WebSocketEnvelope, 전역 SNAKE_CASE 전략이라 JSON 키는
// run_id)로 자기 회차분만 걸러야 한다 — "도착한 첫 MESSAGE = 내 방송" 가정은 안전하지 않다(다른
// VU 의 position 도 같은 소켓에 섞여 들어온다). staffA 로 로그인하는 WS 커넥션이 VU 수만큼(N개)
// 동시에 열리는 것은 의도한 트레이드오프다 — 같은 계정의 다중 세션은 JWT 무상태 특성상 허용되고,
// 이 시나리오가 재는 것은 "그 채널의 팬아웃 지연"이지 "관측자 동시 접속 자체의 부하"가 아니다.
//
// 실행 (준비 먼저):
//   psql -v n=$N -f sql/scenario2_prep.sql -t -A -F',' | grep -v '^$' > k6/scenario2_runs.csv
//   k6 run -e SCENARIO2_CSV=./scenario2_runs.csv -e SCENARIO2_DURATION_SEC=60 \
//       -e SCENARIO2_INTERVAL_SEC=7 --vus $N --iterations $N scenario2_position.js
import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { login } from './lib/auth.js';
import { BASE_URL, WS_URL, SEED_PASSWORD, STAFF_OBSERVER_LOGIN_ID } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const runs = new SharedArray('scenario2_runs', function () {
    const path = __ENV.SCENARIO2_CSV || './scenario2_runs.csv';
    const csv = open(path);
    return csv
        .trim()
        .split('\n')
        .filter((line) => line.length > 0)
        .map((line) => {
            const [tag, loginId, runId, academyId] = line.split(',');
            return { tag, loginId, runId: Number(runId), academyId: Number(academyId) };
        });
});

export const wsFanoutLatencyMs = new Trend('ws_fanout_latency_ms', true);
export const positionPostDurationMs = new Trend('position_post_duration_ms', true);
export const positionPostFailures = new Counter('position_post_failures');
export const wsConnectFailures = new Counter('ws_connect_failures');
// F5 목표 표 row 24 문면("수신 ≥ 송신 × 0.8")을 직접 세는 카운터 — k6 내장 ws_msgs_sent/received 는
// STOMP 제어 프레임(CONNECT·SUBSCRIBE) 수와 전체 팬아웃 수신(다른 VU 분까지 포함)이라 이 시나리오의
// "내가 보낸 position 대비 내 회차 echo 수신" 의도와 단위가 다르다 — 앱 레벨로 직접 센다.
export const positionsSentTotal = new Counter('positions_sent_total');
export const positionEchoReceivedTotal = new Counter('position_echo_received_total');

export const options = {
    scenarios: {
        position_stream: {
            executor: 'per-vu-iterations',
            vus: runs.length,
            iterations: 1,
            maxDuration: '10m',
        },
    },
    thresholds: {
        // §5.3 은 이 시나리오에 수치 임계를 정하지 않았다 — 실패율만 0으로 강제해 회귀를 잡는다.
        position_post_failures: ['count==0'],
        ws_connect_failures: ['count==0'],
        // F5 S2 목표 2 — echo 수신이 송신의 80% 이상. k6 threshold 문법은 두 카운터를 직접 나눈 비교식을
        // 지원하지 않으므로, 실행 후 summary export(count 필드)를 스크립트 밖에서 나눠 판정한다 — 이
        // threshold 는 "0건은 무조건 결함"이라는 하한만 건다(송신이 있는데 echo 가 0이면 즉시 실패로 본다).
        position_echo_received_total: ['count>0'],
    },
};

export default function () {
    const idx = (__VU - 1) % runs.length;
    const target = runs[idx];
    // 올리는 사람(기사) — REST POST 에만 쓴다. WS 는 열지 않는다(위 헤더 주석 — academy live 는
    // STAFF 만 구독 가능).
    const driverToken = login(target.loginId, SEED_PASSWORD);
    // 받는 사람(STAFF 관측자) — academy live 구독 전용. 시드된 회차와 무관하게 academy 1 고정이라
    // target.academyId 를 그대로 써도 같은 값이다(현재 시드가 academy_id=1 단일 고정).
    const staffToken = login(STAFF_OBSERVER_LOGIN_ID, SEED_PASSWORD);

    const durationSec = Number(__ENV.SCENARIO2_DURATION_SEC || 60);
    // NFR-03(위치 송신 주기)을 그대로 쓰지 않는다 — 이 값은 그 규정을 재현하는 것이 아니라 부하
    // 시험의 반복 주기 파라미터다. 실제 송신 주기와 다르게 잡을 수 있으므로 README 에 근거를 적는다.
    const intervalSec = Number(__ENV.SCENARIO2_INTERVAL_SEC || 7);

    let lastSentAt = null;

    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', function () {
            socket.send(connectFrame(staffToken));
        });

        socket.on('message', function (raw) {
            const frames = parseFrames(raw);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-position', `/topic/academy/${target.academyId}/live`));
                    socket.setInterval(function () {
                        const now = Date.now();
                        const payload = JSON.stringify({
                            lat: 37.5 + Math.random() * 0.01,
                            lng: 127.0 + Math.random() * 0.01,
                            recorded_at: new Date(now).toISOString(),
                        });
                        lastSentAt = now;
                        const postRes = http.post(`${BASE_URL}/runs/${target.runId}/position`, payload, {
                            headers: { Authorization: `Bearer ${driverToken}`, 'Content-Type': 'application/json' },
                        });
                        positionPostDurationMs.add(postRes.timings.duration);
                        if (postRes.status !== 204) {
                            positionPostFailures.add(1);
                        } else {
                            positionsSentTotal.add(1);
                        }
                    }, intervalSec * 1000);
                } else if (frame.command === 'ERROR') {
                    wsConnectFailures.add(1);
                } else if (frame.command === 'MESSAGE' && lastSentAt !== null) {
                    // academy live 는 학원 소속 모든 회차의 position 을 함께 실어 보낸다 — 다른 VU 의
                    // 방송을 내 송신의 메아리로 잘못 세지 않도록 envelope 의 event·run_id 를 직접
                    // 확인한다(§7 정정, 위 헤더 주석). 못 읽는 형태거나 남의 회차면 무시하고 다음
                    // 프레임을 본다(래치는 계속 유지 — 내 회차 echo 가 나중에 와도 잡아야 한다).
                    let body;
                    try {
                        body = JSON.parse(frame.body);
                    } catch (e) {
                        body = null;
                    }
                    if (body && body.event === 'position' && body.run_id === target.runId) {
                        wsFanoutLatencyMs.add(Date.now() - lastSentAt);
                        positionEchoReceivedTotal.add(1);
                        lastSentAt = null;
                    }
                }
            }
        });

        socket.on('error', function () {
            wsConnectFailures.add(1);
        });

        socket.setTimeout(function () {
            socket.close();
        }, durationSec * 1000);
    });

    check(res, { 'ws 연결 성공(101)': (r) => r && r.status === 101 });
    if (!res || res.status !== 101) {
        wsConnectFailures.add(1);
    }
}

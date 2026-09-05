// 시나리오 3 — "관제 팬아웃" (IMPLEMENTATION_PLAN §5.2 #3).
// 재는 것: WS 발행 지연 · 동시 연결 수. 무너지는 지점: 인메모리 세션을 쓰는 인스턴스 1개의 세션
// 한계(ARCHITECTURE §9.5). §5.3 은 이 시나리오에도 수치 임계를 정하지 않았다 — 연결 실패율이
// 오르기 시작하는 VU 수를 관측해 report §3 에 "N=__ 에서 무너짐" 또는 "N=200 까지 미관측"으로 적는다.
//
// 메인 관리자(sysadmin, system_admin 역할)는 계정이 1개뿐이라 모든 VU 가 같은 access_token 을
// 재사용한다 — StompAuthChannelInterceptor 의 CONNECT 인증은 토큰 유효성만 보고 세션 유일성을
// 요구하지 않으므로(직접 코드 확인) 이 재사용이 유효하다. 로그인 자체가 병목이 되는 것을 막기
// 위해 setup() 에서 1회만 로그인한다.
//
// 실제 방송 트래픽이 있어야 팬아웃 지연을 잴 수 있다 — /topic/admin/live 로 오는 이벤트는 위치·
// 상태변경 등 실 운행에서 나온다. 이 스크립트 단독으로는 그 트래픽을 만들지 않으므로, 유의미한
// 지연 관측을 하려면 scenario2_position.js 를 동시에 돌려 위치 방송을 만들어야 한다(README 참고).
// 그 방송이 없으면 이 스크립트는 "연결 자체가 버티는가"만 재고, ws_message_latency_ms 는 표본이
// 0에 가까울 수 있다 — 그 경우 결과 표에 "동시 트래픽 없이 측정, 지연 표본 부재"로 적는다.
//
// 실행:
//   k6 run -e ADMIN_LOGIN_ID=sysadmin -e ADMIN_PASSWORD=password \
//       -e SCENARIO3_TARGET_VUS=200 -e SCENARIO3_HOLD_SEC=60 scenario3_admin_fanout.js
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { login } from './lib/auth.js';
import { WS_URL } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

export const wsMessageLatencyMs = new Trend('ws_message_latency_ms', true);
export const wsMessagesReceived = new Counter('ws_messages_received');
export const wsConnectFailures = new Counter('ws_connect_failures');
export const wsSubscribeFailures = new Counter('ws_subscribe_failures');

const TARGET_VUS = Number(__ENV.SCENARIO3_TARGET_VUS || 200);
const HOLD_SEC = Number(__ENV.SCENARIO3_HOLD_SEC || 60);
const RAMP_SEC = Number(__ENV.SCENARIO3_RAMP_SEC || 60);

export const options = {
    scenarios: {
        admin_subscribers: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: `${RAMP_SEC}s`, target: TARGET_VUS },
                { duration: `${HOLD_SEC}s`, target: TARGET_VUS },
                { duration: '10s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
    },
    // §5.3 은 이 시나리오에 수치 임계를 정하지 않았다 — 연결 실패는 관측 대상이지 pass/fail 기준이
    // 아니라서 threshold 를 걸지 않는다(걸면 "무너지는 지점을 찾는다"는 목적과 충돌해 시험이 조기
    // 중단된다).
};

export function setup() {
    const loginId = __ENV.ADMIN_LOGIN_ID || 'sysadmin';
    const password = __ENV.ADMIN_PASSWORD || 'password';
    const token = login(loginId, password);
    return { token };
}

export default function (data) {
    const token = data.token;

    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', function () {
            socket.send(connectFrame(token));
        });

        socket.on('message', function (raw) {
            const now = Date.now();
            const frames = parseFrames(raw);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-admin', '/topic/admin/live'));
                } else if (frame.command === 'ERROR') {
                    wsSubscribeFailures.add(1);
                } else if (frame.command === 'MESSAGE') {
                    wsMessagesReceived.add(1);
                    // 근사치 — occurred_at 은 서버 시계, now 는 이 k6 VU 의 로컬 시계다. 같은 호스트에서
                    // 백엔드·k6 를 돌리므로 NTP 보정을 별도로 하지 않는다(README 에 이 가정을 명시).
                    try {
                        const body = JSON.parse(frame.body);
                        if (body && body.occurred_at) {
                            const occurredAtMs = new Date(body.occurred_at).getTime();
                            wsMessageLatencyMs.add(now - occurredAtMs);
                        }
                    } catch (e) {
                        // 본문이 JSON 이 아니면 지연을 못 재지만 수신 카운트는 이미 반영됐다.
                    }
                }
            }
        });

        socket.on('error', function () {
            wsConnectFailures.add(1);
        });

        socket.setTimeout(function () {
            socket.close();
        }, (RAMP_SEC + HOLD_SEC + 15) * 1000);
    });

    check(res, { 'ws 연결 성공(101)': (r) => r && r.status === 101 });
    if (!res || res.status !== 101) {
        wsConnectFailures.add(1);
    }
    sleep(1);
}

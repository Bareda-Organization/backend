// 시나리오 3 변형 — "학생 채널 현실 부하" (BRIEF-W12-VERIFY §2 회차 C, 09-09 부하 보고서 §12
// "다음에 할 만한 것 ②"). scenario3_admin_fanout.js 를 복사해 **구독 대상만** 바꿨다 — 세션
// 전원이 /topic/admin/live 하나에 몰리는 대신, 각자 자기 학생 채널(/topic/students/{id}/run)만
// 구독한다. 09-09 라운드는 전 세션을 관제 채널에 몰아 "방송 1건 × 세션 수" 라는 최악을 쟀는데,
// 실제로는 세션마다 자기 회차 토픽만 구독하므로 방송 1건이 그 회차 명단(수십 명)에게만 간다 —
// 그 형태로 재면 통과선이 달라지는지를 이 스크립트로 확인한다.
//
// 구독 계정은 role=student 다(StompAuthChannelInterceptor#authorizeStudentChannel — 본인이거나
// 연결된 보호자만 구독 가능). CSV(login_id, student_id)는 부하 시험 DB(schoolbus_load)에 이번
// 측정을 위해 심은 학생 계정 목록이다(측정 세션 한정 — 코드·설정 변경이 아니라 데이터).
//
// 실행:
//   k6 run -e SCENARIO3_STUDENT_CSV=./scenario3_students.csv \
//       -e SCENARIO3_TARGET_VUS=3000 -e SCENARIO3_HOLD_SEC=180 scenario3_student_channel.js
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { login } from './lib/auth.js';
import { WS_URL, SEED_PASSWORD } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

export const wsMessageLatencyMs = new Trend('ws_message_latency_ms', true);
export const wsMessagesReceived = new Counter('ws_messages_received');
export const wsConnectFailures = new Counter('ws_connect_failures');
export const wsSubscribeFailures = new Counter('ws_subscribe_failures');

const students = new SharedArray('scenario3_students', function () {
    const path = __ENV.SCENARIO3_STUDENT_CSV || './scenario3_students.csv';
    const csv = open(path);
    return csv
        .trim()
        .split('\n')
        .filter((line) => line.length > 0)
        .map((line) => {
            const [loginId, studentId] = line.split(',');
            return { loginId, studentId: Number(studentId) };
        });
});

const TARGET_VUS = Number(__ENV.SCENARIO3_TARGET_VUS || students.length);
const HOLD_SEC = Number(__ENV.SCENARIO3_HOLD_SEC || 60);
const RAMP_SEC = Number(__ENV.SCENARIO3_RAMP_SEC || 60);

export const options = {
    scenarios: {
        student_subscribers: {
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
    // admin_fanout 과 같은 이유로 threshold 를 걸지 않는다 — 무너지는 지점을 관측하는 것이 목적이라
    // 연결 실패율에 pass/fail 을 걸면 시험이 조기 중단된다.
};

// VU 하나 = CSV 한 줄(학생 계정 하나). VU 수가 CSV 행 수를 넘으면 순환 재사용한다.
export default function () {
    const target = students[(__VU - 1) % students.length];
    const token = login(target.loginId, SEED_PASSWORD);

    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', function () {
            socket.send(connectFrame(token));
        });

        socket.on('message', function (raw) {
            const now = Date.now();
            const frames = parseFrames(raw);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-student', `/topic/students/${target.studentId}/run`));
                } else if (frame.command === 'ERROR') {
                    wsSubscribeFailures.add(1);
                } else if (frame.command === 'MESSAGE') {
                    wsMessagesReceived.add(1);
                    // 근사치 — occurred_at 은 서버 시계, now 는 이 k6 VU 의 로컬 시계다(admin_fanout
                    // 과 같은 가정 — 같은 호스트에서 백엔드·k6 를 돌리므로 NTP 보정을 하지 않는다).
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

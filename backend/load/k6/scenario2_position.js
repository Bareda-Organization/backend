// 시나리오 2 — "위치 수신 처리량" (IMPLEMENTATION_PLAN §5.2 #2).
// 재는 것: 위치 수신 처리 지연(POST /runs/{runId}/position 응답 시간) · WS 팬아웃 지연
// (송신 시각 → /topic/manager/runs/{runId} 로 그 위치가 방송되기까지). §5.3 은 이 시나리오에
// 수치 임계를 정하지 않았다(NFR-02·03 은 반영시간·송신주기 "규정"이지 이 시험의 pass/fail 기준이
// 아니다) — 그래서 threshold 는 실패율 0 하나만 걸고, 지연 분포는 summary 로 관측만 한다.
//
// VU 하나 = 시드된 회차(run) 하나 = 그 회차에 배치된 기사 하나. 그 VU 가 직접 위치를 올리고
// 동시에 자기 회차의 매니저 채널을 구독해 자기 송신의 방송을 되받는다 — 송신자와 수신자가 같은
// VU 라 "방금 보낸 것의 메아리"라는 가정이 이 시드(회차당 기사 1명)에서는 안전하다.
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
import { BASE_URL, WS_URL, SEED_PASSWORD } from './lib/config.js';
import { connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';

const runs = new SharedArray('scenario2_runs', function () {
    const path = __ENV.SCENARIO2_CSV || './scenario2_runs.csv';
    const csv = open(path);
    return csv
        .trim()
        .split('\n')
        .filter((line) => line.length > 0)
        .map((line) => {
            const [tag, loginId, runId] = line.split(',');
            return { tag, loginId, runId: Number(runId) };
        });
});

export const wsFanoutLatencyMs = new Trend('ws_fanout_latency_ms', true);
export const positionPostDurationMs = new Trend('position_post_duration_ms', true);
export const positionPostFailures = new Counter('position_post_failures');
export const wsConnectFailures = new Counter('ws_connect_failures');

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
    },
};

export default function () {
    const idx = (__VU - 1) % runs.length;
    const target = runs[idx];
    const token = login(target.loginId, SEED_PASSWORD);

    const durationSec = Number(__ENV.SCENARIO2_DURATION_SEC || 60);
    // NFR-03(위치 송신 주기)을 그대로 쓰지 않는다 — 이 값은 그 규정을 재현하는 것이 아니라 부하
    // 시험의 반복 주기 파라미터다. 실제 송신 주기와 다르게 잡을 수 있으므로 README 에 근거를 적는다.
    const intervalSec = Number(__ENV.SCENARIO2_INTERVAL_SEC || 7);

    let lastSentAt = null;

    const res = ws.connect(WS_URL, {}, function (socket) {
        socket.on('open', function () {
            socket.send(connectFrame(token));
        });

        socket.on('message', function (raw) {
            const frames = parseFrames(raw);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED') {
                    socket.send(subscribeFrame('sub-position', `/topic/manager/runs/${target.runId}`));
                    socket.setInterval(function () {
                        const now = Date.now();
                        const payload = JSON.stringify({
                            lat: 37.5 + Math.random() * 0.01,
                            lng: 127.0 + Math.random() * 0.01,
                            recorded_at: new Date(now).toISOString(),
                        });
                        lastSentAt = now;
                        const postRes = http.post(`${BASE_URL}/runs/${target.runId}/position`, payload, {
                            headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                        });
                        positionPostDurationMs.add(postRes.timings.duration);
                        if (postRes.status !== 204) {
                            positionPostFailures.add(1);
                        }
                    }, intervalSec * 1000);
                } else if (frame.command === 'ERROR') {
                    wsConnectFailures.add(1);
                } else if (frame.command === 'MESSAGE' && lastSentAt !== null) {
                    // 이 VU 는 자기 회차만 구독하고 그 회차에 위치를 올리는 것도 이 VU 뿐이다(시드가
                    // 회차당 기사 1명) — 그래서 도착한 첫 MESSAGE 를 직전 송신의 방송으로 본다.
                    wsFanoutLatencyMs.add(Date.now() - lastSentAt);
                    lastSentAt = null;
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

// 시나리오 4 — "온디맨드 계산 경합" (IMPLEMENTATION_PLAN §5.2 #4).
// 재는 것: 승인 화면(GET /staff/approvals/{id}) 응답 시간 · 지도 API 레이트리밋(스텁 격벽) 초과
// 건수. §5.3 은 이 항목에 명시적으로 "사양에 수치 부재 — 측정만 하고 임계는 부여하지 않는다" 고
// 적었다 — 그래서 이 스크립트는 threshold 를 걸지 않고 http_req_duration 분포를 그대로 report 로
// 낸다. 레이트리밋 초과 건수는 이 스크립트가 직접 셀 수 없다(스텁 카운터는 /actuator/prometheus
// 에만 노출) — 실행 전후로 그 값을 스냅샷해서 델타를 report §3 에 적는다(README 참고).
//
// approval_id 마다 최초 1회만 GET 한다 — 같은 id 를 반복 조회하면 ApprovalPreviewCache 가 두
// 번째부터 재계산을 건너뛴다(ApprovalQueryService.detail, 직접 코드 확인). 그래서 이 스크립트는
// per-vu-iterations 가 아니라 CSV 행 하나당 정확히 iteration 하나(shared-iterations)로 돈다 —
// 캐시 히트가 섞이면 "온디맨드 계산 경합"이 아니라 "캐시 히트 응답 시간"을 재게 된다.
//
// 시나리오 1(배치 확정)과 같은 스텁 인스턴스를 동시에 두드리게 만드는 것이 이 시나리오의 목적이라,
// scenario1_observe.sh 를 동시에 돌리면서 이 스크립트를 실행해야 "경합"이 실제로 재현된다 — 이
// 스크립트 단독 실행은 온디맨드 경로 자체의 응답 시간만 잰다(그것도 유효한 관측이지만 "경합"은
// 아니다. report §3 에 어느 쪽으로 실행했는지 적는다).
//
// 실행:
//   psql -v n=$N -f sql/scenario4_prep.sql -t -A -F',' | grep -v '^$' > k6/scenario4_approvals.csv
//   k6 run -e SCENARIO4_CSV=./scenario4_approvals.csv scenario4_approval_ondemand.js
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { login } from './lib/auth.js';
import { BASE_URL, SEED_PASSWORD } from './lib/config.js';

const approvals = new SharedArray('scenario4_approvals', function () {
    const path = __ENV.SCENARIO4_CSV || './scenario4_approvals.csv';
    const csv = open(path);
    return csv
        .trim()
        .split('\n')
        .filter((line) => line.length > 0)
        .map((line) => {
            const [tag, loginId, approvalId] = line.split(',');
            return { tag, loginId, approvalId: Number(approvalId) };
        });
});

export const approvalDetailDurationMs = new Trend('approval_detail_duration_ms', true);
export const approvalDetailFailures = new Counter('approval_detail_failures');

export const options = {
    scenarios: {
        approval_detail: {
            executor: 'shared-iterations',
            vus: Math.min(approvals.length, Number(__ENV.SCENARIO4_MAX_VUS || 50)),
            iterations: approvals.length,
            maxDuration: '10m',
        },
    },
    // §5.3: "사양에 수치 부재 — 측정만 하고 임계는 부여하지 않는다." threshold 를 걸지 않는다.
};

// staffA 는 계정이 1개뿐이라(scenario4_prep.sql 이 항상 이 login_id 를 낸다) 로그인도 1회만 한다 —
// setup() 은 VU 마다가 아니라 시험 전체에서 1회만 실행된다(k6 문서).
export function setup() {
    const loginId = approvals.length > 0 ? approvals[0].loginId : 'staffA';
    const token = login(loginId, SEED_PASSWORD);
    return { token };
}

export default function (data) {
    const idx = __ITER % approvals.length;
    const target = approvals[idx];

    const res = http.get(`${BASE_URL}/staff/approvals/${target.approvalId}`, {
        headers: { Authorization: `Bearer ${data.token}` },
    });

    approvalDetailDurationMs.add(res.timings.duration);
    const ok = check(res, { '200 OK': (r) => r.status === 200 });
    if (!ok) {
        approvalDetailFailures.add(1);
    }
}

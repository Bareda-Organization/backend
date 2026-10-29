package src.backend.routing.map.impl;

import java.math.BigDecimal;
import java.util.List;

/**
 * NCP Direction 15 응답 JSON 의 필요한 부분 — 최상위 {@code route.traoptimal[]} 아래로 내려가며
 * 요약(거리·시간)과 경로 좌표를 읽는다.
 *
 * @param route 경로 묶음
 */
record NaverDrivingResponse(RouteWrapper route) {

    /** {@code option} 을 지정하지 않았을 때의 기본 탐색 결과 묶음이 {@code traoptimal} 이다. */
    record RouteWrapper(List<Traoptimal> traoptimal) {
    }

    /** @param path {@code [[경도, 위도], ...]} — 경로를 구성하는 좌표(NCP 공식 문서 응답 예시). */
    record Traoptimal(Summary summary, List<List<BigDecimal>> path) {
    }

    /**
     * 총합과 함께 구간별 실측값도 온다(경유지가 있을 때만 — 실 API 확인).
     *
     * @param waypoints 시작점→첫 경유지, 경유지→다음 경유지 순 구간값. 경유지가 없으면 {@code null}
     * @param goal      마지막 경유지(또는 경유지가 없으면 시작점)→도착지 구간값. 경유지가 없으면
     *                  {@code location}·{@code dir} 만 오고 {@code distance}·{@code duration} 은
     *                  {@code null} 이다
     */
    record Summary(double distance, double duration, List<WaypointLeg> waypoints, GoalLeg goal) {
    }

    /** 경유지 하나 직전 구간의 실측 거리(m)·시간(ms). */
    record WaypointLeg(double distance, double duration) {
    }

    /** 도착지 직전 구간의 실측 거리(m)·시간(ms) — 경유지가 없으면 둘 다 {@code null} 이다. */
    record GoalLeg(Double distance, Double duration) {
    }
}

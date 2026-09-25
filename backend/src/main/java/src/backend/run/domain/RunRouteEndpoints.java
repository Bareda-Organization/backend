package src.backend.run.domain;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;

/**
 * 회차의 원점·목적지 좌표(Ruling 190) — 반대쪽 끝은 언제나 노선의 첫/마지막 정차지다: 등원은
 * 첫 승차지→학원, 하원은 학원→마지막 하차지(BR-101, 2026-09-25 검사 — 확정 배치·예정 경로 조회
 * 2벌 중복 통합).
 */
public final class RunRouteEndpoints {

    private RunRouteEndpoints() {
    }

    /** {@code direction} 에 따른 원점·목적지 — 등원은 첫 승차지→학원, 하원은 학원→마지막 하차지. */
    public static Endpoints of(Direction direction, GeoPoint academyPoint, GeoPoint firstStopPoint,
            GeoPoint lastStopPoint) {
        return direction == Direction.TO_ACADEMY
                ? new Endpoints(firstStopPoint, academyPoint)
                : new Endpoints(academyPoint, lastStopPoint);
    }

    public record Endpoints(GeoPoint origin, GeoPoint destination) {
    }
}

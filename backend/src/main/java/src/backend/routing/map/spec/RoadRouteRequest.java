package src.backend.routing.map.spec;

import java.time.Duration;
import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 도로 경로 요청 — {@code points} 는 출발지 → 정차지들 → 도착지 순으로 <b>이미 정렬돼 있다</b>.
 *
 * <p>이 포트는 순서를 정하지 않는다. 재배열은 ②단계({@code RouteEngine})의 일이고, 여기서 다시
 * 손대면 최적화가 정한 순서와 실제로 조회한 경로가 갈린다.
 *
 * @param points        최소 2개. 경유지 상한을 넘으면 구현체가 구간을 나눠 부르며 호출자는 그것을 모른다
 * @param timeout       호출자가 주입하는 <b>1회 요청</b>의 상한({@code ARCHITECTURE §8.3} — 배치는 길게,
 *                      온디맨드는 짧게). 배치용 정책을 온디맨드에 그대로 쓰면 관리자가 승인 화면에서
 *                      수십 초를 대기한다
 * @param caller        서킷 개방 시 폴백인지 오류인지를 가르는 축
 * @param forceFallback 참이면 구현체가 공급자 호출을 걸지 않고 직선거리 근사로 곧장 응답한다
 *                      (API_SPEC §6.14, 관리자 강제 확정 콘솔 개입) — 기본값은 {@code false}
 */
public record RoadRouteRequest(List<GeoPoint> points, Duration timeout, CallerPolicy caller,
        boolean forceFallback) {

    /** 지점이 1개면 구간이 0개라 경로라는 말이 성립하지 않는다 — 계산 전에 여기서 걸러 낸다. */
    private static final int MINIMUM_POINTS = 2;

    public RoadRouteRequest {
        if (points == null || points.size() < MINIMUM_POINTS) {
            throw new IllegalArgumentException("도로 경로 요청은 지점이 2개 이상이어야 한다");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("타임아웃은 호출자가 양수로 주입해야 한다");
        }
        if (caller == null) {
            throw new IllegalArgumentException("호출자 정책이 있어야 서킷 개방 처리를 가를 수 있다");
        }
        points = List.copyOf(points);
    }

    /** 강제 폴백이 필요 없는 기존 호출을 위한 편의 생성자 — {@code forceFallback=false}(F3 S2). */
    public RoadRouteRequest(List<GeoPoint> points, Duration timeout, CallerPolicy caller) {
        this(points, timeout, caller, false);
    }
}

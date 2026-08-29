package src.backend.routing.pipeline;

import java.time.Duration;
import java.util.Objects;

import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;

/**
 * 한 번의 계산에 적용할 실행 정책 — <b>호출자가 주입한다</b>({@code ARCHITECTURE §8.3}).
 *
 * <p>같은 계산 코드를 두 소비자가 쓰는데 정책이 다르다. 확정 배치는 사용자가 대기하지 않아 긴
 * 타임아웃을 허용하고, 온디맨드(승인 미리보기·경유 지점 지정)는 관리자가 화면 앞에 있어 짧게
 * 끊어야 한다. 배치용 정책을 온디맨드에 그대로 쓰면 승인 화면에서 수십 초를 대기한다.
 *
 * @param mapTimeout 지도 API <b>1회 요청</b>의 상한
 * @param caller     서킷 개방 시 폴백인지 즉시 오류인지를 가르는 축
 * @param trigger    이 계산을 누가 촉발했는지 — 그대로 {@code route_version.source} 가 된다
 */
public record ComputationPolicy(Duration mapTimeout, CallerPolicy caller, RouteVersionSource trigger) {

    public ComputationPolicy {
        if (mapTimeout == null || mapTimeout.isNegative() || mapTimeout.isZero()) {
            throw new IllegalArgumentException("지도 API 타임아웃은 양수여야 한다: " + mapTimeout);
        }
        Objects.requireNonNull(caller, "호출자 정책이 있어야 서킷 개방 처리를 가를 수 있다");
        Objects.requireNonNull(trigger, "촉발 원인이 있어야 산출 조건을 설명할 수 있다");
    }
}

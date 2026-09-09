package src.backend.student.command;

import java.util.Comparator;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.student.domain.StopProximity;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopMergeLookup;
import src.backend.student.repository.StopRepository;

/**
 * 검증된 좌표를 승하차지에 붙인다 — 근접한 것이 있으면 그것에, 없으면 새로 만든다(STU-05).
 *
 * <p>이것이 "공용 정류장" 을 만드는 것은 아니다(ERD {@code stop}) — 같은 주소를 쓰는 학생이 한
 * 승하차지로 묶이는 것은 <b>매칭 결과</b>이지 정류장 편성이 아니고, 편성은 Phase 6 노선 계산의 일이다.
 *
 * <p>매칭 범위는 <b>학원 안</b>이고, 학원 잠금도 같은 자리에 있다 — 둘 다 이 클래스가 아니라
 * {@link StopMergeLookup#lockAcademyAndFindNearby} 가 갖는다. 호출부에 두면 조건이든 잠금이든
 * 하나가 빠져도 동작하고, 그때 다른 학원의 승하차지에 학생이 붙거나 같은 자리에 승하차지가 둘 생긴다.
 */
@Component
@RequiredArgsConstructor
public class StopMatcher {

    /** {@code stop.name} 이 {@code varchar(100)} 이고 주소 원문은 {@code varchar(255)} 라 잘라 넣는다. */
    private static final int NAME_MAX_LENGTH = 100;

    private final StopRepository stopRepository;

    /**
     * 이 좌표가 설 승하차지를 정한다 — 임계 거리 안에서 <b>가장 가까운</b> 것을 고른다.
     *
     * <p>가장 가까운 것을 고르는 이유는 임계 안에 후보가 둘 이상 있을 수 있기 때문이다. 아무거나
     * 고르면 같은 주소가 조회 순서에 따라 다른 승하차지에 붙어, 두 번 저장했을 때 결과가 갈린다.
     *
     * <p><b>부르는 쪽이 트랜잭션을 열고 있어야 한다</b> — 학원 잠금이 그 트랜잭션이 끝날 때 풀리므로,
     * 트랜잭션이 없으면 잠금이 곧바로 풀려 임계 구역이 성립하지 않는다. 그 경우 조용히 지나가지 않고
     * {@link IllegalStateException} 이 난다.
     */
    public Stop matchOrCreate(Long academyId, GeocodedPoint point) {
        return nearest(academyId, point)
                .orElseGet(() -> stopRepository.save(Stop.forVerifiedAddress(academyId,
                        displayNameOf(point), point.displayName(), point.lat(), point.lng())));
    }

    private Optional<Stop> nearest(Long academyId, GeocodedPoint point) {
        return stopRepository
                .lockAcademyAndFindNearby(academyId, point.lat(), point.lng(),
                        StopProximity.searchBoxDegrees(point.lat()))
                .stream()
                .filter(stop -> distanceTo(stop, point) <= StopProximity.MERGE_RADIUS_METERS)
                .min(Comparator.comparingDouble(stop -> distanceTo(stop, point)));
    }

    private static double distanceTo(Stop stop, GeocodedPoint point) {
        return StopProximity.metersBetween(stop.getLat(), stop.getLng(), point.lat(), point.lng());
    }

    private static String displayNameOf(GeocodedPoint point) {
        String name = point.displayName();
        return name.length() <= NAME_MAX_LENGTH ? name : name.substring(0, NAME_MAX_LENGTH);
    }
}

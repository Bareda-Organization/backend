package src.backend.student.query;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.student.command.AddressVerification;
import src.backend.student.domain.StopProximity;
import src.backend.student.dto.StopSearchResponse;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopRepository;

/** 주소 → 좌표 검색(고정 노선 편성 화면) — 조회 전용이라 승하차지를 만들지 않는다. */
@Service
@RequiredArgsConstructor
public class StopSearchQueryService {

    private final AddressVerification addressVerification;

    private final StopRepository stopRepository;

    /**
     * 도로명 주소 한 건을 좌표로 옮기고, 그 부근의 기존 승하차지를 함께 돌려준다.
     *
     * <p><b>이 클래스에는 {@code @Transactional} 이 없다</b>(§7 규칙 16) — 지오코딩이 실 API 라
     * 트랜잭션 안에서 부르면 공급자 응답 시간만큼 연결이 열린 채 남는다. 근처 조회는 단일 SELECT 라
     * 트랜잭션 경계가 필요 없다(같은 이유로 자기 호출 {@code @Transactional} 도 두지 않는다 —
     * 프록시를 타지 않아 애너테이션이 아무 일도 하지 않는다).
     *
     * <p>실패는 {@link AddressVerification} 이 이미 가른 두 코드를 그대로 쓴다 —
     * {@code 422 ADDRESS_VERIFICATION_FAILED}(그런 주소가 없다) 와
     * {@code 503 ADDRESS_VERIFICATION_UNAVAILABLE}(지금 물어볼 수 없다).
     */
    public StopSearchResponse search(AuthUser requester, String address) {
        GeocodedPoint point = addressVerification.verifySingle(address);
        return new StopSearchResponse(point.lat(), point.lng(), point.displayName(),
                nearbyOf(requester.academyId(), point));
    }

    private List<StopSearchResponse.NearbyStop> nearbyOf(Long academyId, GeocodedPoint point) {
        return stopRepository
                .findNearby(academyId, point.lat(), point.lng(), StopProximity.searchBoxDegrees(point.lat()))
                .stream()
                .map(stop -> new StopSearchResponse.NearbyStop(stop.getId(), stop.getName(), stop.getAddress(),
                        stop.getLat(), stop.getLng(), distanceOf(stop, point)))
                .filter(nearby -> nearby.distanceM() <= StopProximity.MERGE_RADIUS_METERS)
                .sorted(Comparator.comparingInt(StopSearchResponse.NearbyStop::distanceM))
                .toList();
    }

    private static int distanceOf(Stop stop, GeocodedPoint point) {
        return (int) Math.round(StopProximity.metersBetween(stop.getLat(), stop.getLng(), point.lat(),
                point.lng()));
    }
}

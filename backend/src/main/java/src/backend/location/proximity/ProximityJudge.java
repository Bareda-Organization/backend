package src.backend.location.proximity;

import org.springframework.stereotype.Component;

import src.backend.global.policy.PolicyConstants;
import src.backend.routing.domain.GeoPoint;

/**
 * 승하차지 거리 판정 — 근접(NTF-04, Ruling 207: 다음 미도착 승하차지까지 300m 이내 진입) · 출발
 * (Ruling 307: 도착 처리된 승하차지에서 100m 밖으로 이탈) 두 판정을 함께 둔다.
 *
 * <p>{@link GeoPoint#distanceMetersTo} 로 대권 거리(Haversine)를 잰다 — 경로거리가 아니라 직선거리를
 * 쓰기로 한 것이 Ruling 207 의 명시적 결정이고, Ruling 307 도 같은 계산을 그대로 재사용한다. 두
 * 상한(300 · 100) 모두 <b>코드 상수</b>다(Ruling 207 — 횡단 규칙 10, 설정으로 빼지 않는다) — 도심에서
 * 300m 는 출발 판정에 너무 늦다는 것이 Ruling 307 이 100 을 따로 둔 이유다.
 */
@Component
public class ProximityJudge {

    /** 근접 기준 — 정의처는 {@link PolicyConstants#PROXIMITY_ALERT_METERS} 다(학원 설정 응답 {@code policy} 와 같은 값을 읽는다, Ruling 820). */
    private static final double PROXIMITY_THRESHOLD_METERS = PolicyConstants.PROXIMITY_ALERT_METERS;

    private static final double DEPARTURE_THRESHOLD_METERS = 100d;

    /** 버스 위치가 승하차지 300m 이내인가. */
    public boolean isWithinThreshold(GeoPoint busPosition, GeoPoint stopPosition) {
        return busPosition.distanceMetersTo(stopPosition) <= PROXIMITY_THRESHOLD_METERS;
    }

    /** 버스가 도착 처리된 승하차지에서 100m 밖으로 벗어났는가(Ruling 307 — 출발 판정). 안쪽 관측이 먼저 있어야 출발로 본다는 조건은 호출부가 건다(Ruling 875). */
    public boolean hasDeparted(GeoPoint busPosition, GeoPoint stopPosition) {
        return busPosition.distanceMetersTo(stopPosition) > DEPARTURE_THRESHOLD_METERS;
    }
}

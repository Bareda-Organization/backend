package src.backend.location.proximity;

import org.springframework.stereotype.Component;

import src.backend.routing.domain.GeoPoint;

/**
 * 근접 알림(NTF-04) 판정 — Ruling 207: 다음 미도착 승하차지까지 직선거리 300m 이내 진입.
 *
 * <p>{@link GeoPoint#distanceMetersTo} 로 대권 거리(Haversine)를 잰다 — 경로거리가 아니라 직선거리를
 * 쓰기로 한 것이 Ruling 207 의 명시적 결정이다. 상한 300 은 <b>코드 상수</b>다(Ruling 207 — 횡단
 * 규칙 10, 설정으로 빼지 않는다).
 */
@Component
public class ProximityJudge {

    private static final double PROXIMITY_THRESHOLD_METERS = 300d;

    /** 버스 위치가 승하차지 300m 이내인가. */
    public boolean isWithinThreshold(GeoPoint busPosition, GeoPoint stopPosition) {
        return busPosition.distanceMetersTo(stopPosition) <= PROXIMITY_THRESHOLD_METERS;
    }
}

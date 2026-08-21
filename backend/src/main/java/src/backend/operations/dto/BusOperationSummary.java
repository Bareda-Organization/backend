package src.backend.operations.dto;

import java.time.LocalDateTime;

import src.backend.location.dto.LocationOrigin;
import src.backend.routing.domain.RouteDirection;

/**
 * 관리자 관제 목록의 버스 1대 행(BG-21, 설계 §4.1) — 어느 버스가 운행 중이고, 몇 명이 탔고,
 * 누가 누락됐는가를 한 응답에 담는다. 버스마다 한 줄이며 화면은 이 배열을 그대로 목록·지도 마커에 쓴다.
 *
 * @param direction     세션이 진행 중·완료됐으면 그 세션의 방향, 아직 시작 전이면 당일 배포된 계획으로
 *                      짐작한 값(등원 우선)이다. 계획도 없으면 null — 아직 방향을 특정할 근거가 없다.
 */
public record BusOperationSummary(
        Long busId,
        String busName,
        RouteDirection direction,
        BusSessionStatus sessionStatus,
        LocationInfo location,
        Counts counts,
        Crew crew) {

    /**
     * 버스 실시간 좌표. {@code stale} 은 좌표 TTL(15초) 만료를 좌표 부재와 구분한다 —
     * 조회가 empty 면(보고가 끊겼거나 아직 한 번도 보고하지 않았거나) stale=true 로 표시하고
     * 좌표·시각·출처는 전부 null 로 내려 화면이 지난 좌표를 "현재 위치"로 오인하지 않게 한다.
     */
    public record LocationInfo(Double lat, Double lng, LocalDateTime recordedAt, LocationOrigin origin,
                                boolean stale) {

        public static LocationInfo staleInfo() {
            return new LocationInfo(null, null, null, null, true);
        }

        public static LocationInfo of(double lat, double lng, LocalDateTime recordedAt, LocationOrigin origin) {
            return new LocationInfo(lat, lng, recordedAt, origin, false);
        }
    }

    /**
     * 탑승 상태 5종(설계 §3) 집계. {@code total} 은 이 버스에 배정된 활성 학생 수(승인된 결석 포함)이며
     * boarded+alighted+waiting+absent+noShow 의 합과 같다.
     */
    public record Counts(int total, int boarded, int alighted, int waiting, int absent, int noShow) {
    }

    /**
     * 배치 인력(MON-02). {@code attendantMissing} 을 이름 필드와 별도로 둔 이유 — 인솔자가 없으면
     * 승하차 처리 주체가 없어 운행이 성립하지 않는다(기획 C-05·불변조건 I-1). null 여부만으로는
     * 화면이 놓치기 쉬워 명시적 플래그로 강조한다.
     */
    public record Crew(String driverName, String attendantName, boolean attendantMissing) {
    }
}

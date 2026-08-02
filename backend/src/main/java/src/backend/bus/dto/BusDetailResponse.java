package src.backend.bus.dto;

import java.time.LocalDate;
import java.util.List;

import src.backend.routing.domain.RouteDirection;

/**
 * 버스 상세 응답 — 관제 화면 1건이 필요한 마스터 데이터 전부(버스·기사·선탑자·당일 계획·명단+보호자).
 * 운행 세션과 승하차 기록은 담지 않는다(D-L) — 갱신 주기가 달라 폴링 대상이므로 별도 API 로 조회한다.
 */
public record BusDetailResponse(
        BusResponse bus,
        LocalDate serviceDate,
        List<RoutePlanView> plans,      // 당일 배포된 계획(등원/하원, 최대 2건). 없으면 빈 리스트
        List<RosterEntry> roster) {

    /** 당일 노선 계획 1건 — 정차 순서·좌표·polyline(지도 강조용). */
    public record RoutePlanView(Long routePlanId, RouteDirection direction, int version,
                                double totalDistanceM, double totalDurationS, String polyline,
                                List<StopView> stops) {

        public record StopView(int seq, Long studentId, double lat, double lng, long etaSeconds) {
        }
    }

    /** 명단 한 줄 — 학생 인적사항 + 승/하차지 + 보호자 연락처. */
    public record RosterEntry(Long studentId, String name, String photoUrl, String phone,
                              String pickupLabel, Double pickupLat, Double pickupLng,
                              String dropoffLabel, Double dropoffLat, Double dropoffLng,
                              List<GuardianView> guardians) {
    }

    public record GuardianView(Long userId, String name, String phone, String relation) {
    }
}

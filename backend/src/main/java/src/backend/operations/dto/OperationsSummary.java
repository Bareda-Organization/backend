package src.backend.operations.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import src.backend.notification.dto.NotificationResponse;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;

/**
 * 관리자 첫 화면 지표 카드 응답 — 오늘 무엇이 돌고 있고, 무엇을 처리해야 하고, 무엇이 이상한가(BG-22).
 * 설계 근거는 {@code docs/superpowers/specs/2026-08-21-관리자-운영현황-design.md} §5·§5.1.
 *
 * <p>기획이 요구하나 현재 코드에 대응물이 없는 항목(회차 자동 생성·3구간 규칙·미경유 정류장·
 * 기사·인솔자 변경 확인 응답)은 이 응답에 담지 않는다(설계 §5.4) — 빈 필드를 만들면 화면이
 * 그것을 표시하려다 혼란만 준다.
 */
public record OperationsSummary(
        Today today,
        Pending pending,
        Dispatch dispatch,
        Compliance compliance,
        Notifications notifications,
        Attendance attendance,
        Anomalies anomalies) {

    /** 학원 전체 운행 현황 — 버스 단위 요약(방향 무관) + 방향별 breakdown. */
    public record Today(
            int busesTotal,
            int running,
            int completed,
            int notStarted,
            Map<RouteDirection, DirectionCounts> byDirection) {

        /** 방향(PICKUP/DROPOFF) 하나에서의 버스 상태 분포. */
        public record DirectionCounts(int running, int completed, int notStarted) {
        }
    }

    /** 관리자가 처리해야 할 대기 건수. */
    public record Pending(long sosOpen, long attendancePending, long scheduleChangePending) {
    }

    /** 배차 이상 징후 — 자동배차·운행 개시 전에 드러나야 하는 것들. */
    public record Dispatch(
            List<OverCapacityBus> overCapacityBuses,
            List<StudentWithoutCoords> studentsWithoutCoords,
            List<BusWithoutCrew> busesWithoutCrew,
            List<UnpublishedPlan> unpublishedPlans) {

        public record OverCapacityBus(Long busId, String busName, int onboard, int assignCapacity) {
        }

        /**
         * 승차 정류장(또는 학생 전용 좌표) · 하차 좌표 중 하나라도 없는 학생.
         * 자동배차가 이 학생을 조용히 제외하는 원인이라 배차 실행 전에 드러나야 한다.
         */
        public record StudentWithoutCoords(Long studentId, String studentName, boolean missingPickup, boolean missingDropoff) {
        }

        /**
         * 기사 또는 선탑자가 미배정인 버스. 두 역할은 무게가 다르다 — 선탑자가 없으면 승하차 기록
         * 주체 자체가 없어 운행이 성립하지 않는다(불변조건 I-1). {@code missingAttendant} 를 별도
         * 필드로 두어 기사 미배정과 구분한다.
         */
        public record BusWithoutCrew(Long busId, String busName, boolean missingDriver, boolean missingAttendant) {
        }

        /** 당일 계획 중 최신 버전이 아직 PUBLISHED 에 도달하지 못한 것 — 배포 전에는 기사 앱에 노선이 보이지 않는다. */
        public record UnpublishedPlan(Long routePlanId, Long busId, String busName,
                                      RouteDirection direction, RoutePlanStatus status, int version) {
        }
    }

    /** 법적·안전 컴플라이언스 — 보험 만료는 즉시 조치, 임박은 갱신 준비로 성격이 다르다. */
    public record Compliance(List<InsuranceBus> insuranceExpired, List<InsuranceBus> insuranceExpiring) {

        public record InsuranceBus(Long busId, String busName, LocalDate insuranceExpiry, long daysRemaining) {
        }
    }

    /**
     * 당일 발송된 알림 이력. 학부모 문의 응대에 쓰는 화면이라 개인 식별 정보를 포함한다 —
     * 관제 대시보드(Grafana)와 달리 관리자 웹은 원래 개인정보를 다루는 화면이라 기준이 다르다.
     * 조회는 {@code TenantGuard}로 소속 학원 범위를 벗어나지 않는다.
     */
    public record Notifications(List<NotificationResponse> today) {
    }

    /** MON-06 — 회차별 탑승 의사 집계. {@code absent}는 당일 승인된 결석 신고만 센다({@code absent ≠ no_show}). */
    public record Attendance(long expected, long absent) {
    }

    /** 운영 이상 징후. */
    public record Anomalies(
            List<StaleLocationBus> staleLocationBuses,
            List<LongRunningSession> longRunningSessions,
            List<DelayedBus> delayedBuses) {

        /** 운행 중인데 최신 좌표를 못 받는 상태(좌표 TTL 만료 포함) — 좌표 부재와 "보고 중단"을 구분하는 신호. */
        public record StaleLocationBus(Long busId, String busName, Long sessionId) {
        }

        /** 세션 시작 후 임계값 이상 경과 — 종료 처리를 잊은 경우를 탐지한다. */
        public record LongRunningSession(Long sessionId, Long busId, String busName,
                                         LocalDateTime startedAt, long elapsedMinutes) {
        }

        /**
         * 계획 ETA(총 소요시간) 대비 경과 시간 추정치. ⚠ 정확도가 낮다 — 정류장 실제 도달 시각을
         * 기록하는 수단이 없어({@code RideEvent}는 승하차 시점만 기록) 계획값과의 단순 비교로 근사한다.
         * 필드명에 {@code Estimate} 를 붙여 실측이 아님을 명시한다.
         */
        public record DelayedBus(Long busId, String busName, Long sessionId, long delaySecondsEstimate) {
        }
    }
}

package src.backend.operations.dto;

import java.time.LocalDateTime;
import java.util.List;

import src.backend.operations.domain.BoardingStatus;
import src.backend.routing.domain.RouteDirection;

/**
 * 관리자가 버스 1대를 골랐을 때 보는 상세(BG-21, 설계 §4.2) — 학생별 상태 + 경로.
 * {@link BusOperationSummary}(목록 한 줄)와 달리 좌표·배차 인력은 담지 않는다 — 그건 목록 화면이
 * 이미 보여주고, 여기는 "이 버스의 학생 한 명 한 명이 어떤 상태인가"에 집중한다.
 */
public record BusOperationDetail(
        BusInfo bus,
        SessionInfo session,
        RoutePlanInfo routePlan,
        List<StudentStatus> students) {

    public record BusInfo(Long busId, String busName) {
    }

    /**
     * 오늘 운행 세션. {@code status} 는 {@link BusSessionStatus}(NOT_STARTED 포함 3단계)를 그대로 쓴다
     * — 세션이 아예 없는 경우를 화면이 구분해야 한다(목록 API 와 같은 이유). 세션이 없으면
     * {@code id}·{@code startedAt} 은 null, {@code direction} 은 당일 배포된 계획으로 짐작한 값이거나 null.
     */
    public record SessionInfo(Long id, RouteDirection direction, BusSessionStatus status, LocalDateTime startedAt) {
    }

    /**
     * 당일 배포된 노선 계획. 계획이 없으면(아직 미배포·미생성) 이 필드 전체가 null — 버스·학생 명단은
     * 노선 계획과 무관하게 유효한 정보라 이 필드 하나가 없다고 응답 전체를 404 로 만들지 않는다.
     */
    public record RoutePlanInfo(int version, String polyline, List<StopInfo> stops) {

        /**
         * 정차 1건. {@code name} 은 기존 {@code RoutePlanResponse} 에 없던 값으로, 이 API 가
         * "N번 정차"로만 표시되던 갭(PRODUCT_SPEC §12 F10)을 해소한다.
         *
         * <p>{@code reachedAtEstimate} — 필드명 자체에 "추정"을 넣었다. 세션 시작 시각 + 계획 ETA 로
         * 계산한 값이라 실제 도달 시각이 아니다. 버스가 실제 정류소에 도달한 시각을 기록하는 수단이
         * 부재해({@code RideEvent} 는 승하차 시점만 기록) 계획을 근거로 삼을 수밖에 없다 — 운행이
         * 지연되면 실제보다 이르게 "도달"로 표시된다.
         */
        public record StopInfo(int seq, String name, double lat, double lng, long etaSeconds,
                                LocalDateTime reachedAtEstimate) {
        }
    }

    /**
     * 학생 1명의 상태. {@code stopSeq}·{@code stopName} 은 당일 배포된 계획에 이 학생이 있을 때만
     * 채워진다(계획이 없거나 계획에 이 학생이 없으면 둘 다 null). {@code recordedAt} 은 오늘 기록된
     * 승하차 이벤트 중 가장 최근 시각(없으면 null) — 이 학생의 상태가 언제 확정됐는지 화면에 근거로 쓴다.
     */
    public record StudentStatus(Long studentId, String name, BoardingStatus status, Integer stopSeq,
                                 String stopName, LocalDateTime recordedAt, List<GuardianView> guardians) {
    }

    /**
     * 보호자 연락처. 누락(NO_SHOW)을 발견해도 이 정보가 없으면 화면이 관리자에게 전화를 걸어줄 수단을
     * 안 준다 — 그래서 상세 응답에 반드시 포함한다(설계 §5.1).
     */
    public record GuardianView(Long guardianUserId, String name, String relation, String phone) {
    }
}

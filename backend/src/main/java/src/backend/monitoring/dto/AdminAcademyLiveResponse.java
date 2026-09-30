package src.backend.monitoring.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 메인 관리자 콘솔의 학원 1곳 실시간 관제 응답(API_SPEC §6.8, O-05, 목표 8·9) — 오늘 회차를 상태와
 * 무관하게(idle·confirmed·moving·finished 4종) 싣는다(Ruling 315).
 *
 * <p>{@code stops[].eta} · {@code destination_eta} 는 {@code run_stop.eta} 저장값을 그대로 읽은
 * 계획값이다 — 실시간으로 다시 계산하지 않는다(Ruling 232 확정 — 계획값, 재계산 부재).
 *
 * <p>{@code position} 은 마지막 위치 수신 후 2분 초과(유실)면 비우고 {@code last_seen_at} 만 채운다
 * (Ruling 250 · {@code FEATURE_SPEC §4.16} A-14 live 스냅샷 규칙, {@code API_SPEC §5.18} 과 같은 기준값).
 *
 * <p>{@code confirm_at} 은 {@code run.confirm_at} 저장값(출발 30분 전)이다 — 강제 확정 화면의 "확정 예정" 이 읽는다({@code Ruling 393}).
 *
 * <p>{@code consecutive_failures} 는 확정이 계속 실패하는 회차를 강제 확정(§6.14) 대상으로 알아보는 재료다(BR-047).
 */
public record AdminAcademyLiveResponse(List<Run> runs) {

    public record Run(Long runId, String busNo, String direction, String runStatus, Position position,
            OffsetDateTime lastSeenAt, OffsetDateTime departTime, OffsetDateTime confirmAt, OffsetDateTime estDepartTime,
            List<Stop> stops, OffsetDateTime destinationEta, Contact driver, Contact escort, int consecutiveFailures) {
    }

    /** 위치 신호가 아직 한 번도 없거나(Redis 키 부재) 유실(2분 초과)이면 {@code null} — {@link Run#lastSeenAt}. */
    public record Position(BigDecimal lat, BigDecimal lng, OffsetDateTime receivedAt) {
    }

    /**
     * {@code eta} 는 {@code run_stop.eta} 저장값 그대로다(Ruling 232 확정 — 계획값, 재계산 부재).
     * 도착 처리(arrived_at != null)면 이미 지난 예정이라 {@code null} 로 비운다.
     */
    public record Stop(Long stopId, int seq, String name, BigDecimal lat, BigDecimal lng, String change,
            OffsetDateTime arrivedAt, OffsetDateTime eta) {
    }

    public record Contact(String name, String phone) {
    }
}

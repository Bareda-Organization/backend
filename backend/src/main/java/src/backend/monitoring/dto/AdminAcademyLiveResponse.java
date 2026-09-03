package src.backend.monitoring.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 메인 관리자 콘솔의 학원 1곳 실시간 관제 응답(API_SPEC §6.8, O-05, 목표 8·9) — {@code moving}
 * 상태인 회차만 싣는다.
 *
 * <p>{@code stops[].eta} · {@code destination_eta} 는 {@code run_stop.eta} 저장값을 그대로 읽은
 * 계획값이다 — 실시간으로 다시 계산하지 않는다(Ruling 232 확정 — 계획값, 재계산 부재).
 */
public record AdminAcademyLiveResponse(List<Run> runs) {

    public record Run(Long runId, String busNo, String direction, String runStatus, Position position,
            OffsetDateTime departTime, OffsetDateTime estDepartTime, List<Stop> stops,
            OffsetDateTime destinationEta, Contact driver, Contact escort) {
    }

    /** 위치 신호가 아직 한 번도 없으면(Redis 키 부재) {@code null}. */
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

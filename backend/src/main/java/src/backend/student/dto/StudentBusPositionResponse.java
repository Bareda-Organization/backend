package src.backend.student.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 학부모 앱의 실시간 버스 위치(LOC-02, API_SPEC §3.11) — {@code run_status} 가 {@code moving} 이
 * 아니거나 당일 {@code absent} 면 좌표 4종({@code lat}·{@code lng}·{@code receivedAt}·
 * {@code lastSeenAt})·{@code currentStopName} 이 전부 {@code null} 인 채로 돌아간다(§3.11 — 에러가
 * 아니라 좌표 필드 부재로 반환).
 *
 * <p>{@code receivedAt} 과 {@code lastSeenAt} 은 <b>동시에 채워지지 않는다</b> — 신호가 살아 있으면
 * {@code receivedAt} 만, 마지막 수신 후 2분(Ruling 208)이 지나 유실로 판단되면 {@code lastSeenAt} 만
 * 채운다(§3.11 화면 문구 "마지막 확인 위치 · N분 전"의 근거값).
 */
public record StudentBusPositionResponse(Long runId, String busNo, String runStatus, BigDecimal lat, BigDecimal lng,
        OffsetDateTime receivedAt, OffsetDateTime lastSeenAt, String currentStopName) {

    /** 위치가 없는 응답 — 미운행·확정전·종료·당일 미등원이 전부 이 모양으로 수렴한다. */
    public static StudentBusPositionResponse withoutPosition(Long runId, String busNo, String runStatus) {
        return new StudentBusPositionResponse(runId, busNo, runStatus, null, null, null, null, null);
    }
}

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
 * 채운다(§3.11 화면 문구 "마지막 확인 위치 · N분 전"의 근거값). {@code startedAt} · {@code finishedAt} · {@code delay} ·
 * {@code currentStopArrivedAt} 은 WebSocket 이벤트를 놓치고 들어온 화면이 시각과 지연 안내를 그리는 재료다(Ruling 821). 유실 때 좌표는 비우고
 * {@code currentStopName} 은 남긴다 — 학부모 앱은 좌표 부재로 유실을 판정하고, 도착 기록은 유실과 무관하다(BR-056).
 */
public record StudentBusPositionResponse(Long runId, String busNo, String runStatus, BigDecimal lat, BigDecimal lng,
        OffsetDateTime receivedAt, OffsetDateTime lastSeenAt, String currentStopName,
        OffsetDateTime currentStopArrivedAt, OffsetDateTime startedAt, OffsetDateTime finishedAt, Delay delay) {

    /** 회차의 마지막 지연 알림(§4.9) — 지연 안내 띠의 재료. */
    public record Delay(int minutes, String reason, OffsetDateTime sentAt) {
    }

    /** 위치가 없는 응답 — 미운행·확정전·종료·당일 미등원이 전부 이 모양으로 수렴한다. 실제 시작·종료 시각과 지연 알림은 그대로 싣는다. */
    public static StudentBusPositionResponse withoutPosition(Long runId, String busNo, String runStatus,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, Delay delay) {
        return new StudentBusPositionResponse(runId, busNo, runStatus, null, null, null, null, null, null, startedAt,
                finishedAt, delay);
    }
}

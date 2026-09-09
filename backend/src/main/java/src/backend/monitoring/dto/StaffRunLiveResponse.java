package src.backend.monitoring.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 전 차량 실시간 위치 응답(§5.18 {@code GET /staff/runs/live}, MON-07).
 *
 * <p>{@code status='moving'} 인 회차만 담는다 — 정본 문면과 조율자 권장(Phase 13 §4, T2 의 §6.8
 * 과 정합)이 같은 근거를 든다: WS {@code /ws/academy/{id}/live} 의 증분 방송이 이미 {@code moving}
 * 회차만 다루므로, 이 초기 스냅샷도 같은 대상 집합이어야 화면 진입 시 스냅샷과 이후 갱신이 어긋나지
 * 않는다.
 */
public record StaffRunLiveResponse(List<Run> runs) {

    /**
     * @param status 정본이 {@code moving} 만 반환한다 적어 상수처럼 보이지만, 필드로 남긴다 —
     *               응답 형태 자체는 상태 무관하게 유지하는 편이 §6.8(T2, 전 상태 포함 가능) 소비
     *               코드와의 형태 대칭에 유리하다는 판단(확신 없는 지점 — 보고서 §2)
     * @param position 위치 미수신 회차는 {@code null}
     * @param currentStop·{@code nextStop} 정차 이름 문자열(승하차지 또는 강제 경유지) — id 가 아니다
     * @param progress 확정 노선의 정차 진행도 — 노선 미확정이면 {@code done=0, total=0}
     * @param delayMinutes Ruling 232 §3.1 — 가장 최근 도착한 정차의 {@code arrived_at - eta} (분,
     *                     음수는 0), 도착한 정차가 없으면 {@code started_at - depart_time} (음수는
     *                     0). 두 경우 다 0 이하로 내려가지 않는다(확신 없는 지점 — 보고서 §2)
     * @param lastSeenAt {@code position} 이 {@code null} 일 때만 채운다 — 정본 §5.18 "위치 미수신
     *                   회차는 {@code position=null} + {@code last_seen_at}"
     */
    public record Run(Long runId, String busNo, String direction, String status, Position position,
            String currentStop, String nextStop, Progress progress, int delayMinutes, String driverName,
            String escortName, OffsetDateTime lastSeenAt) {
    }

    public record Position(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt) {
    }

    public record Progress(int done, int total) {
    }
}

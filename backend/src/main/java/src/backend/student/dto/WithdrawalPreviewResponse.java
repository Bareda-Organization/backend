package src.backend.student.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 퇴원 미리보기(API_SPEC §5.11 {@code GET /staff/students/{id}/withdrawal-preview}, Ruling 815) — 퇴원 확인 창이 오늘·내일
 * 영향을 보이는 값이다. 퇴원하면 오늘 회차는 그대로 운행되고(오늘 명단 유지) 내일 회차에서 빠진다. 아무것도 바꾸지 않는다.
 */
public record WithdrawalPreviewResponse(List<RunItem> todayRuns, List<RunItem> tomorrowRuns) {

    /**
     * 그 학생이 탑승자인 미취소·미종료 회차 1건 — {@code stopName} 은 그 학생의 승하차지(확정 전 예정 명단이면 예정 승하차지,
     * 승하차지를 알 수 없으면 {@code null}).
     */
    public record RunItem(Long runId, String busNo, String direction, OffsetDateTime departTime, String status,
            String stopName) {
    }
}

package src.backend.monitoring.dto;

import java.util.List;

/**
 * 메인 관리자 전체 관제의 학원별 오늘 지연·확정 실패 집계(API_SPEC §6.15, Ruling 543) — 학원마다
 * {@code GET /admin/academies/{id}/runs/live} 를 부르면 학원 수에 비례해 요청이 늘어서 한 번에 센다.
 *
 * <p>문제가 하나도 없는 학원은 {@code items} 에 없다(둘 다 0 인 행을 싣지 않는다) — 응답이 학원 수가 아니라 문제 학원 수에
 * 비례한다. {@code delayedRuns} 는 오늘 지연 알림이 나간 채 아직 끝나지 않은 회차, {@code confirmFailedRuns} 는 오늘
 * 확정이 계속 실패하는 회차(강제 확정 §6.14 의 대상 후보)다.
 *
 * <p>{@code today} 는 전 학원의 오늘 회차 요약이다(Ruling 805) — 기존 {@code items} 는 그대로 두는 가산 변경.
 */
public record AdminRunAttentionResponse(List<Item> items, List<Today> today) {

    /** 학원 1곳의 집계 — 두 값 중 하나는 반드시 0 보다 크다. */
    public record Item(Long academyId, long delayedRuns, long confirmFailedRuns) {
    }

    /**
     * 전 학원 오늘 회차 요약 1행(Ruling 805) — {@code items} 와 달리 문제 없는 학원·비활성 학원도 싣는다. {@code runCount} 는
     * 오늘 미취소 회차 수이고 {@code byStatus} 4종의 합과 같다.
     */
    public record Today(Long academyId, String academyName, String academyStatus, long runCount, ByStatus byStatus,
            long delayedRuns, long confirmFailedRuns) {
    }

    /** 회차 상태별 수 — {@code idle} · {@code confirmed} · {@code moving} · {@code finished}. */
    public record ByStatus(long idle, long confirmed, long moving, long finished) {
    }
}

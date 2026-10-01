package src.backend.monitoring.dto;

import java.util.List;

/**
 * 메인 관리자 전체 관제의 학원별 오늘 지연·확정 실패 집계(API_SPEC §6.15, Ruling 543) — 학원마다
 * {@code GET /admin/academies/{id}/runs/live} 를 부르면 학원 수에 비례해 요청이 늘어서 한 번에 센다.
 *
 * <p>문제가 하나도 없는 학원은 {@code items} 에 없다(둘 다 0 인 행을 싣지 않는다) — 응답이 학원 수가 아니라 문제 학원 수에
 * 비례한다. {@code delayedRuns} 는 오늘 지연 알림이 나간 채 아직 끝나지 않은 회차, {@code confirmFailedRuns} 는 오늘
 * 확정이 계속 실패하는 회차(강제 확정 §6.14 의 대상 후보)다.
 */
public record AdminRunAttentionResponse(List<Item> items) {

    /** 학원 1곳의 집계 — 두 값 중 하나는 반드시 0 보다 크다. */
    public record Item(Long academyId, long delayedRuns, long confirmFailedRuns) {
    }
}

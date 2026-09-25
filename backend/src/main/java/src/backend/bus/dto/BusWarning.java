package src.backend.bus.dto;

/**
 * 차량 수정 응답의 경고 1건(§5.12 "정원 축소로 기배정 인원이 초과하면 경고", BR-116) — 경고이고 차단이 아니다.
 *
 * @param code            {@code CAPACITY_BELOW_ASSIGNED}
 * @param runId           새 학생 정원보다 배정 인원이 많은 회차(오늘 이후 · 미취소 · {@code idle}·{@code confirmed})
 * @param assignedCount   그 회차의 배정 인원 — 확정 회차는 {@code absent} 를 뺀 명단, 확정 전은 예정 명단
 * @param studentCapacity 수정 후 학생 탑승 가능 인원
 */
public record BusWarning(String code, Long runId, int assignedCount, int studentCapacity) {

    public static BusWarning capacityBelowAssigned(Long runId, int assignedCount, int studentCapacity) {
        return new BusWarning("CAPACITY_BELOW_ASSIGNED", runId, assignedCount, studentCapacity);
    }
}

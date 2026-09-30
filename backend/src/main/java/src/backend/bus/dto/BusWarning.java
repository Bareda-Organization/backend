package src.backend.bus.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 차량 수정 응답의 경고 1건(§5.12 "정원 축소로 기배정 인원이 초과하면 경고", BR-116) — 경고이고 차단이 아니다.
 *
 * @param code            {@code CAPACITY_BELOW_ASSIGNED}
 * @param runId           새 학생 정원보다 배정 인원이 많은 회차(오늘 이후 · 미취소 · {@code idle}·{@code confirmed})
 * @param serviceDate     그 회차의 운행일 — 관리자가 어느 회차인지 찾게 한다(BR-274, Ruling 391)
 * @param departTime      그 회차의 예정 출발 시각
 * @param direction       그 회차의 방향({@code to_academy}·{@code from_academy})
 * @param assignedCount   그 회차의 배정 인원 — 확정 회차는 {@code absent} 를 뺀 명단, 확정 전은 예정 명단
 * @param studentCapacity 수정 후 학생 탑승 가능 인원
 */
public record BusWarning(String code, Long runId, LocalDate serviceDate, OffsetDateTime departTime, String direction,
        int assignedCount, int studentCapacity) {

    public static BusWarning capacityBelowAssigned(Long runId, LocalDate serviceDate, OffsetDateTime departTime,
            String direction, int assignedCount, int studentCapacity) {
        return new BusWarning("CAPACITY_BELOW_ASSIGNED", runId, serviceDate, departTime, direction, assignedCount,
                studentCapacity);
    }
}

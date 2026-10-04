package src.backend.manager.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.global.common.enums.Direction;
import src.backend.run.entity.RunStatus;

/**
 * 매니저 한 명의 한 회차 배치 한 줄(API_SPEC §5.13 {@code assignments[]}, Ruling 817) — 오늘·내일 미취소 회차만이다.
 * {@code managerId} 로 매니저별로 되돌려 나눈다(여러 매니저의 배치를 한 번에 읽는다).
 */
public record ManagerRunAssignment(Long managerId, Long runId, LocalDate serviceDate, String busNo,
        Direction direction, OffsetDateTime departTime, RunStatus status) {

    /** 응답 항목으로 옮긴다 — 매니저 id 는 상위 매니저가 들고 있어 싣지 않는다. */
    public ManagerResponse.RunItem toResponse() {
        return new ManagerResponse.RunItem(runId, serviceDate, busNo, direction.name().toLowerCase(Locale.ROOT),
                departTime, status.name().toLowerCase(Locale.ROOT));
    }
}

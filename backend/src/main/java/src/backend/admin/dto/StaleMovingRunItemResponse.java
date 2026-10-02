package src.backend.admin.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import src.backend.global.common.LowerCaseFormatter;
import src.backend.run.repository.StaleMovingRunRow;

/** 끝나지 않은 이동 중 회차 한 행(API_SPEC §6.16, Ruling 724) — {@code boardedCount} 는 아직 {@code boarded} 인 탑승자 수다. */
public record StaleMovingRunItemResponse(Long runId, Long academyId, String academyName, LocalDate serviceDate,
        String direction, String busNo, OffsetDateTime startedAt, boolean finishPending, long boardedCount) {

    public static StaleMovingRunItemResponse from(StaleMovingRunRow row) {
        return new StaleMovingRunItemResponse(row.getRunId(), row.getAcademyId(), row.getAcademyName(),
                row.getServiceDate(), LowerCaseFormatter.lower(row.getDirection().name()), row.getBusNo(),
                row.getStartedAt(), row.getFinishPending(), row.getBoardedCount());
    }
}

package src.backend.run.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.run.entity.Run;

/**
 * 운행 시작 응답(API_SPEC §4.4) — {@code auto_boarded_count} 는 하원 회차에서만 값이 실린다
 * (등원 회차는 시작 시점에 탑승자를 건드리지 않는다, C-07).
 */
public record RunStartResponse(String runStatus, OffsetDateTime startedAt, Integer autoBoardedCount) {

    public static RunStartResponse of(Run run, Integer autoBoardedCount) {
        return new RunStartResponse(run.getStatus().name().toLowerCase(Locale.ROOT), run.getStartedAt(),
                autoBoardedCount);
    }
}

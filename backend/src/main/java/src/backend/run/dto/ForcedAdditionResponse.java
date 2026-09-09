package src.backend.run.dto;

import src.backend.run.entity.RunForcedAddition;

/** 강제 추가 응답(API_SPEC §5.7) — {@code status} 는 항상 {@code staged}: 확정 배치가 명단에 합칠 때까지의 대기 상태다. */
public record ForcedAdditionResponse(Long forcedAdditionId, Long runId, Long studentId, Long stopId, String status) {

    private static final String STAGED = "staged";

    public static ForcedAdditionResponse from(RunForcedAddition entity) {
        return new ForcedAdditionResponse(entity.getId(), entity.getRunId(), entity.getStudentId(),
                entity.getStopId(), STAGED);
    }
}

package src.backend.run.dto;

import src.backend.run.entity.RunTransfer;

/**
 * 버스 간 이동 응답(API_SPEC §5.8) — {@code status} 는 항상 {@code staged}: 확정 배치가 명단에
 * 양쪽을 반영할 때까지의 대기 상태다.
 *
 * @param impact 이번 신청이 두 회차 인원에 주는 즉시 영향 — {@code from} 은 제외 전·후,
 *               {@code to} 는 추가 전·후·정원. 동시에 대기 중인 다른 이동 건과의 상호작용은
 *               반영하지 않는다(정원 판정 자체는 그 건들을 counting 하지만, 이 응답의 전·후 숫자는
 *               이 1건만의 즉시 효과를 보여준다).
 */
public record TransferResponse(Long transferId, Long studentId, Long fromRunId, Long toRunId, Long stopId,
        String status, TransferImpact impact) {

    private static final String STAGED = "staged";

    public record TransferImpact(FromImpact from, ToImpact to) {
    }

    public record FromImpact(long riderCountBefore, long riderCountAfter) {
    }

    public record ToImpact(long riderCountBefore, long riderCountAfter, int capacity) {
    }

    public static TransferResponse of(RunTransfer entity, TransferImpact impact) {
        return new TransferResponse(entity.getId(), entity.getStudentId(), entity.getFromRunId(),
                entity.getToRunId(), entity.getStopId(), STAGED, impact);
    }
}

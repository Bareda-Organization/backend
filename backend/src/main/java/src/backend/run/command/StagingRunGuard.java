package src.backend.run.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 강제 추가(§5.7)·이동(§5.8)을 저장하기 직전 회차를 잠가 아직 받을 수 있는지 다시 본다 — 구간 판정과 저장 사이에
 * 지오코딩(수 초)이 끼어, 그 사이 확정·취소된 회차에 201 을 주고 명단에 합류하지 못하는 일을 막는다(BR-042·044).
 * 호출부 트랜잭션 안에서만 부른다 — 잠금이 저장 커밋까지 유지돼야 한다.
 */
@Component
@RequiredArgsConstructor
class StagingRunGuard {

    private final RunRepository runRepository;

    void lockIdle(Run run) {
        Run locked = lock(run.getId(), run.getAcademyId());
        if (locked.isCanceled()) {
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
        if (locked.getStatus() != RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }

    /**
     * 판정 없이 잠근 회차를 돌려준다 — 임시 취소된 회차도 잠가야 하는 이동 대기 취소(§5.8.1)와 회차 취소가 쓴다.
     * 같은 영속성 컨텍스트가 그 회차를 먼저 읽었다면 잠금 조회는 낡은 상태를 돌려주므로, 호출부는 잠그기 전에 회차를
     * 읽지 않는다.
     */
    Run lock(Long runId, Long academyId) {
        return runRepository.findLockedByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }
}

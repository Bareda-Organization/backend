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
        Run locked = runRepository.findLockedByIdAndAcademyId(run.getId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (locked.isCanceled()) {
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
        if (locked.getStatus() != RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }
}

package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunStatus;
import src.backend.run.entity.RunTransferStatus;
import src.backend.run.repository.RunTransferRepository;

/**
 * 이동 대기 취소(API_SPEC §5.8.1, Ruling 369) — 반영 전({@code staged}) 이동 행을 지우고 감사 1건을 남긴다.
 *
 * <p><b>두 회차를 먼저 잠근 뒤 판정하고 지운다</b> — 조건부 {@code DELETE ... WHERE 두 회차 idle} 한 문장은
 * 확정 배치가 아직 커밋하지 않은 {@code idle → confirmed} 전이를 보지 못해, 확정이 그 행을 명단에 넣은 채 커밋되고
 * 행만 사라진다. 확정({@code RunRepository#confirmIfIdle})과 등록({@link StagingRunGuard})이 쓰는 같은 행 잠금을
 * 잡으면 둘 중 늦은 쪽이 먼저 커밋된 상태를 본다. 삭제는 지운 행 수를 돌려주는 조건부 문장이라 같은 행을 동시에
 * 취소한 요청이 먼저 지웠으면 0 이고 그때는 404 다.
 */
@Service
@RequiredArgsConstructor
public class TransferCancelCommandService {

    private final RunTransferRepository runTransferRepository;

    private final StagingRunGuard stagingRunGuard;

    private final RunTransferRemoval transferRemoval;

    private final Clock clock;

    /**
     * 이동 대기를 취소한다 — 없음·타 학원 {@code 404}, 임시 취소되지 않은 회차 중 하나라도 ① 구간이 끝났거나 이미
     * 반영됐으면 {@code 403}. 임시 취소된 회차는 판정에서 빼되 잠금은 잡는다(Ruling 372 ②) — 출발 회차가 취소된 이동은
     * 회차 취소가 지우지 않으므로 관계자가 이 API 로 지울 수 있어야 한다.
     */
    @Transactional
    public void cancel(AuthUser requester, Long transferId) {
        RunTransfer found = runTransferRepository.findByIdAndAcademyId(transferId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFER_NOT_FOUND));

        // 회차를 읽기 전에 잠근다 — 먼저 읽으면 잠금 조회가 낡은 상태를 돌려준다(StagingRunGuard#lock).
        Long firstId = Math.min(found.getFromRunId(), found.getToRunId());
        Long secondId = Math.max(found.getFromRunId(), found.getToRunId());
        Run first = stagingRunGuard.lock(firstId, requester.academyId());
        Run second = stagingRunGuard.lock(secondId, requester.academyId());
        assertOpenUnlessCanceled(first);
        assertOpenUnlessCanceled(second);

        if (found.getStatus() != RunTransferStatus.STAGED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        if (!transferRemoval.removeStaged(transferId, requester.academyId(), requester.accountId())) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_FOUND);
        }
    }

    private void assertOpenUnlessCanceled(Run run) {
        if (run.isCanceled()) {
            return;
        }
        if (run.getStatus() != RunStatus.IDLE
                || ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock)) != ChangeWindow.IMMEDIATE) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }
}

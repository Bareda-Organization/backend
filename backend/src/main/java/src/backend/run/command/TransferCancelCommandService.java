package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunTransferStatus;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;

/**
 * 이동 대기 취소(API_SPEC §5.8.1, Ruling 369) — 반영 전({@code staged}) 이동 행을 지우고 감사 1건을 남긴다.
 *
 * <p><b>두 회차를 먼저 잠근 뒤 판정하고 지운다</b> — 조건부 {@code DELETE ... WHERE 두 회차 idle} 한 문장은
 * 확정 배치가 아직 커밋하지 않은 {@code idle → confirmed} 전이를 보지 못해, 확정이 그 행을 명단에 넣은 채 커밋되고
 * 행만 사라진다. 확정({@code RunRepository#confirmIfIdle})과 등록({@link StagingRunGuard})이 쓰는 같은 행 잠금을
 * 잡으면 둘 중 늦은 쪽이 먼저 커밋된 상태를 본다. 잠금 뒤에 이동 행을 다시 읽는 이유는 같은 행을 동시에
 * 취소한 요청이 먼저 지웠을 수 있어서다(그러면 404).
 */
@Service
@RequiredArgsConstructor
public class TransferCancelCommandService {

    private final RunTransferRepository runTransferRepository;

    private final RunRepository runRepository;

    private final StagingRunGuard stagingRunGuard;

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

    /** 이동 대기를 취소한다 — 없음·타 학원 {@code 404}, 어느 한 회차라도 ① 구간이 끝났거나 이미 반영됐으면 {@code 403}. */
    @Transactional
    public void cancel(AuthUser requester, Long transferId) {
        Long academyId = requester.academyId();
        RunTransfer found = runTransferRepository.findByIdAndAcademyId(transferId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFER_NOT_FOUND));
        Run fromRun = runOf(found.getFromRunId(), academyId);
        Run toRun = runOf(found.getToRunId(), academyId);
        assertWindowOpen(fromRun);
        assertWindowOpen(toRun);

        // 잠금 순서를 id 로 고정해 등록(TransferStore)과 서로의 회차를 반대 순서로 잠그는 교착을 막는다.
        boolean fromFirst = fromRun.getId() < toRun.getId();
        stagingRunGuard.lockIdle(fromFirst ? fromRun : toRun);
        stagingRunGuard.lockIdle(fromFirst ? toRun : fromRun);

        RunTransfer transfer = runTransferRepository.findByIdAndAcademyId(transferId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSFER_NOT_FOUND));
        if (transfer.getStatus() != RunTransferStatus.STAGED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        runTransferRepository.delete(transfer);
        String actorLoginId = accountRepository.findById(requester.accountId()).map(Account::getLoginId).orElse(null);
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, academyId, requester.accountId(),
                actorLoginId, "run_transfer", transfer.getId(), Map.of(), OffsetDateTime.now(clock)));
    }

    private Run runOf(Long runId, Long academyId) {
        return runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    private void assertWindowOpen(Run run) {
        if (ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock)) != ChangeWindow.IMMEDIATE) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }
}

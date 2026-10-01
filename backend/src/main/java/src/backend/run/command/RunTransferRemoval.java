package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.request.ClientIp;
import src.backend.run.repository.RunTransferRepository;

/**
 * 반영 전({@code staged}) 이동 대기 1건을 지우고 감사 1행을 남긴다 — 관계자의 직접 취소(§5.8.1)와 회차 임시 취소의
 * 부수효과(Ruling 372 ①)가 같은 모양으로 지우도록 한 곳에 둔다. 호출부 트랜잭션 안에서만 부른다.
 */
@Component
@RequiredArgsConstructor
class RunTransferRemoval {

    private final RunTransferRepository runTransferRepository;

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

    /** @return 지웠으면 {@code true}, 이미 없거나 {@code staged} 가 아니라 지운 행이 없으면 {@code false} */
    boolean removeStaged(Long transferId, Long academyId, Long actorAccountId) {
        if (runTransferRepository.deleteStagedByIdAndAcademyId(transferId, academyId) == 0) {
            return false;
        }
        String actorLoginId = accountRepository.findById(actorAccountId).map(Account::getLoginId).orElse(null);
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, academyId, actorAccountId,
                actorLoginId, "run_transfer", transferId, Map.of(), ClientIp.ofCurrentRequest(),
                OffsetDateTime.now(clock)));
        return true;
    }
}

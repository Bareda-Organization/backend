package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.TemporaryPasswordGenerator;
import src.backend.account.dto.AccountPasswordResetResponse;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ClientIp;
import src.backend.global.security.AuthUser;

/**
 * 관리자 경유 비밀번호 초기화(AUTH-08 · C-11, API_SPEC §5.22 · Ruling 329) — SMS 연동 전까지 §2.9 가
 * {@code 503} 이라 학원 사용자의 유일한 복구 경로다.
 *
 * <p>비밀번호 교체 · 감사 기록 · refresh 토큰 전량 무효화를 한 트랜잭션에서 한다. 차단은 풀지 않는다 —
 * 해제는 메인 관리자 몫이다(§6.12).
 */
@Service
@RequiredArgsConstructor
public class AccountPasswordResetCommandService {

    /** 대상 역할(§5.22) — 관계자는 메인 관리자 경로(§6.7), 메인 관리자는 대상 밖. */
    private static final Set<Role> RESETTABLE_ROLES = Set.of(Role.PARENT, Role.STUDENT, Role.DRIVER, Role.ESCORT);

    private final AccountRepository accountRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final AuditLogRepository auditLogRepository;

    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    private final PasswordEncoder passwordEncoder;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    /**
     * 같은 학원의 학부모·학생·매니저 계정 비밀번호를 임시 값으로 바꾼다 — 부재·타 학원·대상 밖 역할은 전부
     * {@code 404 ACCOUNT_NOT_FOUND}(존재 비노출).
     *
     * <p>무효화가 마지막인 이유는 {@code revokeAllValidByAccountId} 가 영속성 컨텍스트를 비워, 그 뒤의 엔티티
     * 변경이 조용히 사라지기 때문이다({@code StaffAccountCommandService} 와 같은 순서).
     *
     * <p><b>임시 비밀번호의 BCrypt 는 트랜잭션·행 잠금 밖에서 만든다</b>(R46 T-3) — 새 해시는 저장된 해시와 무관해 잠금을
     * 기다릴 이유가 없고, 안에 두면 그 계정 행을 잡은 채 수십~수백 ms 를 쓴다. 대상이 없어 404 인 요청도 해시를 한 번
     * 만드는 것은 받아들인다 — 관계자 전용 경로라 빈도가 낮다.
     */
    public AccountPasswordResetResponse reset(AuthUser requester, Long accountId) {
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String temporaryHash = passwordEncoder.encode(temporaryPassword);
        return transactionTemplate.execute(status -> issue(requester, accountId, temporaryPassword, temporaryHash));
    }

    private AccountPasswordResetResponse issue(AuthUser requester, Long accountId, String temporaryPassword,
            String temporaryHash) {
        // 행 잠금으로 읽는다(BR-249) — 전 컬럼 UPDATE 가 그 사이 커밋된 로그인 실패 차단을 지우지 않게 한다.
        Account target = accountRepository.findByIdAndAcademyIdForUpdate(accountId, requester.academyId())
                .filter(account -> RESETTABLE_ROLES.contains(account.getRole()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Account actor = accountRepository.findById(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);

        target.issueTemporaryPassword(temporaryHash);
        auditLogRepository.save(AuditLog.forAccountPasswordReset(target.getAcademyId(), actor.getId(),
                actor.getLoginId(), target.getId(), ClientIp.ofCurrentRequest(), now));
        AccountPasswordResetResponse response =
                new AccountPasswordResetResponse(String.valueOf(target.getId()), target.getLoginId(), temporaryPassword);
        refreshTokenRepository.revokeAllValidByAccountId(target.getId(), now);
        return response;
    }
}

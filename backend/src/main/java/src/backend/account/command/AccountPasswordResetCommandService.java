package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final Clock clock;

    /**
     * 같은 학원의 학부모·학생·매니저 계정 비밀번호를 임시 값으로 바꾼다 — 부재·타 학원·대상 밖 역할은 전부
     * {@code 404 ACCOUNT_NOT_FOUND}(존재 비노출).
     *
     * <p>무효화가 마지막인 이유는 {@code revokeAllValidByAccountId} 가 영속성 컨텍스트를 비워, 그 뒤의 엔티티
     * 변경이 조용히 사라지기 때문이다({@code StaffAccountCommandService} 와 같은 순서).
     */
    @Transactional
    public AccountPasswordResetResponse reset(AuthUser requester, Long accountId) {
        Account target = accountRepository.findByIdAndAcademyId(accountId, requester.academyId())
                .filter(account -> RESETTABLE_ROLES.contains(account.getRole()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Account actor = accountRepository.findById(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);

        String temporaryPassword = temporaryPasswordGenerator.generate();
        target.changePassword(passwordEncoder.encode(temporaryPassword));
        auditLogRepository.save(AuditLog.forAccountPasswordReset(target.getAcademyId(), actor.getId(),
                actor.getLoginId(), target.getId(), now));
        AccountPasswordResetResponse response =
                new AccountPasswordResetResponse(String.valueOf(target.getId()), target.getLoginId(), temporaryPassword);
        refreshTokenRepository.revokeAllValidByAccountId(target.getId(), now);
        return response;
    }
}

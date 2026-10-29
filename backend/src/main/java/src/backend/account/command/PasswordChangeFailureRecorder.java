package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.LoginFailureDetail;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ClientIp;
import src.backend.global.security.AccountStandingChangedEvent;

/**
 * 비밀번호 변경에서 현재 비밀번호를 틀린 시도를 로그인 실패와 같은 카운터(C-11)에 올린다(API_SPEC §2.8). 접근 토큰을 가진 사람이 변경
 * API 로 현재 비밀번호를 제한 없이 대입하지 못하게 하는 장치다.
 *
 * <p>별도 빈인 이유 — 호출자({@link PasswordChangeCommandService#changePassword})는 이 기록을 커밋한 뒤에야 예외를 던져야 하는데, 던지는
 * 예외가 같은 트랜잭션을 지나가면 방금 올린 카운터까지 롤백된다. 그래서 이 빈이 카운터를 올려 커밋하고 <b>던질 예외를 돌려준다</b>.
 * 5회째에는 로그인과 같이 계정을 {@code blocked} 로 옮기고 유효 refresh 토큰을 전량 무효화하며 차단 감사 행을 남긴다.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeFailureRecorder {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 틀린 현재 비밀번호 한 번을 기록하고 호출자가 던질 예외를 돌려준다 — 상한 전이면 남은 횟수를 실은 {@code 401 INVALID_CREDENTIALS},
     * 이번 시도로 상한에 닿았거나 이미 차단된 계정이면 {@code 403 AUTH_ACCOUNT_BLOCKED}.
     */
    @Transactional
    public BusinessException record(Long accountId) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.assertNotBlocked();
        OffsetDateTime now = OffsetDateTime.now(clock);
        int remaining = account.recordLoginFailure(now);
        if (account.getStatus() != AccountStatus.BLOCKED) {
            return new BusinessException(ErrorCode.INVALID_CREDENTIALS, new LoginFailureDetail(remaining));
        }
        refreshTokenRepository.revokeAllValidByAccountId(accountId, now);
        auditLogRepository.save(AuditLog.forLoginBlock(account.getAcademyId(), accountId, account.getLoginId(),
                ClientIp.ofCurrentRequest(), now));
        eventPublisher.publishEvent(new AccountStandingChangedEvent(accountId)); // 커밋 직후 계정 상태 보관 항목을 비운다
        return new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
    }
}

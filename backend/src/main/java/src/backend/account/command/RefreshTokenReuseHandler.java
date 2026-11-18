package src.backend.account.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.entity.RefreshToken;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.service.AuditRecorder;
import src.backend.global.request.ClientIp;

/**
 * 회전으로 폐기된 refresh 토큰이 다시 제시됐을 때의 처리(API_SPEC §2.6) — 회전 10초 뒤의 제시는 훔친 토큰의 재사용으로 보고 그 계정의
 * refresh 토큰을 전부 폐기하며 감사 행 1건을 남긴다. 폐기할 토큰이 하나도 없으면(같은 토큰의 이전 제시가 이미 전부 폐기했다)
 * 감사 행을 더하지 않아 탐지 한 번에 1건이다. 10초 안의 제시는 같은 토큰을 동시에 보낸 경합이라 아무것도 하지 않는다(BR-061).
 * 로그아웃 · 비밀번호 변경 · 차단 같은 다른 폐기는 {@code rotated_at} 이 비어 있어 재사용으로 보지 않는다.
 *
 * <p>호출한 재발급 요청은 이어서 {@code 401} 을 던져 롤백되므로, 폐기와 감사 행은 {@code REQUIRES_NEW} 로 따로 커밋한다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenReuseHandler {

    /** 회전 직후 같은 토큰이 겹쳐 도착하는 경합으로 보는 시간 — 이보다 늦은 제시를 재사용으로 본다. */
    static final Duration RACE_WINDOW = Duration.ofSeconds(10);

    private static final String AUDIT_ACTION = "account.refresh_token_reuse";

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountRepository accountRepository;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    /** 이미 폐기된 {@code revoked} 토큰이 제시됐을 때 재사용이면 계정의 refresh 토큰을 전부 폐기하고 감사 행을 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRevokedTokenPresented(RefreshToken revoked) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (revoked.getRotatedAt() == null || !revoked.getRotatedAt().plus(RACE_WINDOW).isBefore(now)) {
            return;
        }
        Long accountId = revoked.getAccountId();
        if (refreshTokenRepository.revokeAllValidByAccountId(accountId, now) == 0) {
            return;
        }
        Account account = accountRepository.findById(accountId).orElseThrow();
        auditRecorder.record(AuditLog.forDataAccessChange(AuditAction.UPDATE, account.getAcademyId(), accountId,
                account.getLoginId(), "account", accountId, Map.of("action", AUDIT_ACTION),
                ClientIp.ofCurrentRequest(), now));
    }
}

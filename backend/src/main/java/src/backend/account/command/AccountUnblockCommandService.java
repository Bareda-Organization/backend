package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.AccountUnblockResponse;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 로그인 차단 해제(AUTH-06 · O-03, API_SPEC §6.12) — 상태 전이와 감사 적재를 한 트랜잭션에서 함께 한다.
 *
 * <p><b>refresh 토큰을 무효화하지 않는다.</b> 해제는 접근을 <b>여는</b> 방향이라 끊을 세션이 없고,
 * 애초에 차단 시점({@code LoginCommandService})에 그 계정의 유효 토큰을 전량 무효화했으므로 여기서
 * 다시 부르면 항상 0행을 갱신하는 호출이 하나 남는다.
 */
@Service
@RequiredArgsConstructor
public class AccountUnblockCommandService {

    private final AccountRepository accountRepository;

    private final AuditLogRepository auditLogRepository;

    private final Clock clock;

    /**
     * 차단을 푼다 — 미존재 계정은 {@code 404 ACCOUNT_NOT_FOUND}, {@code blocked} 가 아니면
     * {@code 409 ACCOUNT_NOT_BLOCKED}(§6.12).
     *
     * <p>감사 적재가 <b>같은 트랜잭션</b>인 것이 중요하다. 갈라 두면 상태만 바뀌고 기록이 없는 계정이
     * 존재할 수 있고, 그 상태는 "해제된 적 없는 계정" 과 구별되지 않아 감사의 목적이 소멸한다.
     *
     * <p>처리자 계정을 토큰의 식별자로 <b>다시 조회</b>하는 이유는 감사 행이 {@code actor_login_id}
     * 스냅샷을 요구하는데(ERD §3.6) 토큰에는 그 값이 없어서다 — 아이디를 토큰에 실어 옮기면 계정이
     * 아이디를 바꾼 뒤에도 옛 값이 계속 기록된다.
     *
     * @param actorAccountId 해제를 실행하는 메인 관리자 계정 — {@code unblocked_by} 이자 감사 행위자다
     */
    @Transactional
    public AccountUnblockResponse unblock(Long targetAccountId, Long actorAccountId) {
        Account actor = accountRepository.findById(actorAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Account target = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now(clock);
        target.unblock(actor.getId(), now);
        auditLogRepository.save(AuditLog.forAccountUnblock(target.getAcademyId(), actor.getId(),
                actor.getLoginId(), target.getId(), now));

        return AccountUnblockResponse.from(target);
    }
}

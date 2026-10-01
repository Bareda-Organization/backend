package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 비밀번호 변경(AUTH-07, API_SPEC §2.8) — 현재 비밀번호를 대조한 뒤 바꾸고, 성공하면 이 계정의
 * refresh 토큰을 전량 무효화한다(재로그인 강제).
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeCommandService {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 현재 비밀번호 불일치 시 {@code 401 INVALID_CREDENTIALS}(API_SPEC §2.8) — 형식 오류는 {@code @Valid} 가 앞단에서 걸러 {@code 422} 로 응답한다.
     *
     * <p><b>BCrypt(대조 · 새 해시)는 트랜잭션·행 잠금 밖에서 한다</b>(R46 T-3, {@code LoginCommandService} 와 같은 3단계) —
     * 요청당 수십~수백 ms 라 잠금 안에 두면 그 계정의 로그인이 그만큼 막히고 연결이 묶인다. 잠금 없이 읽어 대조하고 새 해시를
     * 만든 뒤, {@link #settle} 이 행을 잠가 저장한다.
     */
    public void changePassword(Long accountId, String currentPassword, String newPassword) {
        Account snapshot = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        String comparedHash = snapshot.getPasswordHash();
        if (!passwordEncoder.matches(currentPassword, comparedHash)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        String newHash = passwordEncoder.encode(newPassword);
        transactionTemplate.executeWithoutResult(
                status -> settle(accountId, currentPassword, comparedHash, newHash));
    }

    /**
     * 행을 잠그고 새 해시를 저장한 뒤 refresh 토큰을 전량 무효화한다.
     *
     * <p>행 잠금으로 읽는다(BR-249) — 전 컬럼 UPDATE 가 그 사이 커밋된 로그인 실패 차단을 지우지 않게 한다. <b>잠근 행의
     * 해시가 대조에 쓴 해시와 다르면</b> 그 사이 다른 요청이 비밀번호를 바꾼 것이라 앞선 대조를 버리고 현재 비밀번호를
     * 잠근 행의 해시로 다시 대조한다({@code LoginSettlement} 와 같다) — 옛 대조를 믿으면 이미 바뀐 비밀번호를 새 값으로
     * 덮어쓴다. 다시 대조하는 동안은 잠금을 쥐지만 드문 경로라 받아들인다.
     */
    private void settle(Long accountId, String currentPassword, String comparedHash, String newHash) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.getPasswordHash().equals(comparedHash)
                && !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        account.changePassword(newHash);

        OffsetDateTime now = OffsetDateTime.now(clock);
        refreshTokenRepository.revokeAllValidByAccountId(accountId, now);
    }
}

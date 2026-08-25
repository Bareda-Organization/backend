package src.backend.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.entity.RefreshToken;
import src.backend.global.common.enums.Role;

/**
 * {@link RefreshTokenRepository} 신규 조회·갱신 — 호출부(Task 4)가 아직 없어 이 테스트가
 * 그 자리를 대신한다. 특히 부분 인덱스({@code ix_refresh_token_account_active}) 조건과
 * 어긋나면 무효화된 토큰이 결과에 섞이므로, "행이 나온다"가 아니라 "섞이지 않는다"를 단언한다.
 */
@SpringBootTest
@Transactional
class RefreshTokenRepositoryTest {

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private Long createAccount(String loginId, String phone) {
        Academy academy = academyRepository.save(Academy.register("P2T3RTKQQQQ" + loginId.charAt(loginId.length() - 1),
                "학원P2T3RTK토큰", "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId, "x", "토큰테스트",
                phone, null, Role.PARENT));
        return account.getId();
    }

    /** §2.6 재발급 — {@code token_hash} UNIQUE 를 그대로 탄다. */
    @Test
    void findByTokenHash_은_해시로_토큰을_찾는다() {
        Long accountId = createAccount("p2t3rtkfindqqq1", "010-8888-0001");
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-find-1", now, now.plusDays(14), "device-a"));

        RefreshToken found = refreshTokenRepository.findByTokenHash("hash-find-1").orElseThrow();

        assertThat(found.getAccountId()).isEqualTo(accountId);
    }

    /** §2.8 비밀번호 변경 시 무효화 대상 조회 — 이미 해지된 토큰은 결과에 섞이지 않는다. */
    @Test
    void findAllByAccountIdAndRevokedAtIsNull_은_해지된_토큰을_제외한다() {
        Long accountId = createAccount("p2t3rtkfindqqq2", "010-8888-0002");
        OffsetDateTime now = OffsetDateTime.now();
        RefreshToken valid = refreshTokenRepository.save(
                RefreshToken.issue(accountId, "hash-valid-2", now, now.plusDays(14), "device-a"));
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-revoked-2", now, now.plusDays(14), "device-b"));
        refreshTokenRepository.revokeAllValidByAccountId(accountId, now);
        // 위 호출로 둘 다 무효화되므로, 새 유효 토큰을 하나 더 발급해 "유효 1건 + 무효 1건" 상태를 만든다.
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-valid-2-b", now, now.plusDays(14), "device-c"));

        var result = refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId);

        assertThat(result).extracting(RefreshToken::getTokenHash)
                .containsExactly("hash-valid-2-b")
                .doesNotContain("hash-valid-2", "hash-revoked-2");
    }

    /** §2.8 전량 무효화 — 이미 해지된 행은 원래 시각을 유지한 채 건드리지 않는다. */
    @Test
    void revokeAllValidByAccountId_은_이미_해지된_토큰의_시각을_바꾸지_않는다() {
        Long accountId = createAccount("p2t3rtkfindqqq3", "010-8888-0003");
        OffsetDateTime issuedAt = OffsetDateTime.now().minusDays(1);
        OffsetDateTime firstRevokedAt = issuedAt.plusHours(1);
        RefreshToken alreadyRevoked = refreshTokenRepository.save(
                RefreshToken.issue(accountId, "hash-already-3", issuedAt, issuedAt.plusDays(14), "device-a"));
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-untouched-3", issuedAt,
                issuedAt.plusDays(14), "device-b"));
        // 하나를 먼저 해지해 "이미 해지된 상태"를 만든다.
        int firstCount = refreshTokenRepository.revokeAllValidByAccountId(accountId, firstRevokedAt);
        assertThat(firstCount).isEqualTo(2);

        // 새로 유효한 토큰을 하나 추가한 뒤, 다시 무효화를 호출한다.
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-new-valid-3", issuedAt,
                issuedAt.plusDays(14), "device-c"));
        OffsetDateTime secondRevokedAt = firstRevokedAt.plusHours(1);
        int secondCount = refreshTokenRepository.revokeAllValidByAccountId(accountId, secondRevokedAt);

        assertThat(secondCount).isEqualTo(1);
        RefreshToken reloaded = refreshTokenRepository.findByTokenHash("hash-already-3").orElseThrow();
        assertThat(reloaded.getRevokedAt()).isEqualTo(firstRevokedAt);
    }
}

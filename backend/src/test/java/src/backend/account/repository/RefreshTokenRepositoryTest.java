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

    /** §2.7 로그아웃 — 요청 토큰 1건을 무효화하면 반환값 1, 재조회 시 해지 시각이 채워진다. */
    @Test
    void revokeByTokenHash_은_유효한_토큰을_무효화하고_1을_반환한다() {
        Long accountId = createAccount("p2t3rtkfindqqq4", "010-8888-0004");
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-logout-4", now, now.plusDays(14), "device-a"));
        OffsetDateTime revokedAt = now.plusMinutes(1);

        int count = refreshTokenRepository.revokeByTokenHash("hash-logout-4", revokedAt);

        assertThat(count).isEqualTo(1);
        RefreshToken reloaded = refreshTokenRepository.findByTokenHash("hash-logout-4").orElseThrow();
        assertThat(reloaded.getRevokedAt()).isEqualTo(revokedAt);
    }

    /** §2.7 로그아웃 재호출 — 이미 해지된 토큰을 다시 부르면 반환값 0, 최초 해지 시각을 덮어쓰지 않는다. */
    @Test
    void revokeByTokenHash_은_이미_해지된_토큰을_다시_해지하지_않는다() {
        Long accountId = createAccount("p2t3rtkfindqqq5", "010-8888-0005");
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-logout-5", now, now.plusDays(14), "device-a"));
        OffsetDateTime firstRevokedAt = now.plusMinutes(1);
        int firstCount = refreshTokenRepository.revokeByTokenHash("hash-logout-5", firstRevokedAt);
        assertThat(firstCount).isEqualTo(1);

        OffsetDateTime secondRevokedAt = firstRevokedAt.plusMinutes(1);
        int secondCount = refreshTokenRepository.revokeByTokenHash("hash-logout-5", secondRevokedAt);

        assertThat(secondCount).isEqualTo(0);
        RefreshToken reloaded = refreshTokenRepository.findByTokenHash("hash-logout-5").orElseThrow();
        assertThat(reloaded.getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    /** §2.7 로그아웃 — 단말 A 로그아웃이 같은 계정의 다른 유효 토큰(단말 B)을 건드리지 않는다. */
    @Test
    void revokeByTokenHash_은_같은_계정의_다른_유효_토큰을_건드리지_않는다() {
        Long accountId = createAccount("p2t3rtkfindqqq6", "010-8888-0006");
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-logout-6-a", now, now.plusDays(14), "device-a"));
        refreshTokenRepository.save(RefreshToken.issue(accountId, "hash-logout-6-b", now, now.plusDays(14), "device-b"));

        refreshTokenRepository.revokeByTokenHash("hash-logout-6-a", now.plusMinutes(1));

        RefreshToken deviceB = refreshTokenRepository.findByTokenHash("hash-logout-6-b").orElseThrow();
        assertThat(deviceB.getRevokedAt()).isNull();
    }

    /**
     * {@code @Modifying} 쿼리는 명시적 트랜잭션이 있어야 실행된다 — 이 클래스는 {@code @Transactional}
     * 이라 평소엔 그 우산 아래서 돌아 이 메서드 자체의 트랜잭션 경계가 있는지 없는지가 드러나지
     * 않는다. 이 테스트만 {@code NOT_SUPPORTED} 로 클래스 트랜잭션을 걷어내, 호출부(Task 4)가
     * {@code @Transactional} 을 깜빡해도 이 메서드가 스스로 트랜잭션을 열어 동작하는지 직접 본다
     * (보완 리뷰 Minor #3).
     *
     * <p>클래스 트랜잭션을 걷어냈으므로 이 메서드가 만든 행은 테스트 종료 후에도 롤백되지 않는다 —
     * 재실행(테스트 전체 묶음을 두 번 연속 돌리는 검증 포함) 시 UNIQUE 제약과 충돌하지 않도록 {@code finally}
     * 에서 직접 지운다.
     */
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void revokeByTokenHash_은_호출부_트랜잭션이_없어도_동작한다() {
        Academy academy = academyRepository.save(
                Academy.register("P2T3RTKQQQQ7", "학원P2T3RTK토큰", "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), "p2t3rtkfindqqq7", "x",
                "토큰테스트", "010-8888-0007", null, Role.PARENT));
        try {
            OffsetDateTime now = OffsetDateTime.now();
            refreshTokenRepository.save(
                    RefreshToken.issue(account.getId(), "hash-notx-7", now, now.plusDays(14), "device-a"));

            int count = refreshTokenRepository.revokeByTokenHash("hash-notx-7", now.plusMinutes(1));

            assertThat(count).isEqualTo(1);
        } finally {
            refreshTokenRepository.findByTokenHash("hash-notx-7").ifPresent(refreshTokenRepository::delete);
            accountRepository.deleteById(account.getId());
            academyRepository.deleteById(academy.getId());
        }
    }
}

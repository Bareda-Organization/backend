package src.backend.account.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.account.entity.RefreshToken;

/** {@link RefreshToken} 영속성 접근. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** {@code token_hash} UNIQUE 제약을 그대로 탄다(API_SPEC §2.6 토큰 재발급). */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 계정의 유효(미해지) 토큰 전량을 찾는다(API_SPEC §2.8 비밀번호 변경 시 전량 무효화 대상 조회).
     *
     * <p>조건이 {@code ix_refresh_token_account_active}(부분 인덱스, {@code WHERE revoked_at IS NULL})
     * 와 정확히 일치해야 그 인덱스를 탄다 — {@code revokedAt IS NULL} 을 빼면 전체 스캔으로 떨어진다.
     */
    List<RefreshToken> findAllByAccountIdAndRevokedAtIsNull(Long accountId);

    /**
     * 계정의 유효 토큰 전량을 한 번에 무효화한다(API_SPEC §2.8) — 이미 해지된 행은 건드리지 않는다.
     *
     * <p>엔티티에 세터가 없어(불변 필드) 벌크 JPQL UPDATE 로 처리했다 — 조회 후 각 행을 순회하며
     * 저장하는 대신, 위 조회와 동일한 조건으로 한 번에 갱신해 부분 인덱스 조건과 어긋나지 않게 한다.
     *
     * @return 실제로 무효화된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt "
            + "WHERE t.accountId = :accountId AND t.revokedAt IS NULL")
    int revokeAllValidByAccountId(@Param("accountId") Long accountId, @Param("revokedAt") OffsetDateTime revokedAt);
}

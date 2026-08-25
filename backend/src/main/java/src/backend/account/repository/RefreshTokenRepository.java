package src.backend.account.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.RefreshToken;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link RefreshToken} 영속성 접근. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** {@code token_hash} UNIQUE 제약을 그대로 탄다(API_SPEC §2.6 토큰 재발급). */
    @AcademyScopeExempt(reason = "§2.6 재발급 — 토큰 해시만 들고 시작해 소속 학원이 미상")
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 계정의 유효(미해지) 토큰 전량을 찾는다(API_SPEC §2.8 비밀번호 변경 시 전량 무효화 대상 조회).
     *
     * <p>조건이 {@code ix_refresh_token_account_active}(부분 인덱스, {@code WHERE revoked_at IS NULL})
     * 와 정확히 일치해야 그 인덱스를 탄다 — {@code revokedAt IS NULL} 을 빼면 전체 스캔으로 떨어진다.
     */
    @AcademyScopeExempt(reason = "§2.8 계정 단위 전량 무효화 — 대상이 계정 하나라 학원 범위가 판정에 개입 부재. "
            + "호출부가 인증·본인확인을 마치고 특정한 계정의 id 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 "
            + "그대로 넘기면 타 학원 계정의 토큰 목록을 열람할 수 있다(revokeAllValidByAccountId 와 같은 전제)")
    List<RefreshToken> findAllByAccountIdAndRevokedAtIsNull(Long accountId);

    /**
     * 계정의 유효 토큰 전량을 한 번에 무효화한다(API_SPEC §2.8) — 이미 해지된 행은 건드리지 않는다.
     *
     * <p>엔티티에 세터가 없어(불변 필드) 벌크 JPQL UPDATE 로 처리했다 — 조회 후 각 행을 순회하며
     * 저장하는 대신, 위 조회와 동일한 조건으로 한 번에 갱신해 부분 인덱스 조건과 어긋나지 않게 한다.
     *
     * <p>{@code flushAutomatically = true} 가 반드시 필요하다 — 호출부(로그인 차단 전이·비밀번호
     * 변경·비밀번호 복구)는 전부 이 호출 직전에 {@code Account} 엔티티를 먼저 변경(mutate)해 둔다.
     * {@code clearAutomatically} 만 켜면 벌크 UPDATE 뒤 영속성 컨텍스트를 비우는 시점에 그 미반영
     * (flush 전) {@code Account} 변경분이 플러시되지 않은 채 통째로 버려진다 — 즉 이 벌크 쿼리를 켜는
     * 순간 직전에 바꾼 계정 상태가 조용히 롤백된 것처럼 사라진다(운영 결함, Task 4 발견·수정).
     *
     * <p>{@link Transactional} 을 메서드에 직접 단 이유는 별개다 — {@code @Modifying} 쿼리는 명시적
     * 트랜잭션 안에서만 실행되므로, 호출부가 {@code @Transactional} 을 잊으면 그 자리에서
     * {@code InvalidDataAccessApiUsageException} 으로 실패한다(보완 리뷰 Minor #3). 호출부 트랜잭션에
     * 얹혀가는 대신 여기에 달아, 잊어도 이 메서드 하나는 항상 동작한다.
     *
     * @return 실제로 무효화된 행 수
     */
    @AcademyScopeExempt(reason = "§2.8 계정 단위 전량 무효화 — 대상이 계정 하나라 학원 범위가 판정에 개입 부재. "
            + "호출부(로그인 차단 전이·비밀번호 변경·복구)가 인증·본인확인을 마치고 특정한 계정의 id 만 넘긴다는 "
            + "전제 — 요청 파라미터의 accountId 를 넘기면 타 학원 계정의 토큰을 무효화할 수 있다")
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt "
            + "WHERE t.accountId = :accountId AND t.revokedAt IS NULL")
    int revokeAllValidByAccountId(@Param("accountId") Long accountId, @Param("revokedAt") OffsetDateTime revokedAt);

    /**
     * 로그아웃 요청에 담긴 토큰 1건만 무효화한다(API_SPEC §2.7) — 같은 계정의 다른 유효 토큰(다른
     * 단말)은 건드리지 않는다.
     *
     * <p>{@code WHERE} 에 {@code t.revokedAt IS NULL} 을 넣어 이미 해지된 행은 다시 갱신하지
     * 않는다 — 로그아웃을 두 번 호출해도 최초 해지 시각이 덮어써지지 않게 하기 위함.
     *
     * <p>{@link #revokeAllValidByAccountId} 와 같은 이유로 {@link Transactional} 을 단다(보완 리뷰
     * Minor #3) — 호출부가 트랜잭션을 잊어도 이 메서드 자체가 트랜잭션 경계를 갖는다.
     *
     * @return 실제로 무효화된 행 수(0 이면 이미 무효화됐거나 존재하지 않는 토큰)
     */
    @AcademyScopeExempt(reason = "§2.7 로그아웃 — 토큰 해시만 들고 시작해 소속 학원이 미상(findByTokenHash 와 같은 근거). "
            + "해시가 token_hash UNIQUE 로 행 1건을 특정하므로 학원 조건을 더해도 좁혀지는 것이 부재")
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt "
            + "WHERE t.tokenHash = :tokenHash AND t.revokedAt IS NULL")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("revokedAt") OffsetDateTime revokedAt);
}

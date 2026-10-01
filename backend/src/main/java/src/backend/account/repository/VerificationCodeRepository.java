package src.backend.account.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.entity.VerificationCode;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.account.entity.VerificationPurpose;

/**
 * {@link VerificationCode} 영속성 접근 — 전화번호 복구(API_SPEC §2.9 · Ruling 513)가 쓴다.
 *
 * <p>발급 빈도(번호당 60초 1회·24시간 5회)는 이 테이블의 {@code created_at} 행 수로 세고, 대조 횟수·소비는
 * <b>조건부 UPDATE</b> 로 누적한다 — 읽고 더하고 쓰는 형태는 동시 대조가 같은 값을 읽어 상한을 넘기게 한다.
 */
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /**
     * 연락처·목적으로 가장 최근 발급된 인증 코드를 찾는다(API_SPEC §2.9 아이디·비밀번호 복구 —
     * 사용자가 제출한 코드를 이 결과와 대조한다). 같은 연락처·목적으로 재발송이 일어나면 이전 코드는
     * 무효 취급해야 하므로 최신 1건만 본다.
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    Optional<VerificationCode> findTopByPhoneAndPurposeOrderByCreatedAtDesc(String phone, VerificationPurpose purpose);

    /**
     * 같은 연락처·목적의 미소비 코드를 전부 소진 처리한다 — 새 코드를 발급하기 직전에 부른다.
     *
     * <p>이것이 없으면 대조 상한({@code VerificationCode.MAX_ATTEMPTS})을 우회할 수 있다 — 상한에
     * 닿을 때마다 코드를 재발급받으면 옛 코드도 그대로 살아 있어 유효한 코드가 DB 에 계속 쌓인다
     * (리뷰 라운드 1 C1-④ · m5). {@code consumed_at} 을 채우는 것이 이 스키마의 유일한 무효화
     * 수단이라 "소비" 와 "무효화" 가 같은 컬럼을 쓴다.
     *
     * <p>{@code flushAutomatically} 를 {@code clearAutomatically} 와 함께 켠다 —
     * {@code RefreshTokenRepository#revokeAllValidByAccountId} 와 같은 이유다. 벌크 UPDATE 뒤
     * 영속성 컨텍스트를 비우는 시점에 아직 플러시되지 않은 엔티티 변경분이 있으면 그것이 통째로
     * 버려진다. 지금 호출부는 직전에 엔티티를 변경하지 않지만, 그 전제는 호출부가 늘어나면 언제든
     * 깨지고 깨진 자리는 조용히 실패한다.
     *
     * <p>{@code clearAutomatically} 는 반대 방향의 위험도 함께 만든다 — <b>이 호출 뒤에는 호출 이전에
     * 로드한 엔티티가 전부 detach 된다.</b> (이 호출은 로드한 계정이 없는 발급 경로에서만 부른다) 이 줄 뒤에 {@code account.changePassword(...)} 같은 변경이 한 줄만
     * 들어와도 그 변경은 더티 체킹 대상에서 빠져 예외도 로그도 없이 사라진다. 뒤에서 다시 변경하려면
     * 재조회해야 한다(리뷰 라운드 2 m-3).
     *
     * @return 실제로 무효화된 행 수
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VerificationCode v SET v.consumedAt = :now "
            + "WHERE v.phone = :phone AND v.purpose = :purpose AND v.consumedAt IS NULL")
    int invalidateUnconsumedByPhoneAndPurpose(@Param("phone") String phone,
            @Param("purpose") VerificationPurpose purpose, @Param("now") OffsetDateTime now);

    /** 이 연락처로 {@code since} 이후 발급된 코드 행 수 — 발급 빈도 제한이 센다(목적·소비 여부와 무관). */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    long countByPhoneAndCreatedAtAfter(String phone, OffsetDateTime since);

    /**
     * 대조 시도를 1 올린다 — 상한({@code max})에 닿았거나 이미 소비된 코드는 올리지 않고 0 을 돌려준다(그 코드는 더는
     * 통과하지 않는다). 읽은 값에 1 을 더해 쓰지 않고 DB 가 한 문장으로 더한다.
     *
     * <p>{@code clearAutomatically} 를 켜지 않는다 — 호출부가 이 뒤에 잠근 계정을 변경하므로, 영속성 컨텍스트를 비우면
     * 그 변경이 예외 없이 사라진다.
     *
     * @return 올린 행 수(0 이면 대조할 수 없는 코드)
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE VerificationCode v SET v.attemptCount = v.attemptCount + 1 "
            + "WHERE v.id = :id AND v.consumedAt IS NULL AND v.attemptCount < :max")
    int recordAttempt(@Param("id") Long id, @Param("max") int max);

    /**
     * 만료 전이고 아직 쓰이지 않은 코드를 소비한다 — 같은 코드로 동시에 두 번 통과하는 것을 막는 유일한 자리다.
     *
     * @return 소비한 행 수(0 이면 만료됐거나 다른 요청이 먼저 썼다)
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE VerificationCode v SET v.consumedAt = :now "
            + "WHERE v.id = :id AND v.consumedAt IS NULL AND v.expiresAt >= :now")
    int consumeIfValid(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 이 번호의 발급을 트랜잭션이 끝날 때까지 직렬화한다(R46 FUBE · Ruling 553) — 계정 행을 잠그는 방식은 <b>미등록 번호에는
     * 잠글 행이 없어</b> 동시에 들어온 발급이 둘 다 한도를 통과한다. 등록 번호만 한 건 통과·나머지 429 이면 그 차이가
     * 가입 여부의 단서가 되므로 번호 문자열 자체를 잠금 키로 쓴다.
     *
     * @return 항상 1 — {@code pg_advisory_xact_lock} 이 값을 돌려주지 않아 쿼리가 결과 행을 갖게 하려고 센다
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext(:phone)) AS locked) AS lock_result",
            nativeQuery = true)
    long lockByPhone(@Param("phone") String phone);

    /**
     * {@code cutoff} 보다 오래된 발급 행을 지운다 — 미등록 번호의 요청도 행을 남기게 되어(발급 빈도를 가입 여부와 무관하게
     * 세려고) 번호를 바꿔 가며 보내는 요청이 행을 끝없이 쌓을 수 있다. 하루가 지난 행은 어느 제한({@code 60초·24시간})에도
     * 세어지지 않고 코드도 만료돼 있어 지워도 동작이 달라지지 않는다.
     */
    @AcademyScopeExempt(reason = "§2.9 계정 복구 — 전화번호만 들고 시작해 소속 학원이 미상")
    @Transactional
    @Modifying
    @Query("DELETE FROM VerificationCode v WHERE v.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") OffsetDateTime cutoff);
}

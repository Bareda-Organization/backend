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

/** {@link VerificationCode} 영속성 접근. */
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
     * 로드한 엔티티가 전부 detach 된다.</b> {@code RecoverCommandService#recover} 는 이 호출보다 먼저
     * {@code account} 를 로드하므로, 이 줄 뒤에 {@code account.changePassword(...)} 같은 변경이 한 줄만
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
}

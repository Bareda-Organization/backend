package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationSetting;

/**
 * {@link NotificationSetting} 영속성 접근(NTF-07).
 *
 * <p>PK 가 곧 {@code account_id} 라 {@code AcademySettingRepository} 와 같은 이유로 별도의 소유자
 * 조건을 붙이지 않는다 — {@code findById(accountId)} 자체가 이미 그 계정으로 좁혀져 있다.
 */
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    /**
     * 설정 행이 없으면 기본값(전부 on)으로 만든다 — 이미 있으면 아무것도 하지 않는다. 같은 계정의 첫 변경이 두 기기에서 겹쳐도 한 번만 만들어지고
     * 진 쪽이 기본 키 위반(500)을 받지 않는다. 읽고 없으면 저장하는 두 걸음은 두 요청이 모두 "없음" 을 보는 창이 있다.
     *
     * @return 1 = 새로 만듦, 0 = 이미 있음
     */
    @AcademyScopeExempt(reason = "§3.14 본인 설정 — notification_setting 은 account 부모 경유라 계정이 곧 학원 범위이고, 호출부가 토큰의 "
            + "accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at)
            VALUES (:accountId, true, true, true, :now)
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int createIfAbsent(@Param("accountId") Long accountId, @Param("now") OffsetDateTime now);

    /**
     * 설정 행을 <b>행 잠금</b>으로 읽는다 — 부분 변경은 읽은 행에 보낸 토글만 얹어 쓰는데, 변경 감지는 세 토글 열을 모두 쓰므로 같은 계정의 서로 다른 토글 변경 두 건이 잠금 없이 겹치면
     * 한쪽이 다른 쪽의 옛 값을 되써 변경이 사라진다. 잠그면 뒤 요청이 앞 요청의 커밋을 본 뒤 읽는다.
     */
    @AcademyScopeExempt(reason = "§3.14 본인 설정 — notification_setting 은 account 부모 경유라 계정이 곧 학원 범위이고, 호출부가 토큰의 "
            + "accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NotificationSetting s WHERE s.accountId = :accountId")
    Optional<NotificationSetting> findLockedById(@Param("accountId") Long accountId);
}

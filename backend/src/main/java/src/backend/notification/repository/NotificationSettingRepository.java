package src.backend.notification.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
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
}

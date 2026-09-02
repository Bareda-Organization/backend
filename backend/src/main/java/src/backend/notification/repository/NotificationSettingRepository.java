package src.backend.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.notification.entity.NotificationSetting;

/**
 * {@link NotificationSetting} 영속성 접근(Phase 12 목표 7·8, NTF-07).
 *
 * <p>PK 가 곧 {@code account_id} 라 {@code AcademySettingRepository} 와 같은 이유로 별도의 소유자
 * 조건을 붙이지 않는다 — {@code findById(accountId)} 자체가 이미 그 계정으로 좁혀져 있다.
 */
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {
}

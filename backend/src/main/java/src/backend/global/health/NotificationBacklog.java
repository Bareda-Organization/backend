package src.backend.global.health;

import java.time.OffsetDateTime;

/**
 * 관제 헬스(API_SPEC §6.18 {@code health[]} {@code notification})가 읽는 발송 적체의 포트 — 알림 모듈만 알림 표를 알고, 다른 모듈은 알림 모듈을 참조하지 않는다는 구조 규칙
 * ({@code NotificationModuleIsolationTest}) 때문에 전역에 계약을 두고 알림 모듈이 구현한다.
 */
public interface NotificationBacklog {

    /** {@code threshold} 이전에 적재되고 아직 발송 대기({@code pending})인 알림 수 — 서버 단위 값이라 학원 필터가 없다. */
    long countPendingCreatedBefore(OffsetDateTime threshold);
}

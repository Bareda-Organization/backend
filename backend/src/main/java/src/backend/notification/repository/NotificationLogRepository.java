package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;

/**
 * 알림 아웃박스의 적재·회수·발송 상태 전이와 관계자 웹의 알림 로그 조회를 한 저장소로 묶는다. 질의는 묶음별 조각 인터페이스에 나뉘어 있고
 * 이 인터페이스가 전부 상속한다 — 발송 상태 전이 {@link NotificationLogStateTransitions}(선점 · 성공 · 실패 · 건너뜀 · 시도 되돌리기) ·
 * 워커 회수 조회 {@link NotificationLogRetryQueries}(재시도 후보 · 정체 행 실패 전이) · 수신자 알림함 {@link NotificationLogInboxQueries}(목록 · 미읽음 수 ·
 * 읽음 처리 · 수신 확인) · 관계자 알림 로그 조회 {@link NotificationLogStaffLogQueries}(목록 · 묶어 보기 · 미확인 배지). 보존 정리 후보 id 와
 * 관제 적체 수는 이 인터페이스에 직접 둔다. 호출부는 조각 인터페이스가 아니라 {@code NotificationLogRepository} 로 모든 메서드를 부른다.
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long>, NotificationLogStateTransitions,
        NotificationLogRetryQueries, NotificationLogInboxQueries, NotificationLogStaffLogQueries {

    /**
     * 보존 정리 배치 후보 id — {@code created_at} 기준으로 자른다.
     *
     * <p>{@code sent_at} 이 아니라 {@code created_at} 을 쓴 이유 — {@code sent_at} 은
     * {@code push_state='sent'} 일 때만 채워진다(ERD notification_log 컬럼표). {@code failed} ·
     * {@code skipped} · 아직 워커가 못 집은 {@code pending} 행은 {@code sent_at} 이 영원히 NULL이라,
     * 그 컬럼으로 컷오프를 걸면 발송에 실패한 행일수록 보존 대상에서 영영 빠진다. 보관 14일은
     * "레코드"의 보관 기간이지 "발송 성공"의 보관 기간이 아니다(ERD §7.2 "notification_log — 14일 —
     * 알림 보관 기간").
     *
     * <p>전 학원의 만료 로그가 대상이라 학원 조건을 걸지 않는다 — 걸면 그 학원 밖 로그가 영영
     * 정리되지 않는다({@link #findRetryCandidates} 와 같은 축).
     */
    @AcademyScopeExempt(reason = "보존 정리 배치 — 전 학원의 만료 로그 전건이 대상이고, "
            + "부르는 주체가 사용자 요청이 아니라 스케줄러라 요청 주체의 소속 자체가 부재")
    @Query("select n.id from NotificationLog n where n.createdAt < :cutoff order by n.id")
    List<Long> findIdsForRetentionCleanup(@Param("cutoff") OffsetDateTime cutoff, Limit limit);

    /** {@code threshold} 이전에 적재되고 아직 발송 대기({@code pending})인 알림 수 — 관제 헬스(API_SPEC §6.18)의 적체 값이다. */
    @AcademyScopeExempt(reason = "관제 헬스는 서버 단위 값이라 학원 필터가 없다 — 전 학원의 발송 대기 적체를 한 숫자로 센다")
    @Query("select count(n) from NotificationLog n where n.pushState = src.backend.notification.entity.PushState.PENDING "
            + "and n.createdAt <= :threshold")
    long countPendingCreatedBefore(@Param("threshold") OffsetDateTime threshold);
}

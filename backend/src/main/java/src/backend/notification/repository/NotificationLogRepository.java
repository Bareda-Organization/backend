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
 * 아웃박스 행의 적재·회수·발송 상태 전이(TECH_DECISIONS §7.2) + 관계자 웹의 알림 로그 조회
 * (API_SPEC §5.17, NTF-10·11, A-13).
 *
 * <p>상태 전이를 전부 {@code @Modifying} 조건부 UPDATE 로 두는 이유는 §7 규칙 3 이다 — 변경 감지는
 * "읽고 판단하고 쓰는" 사이에 창이 생겨, 즉시 발송과 워커가 같은 행을 함께 집는다.
 *
 * <p>전이 메서드가 전부 {@code REQUIRES_NEW} 인 것은 호출 시점 때문이다. 즉시 발송은
 * {@code AFTER_COMMIT} 리스너에서 부르는데, 그 시점의 스레드에는 <b>이미 커밋된</b> 트랜잭션의
 * {@code EntityManager} 가 아직 묶여 있다. {@code REQUIRED} 로 두면 그 끝난 트랜잭션에 합류해
 * UPDATE 가 아무 데도 반영되지 않고, 그런데도 예외가 부재해 초록으로 지나간다.
 *
 * <p><b>조회 메서드({@code searchForStaffLog}·{@code countUnackedForStaffLog})는 §5.17 전용이다.</b>
 * 이름에 {@code ForStaffLog} 를 붙인 이유는 이 파일이 전이 메서드와 조회 메서드를 함께 갖게 되면서,
 * 양쪽 담당이 서로 다른 뜻으로 같은 이름을 지어 충돌하는 것이 실제 위험이기 때문이다(Ruling 222) —
 * 아웃박스 전이 메서드는 전부 단건({@code id} 로 특정)이라 이름이 겹칠 일이 없지만, 조회는 그렇지
 * 않다. 이 인터페이스는 질의 묶음별 조각 인터페이스({@link NotificationLogStateTransitions} ·
 * {@link NotificationLogRetryQueries} · {@link NotificationLogInboxQueries} · {@link NotificationLogStaffLogQueries})를 상속해 하나로 합친다 —
 * 저장소 빈은 엔티티 하나에 이것 하나이고 호출부는 이 이름으로 모든 메서드를 부른다(Ruling 222, 크기 분할).
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

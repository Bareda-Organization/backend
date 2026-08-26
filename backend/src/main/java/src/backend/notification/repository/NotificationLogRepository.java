package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;

/**
 * 아웃박스 행의 적재·회수·발송 상태 전이(TECH_DECISIONS §7.2).
 *
 * <p>상태 전이를 전부 {@code @Modifying} 조건부 UPDATE 로 두는 이유는 §7 규칙 3 이다 — 변경 감지는
 * "읽고 판단하고 쓰는" 사이에 창이 생겨, 즉시 발송과 워커가 같은 행을 함께 집는다.
 *
 * <p>전이 메서드가 전부 {@code REQUIRES_NEW} 인 것은 호출 시점 때문이다. 즉시 발송은
 * {@code AFTER_COMMIT} 리스너에서 부르는데, 그 시점의 스레드에는 <b>이미 커밋된</b> 트랜잭션의
 * {@code EntityManager} 가 아직 묶여 있다. {@code REQUIRED} 로 두면 그 끝난 트랜잭션에 합류해
 * UPDATE 가 아무 데도 반영되지 않고, 그런데도 예외가 부재해 초록으로 지나간다.
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * 발송에 성공한 행을 {@code sent} 로 옮긴다 — {@code pending} 인 행만 대상이라 이미 옮겨진 행을
     * 두 번 세지 않는다.
     *
     * @return 1 = 전이함, 0 = 다른 실행이 이미 옮김
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 상태 전이 — 대상이 id 로 특정된 단건이고, 부르는 주체가 "
            + "사용자 요청이 아니라 발송 절차라 학원 범위가 판정에 개입 부재. 그 id 는 같은 절차가 회수한 "
            + "행에서만 나온다(NotificationDispatcher)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushState = :sent, n.sentAt = :now, n.failReason = null
             where n.id = :id and n.pushState = :pending
            """)
    int markSent(@Param("id") Long id, @Param("pending") PushState pending, @Param("sent") PushState sent,
            @Param("now") OffsetDateTime now);

    /**
     * 발송에 실패한 행에 사유를 남긴다. 다음 상태는 호출부가 정한다 — 재시도 상한 미만이면
     * {@code pending} 으로 남겨 워커가 다시 집고, 상한에 닿았으면 {@code failed} 다.
     *
     * <p>상한 판정을 SQL 안에 넣지 않는 이유는 {@code case when} 이 늘어날수록 "언제 포기하는가" 라는
     * 정책이 쿼리 문자열로 숨기 때문이다 — 정책은 {@code NotificationRetryPolicy} 한 곳에 둔다.
     *
     * @return 1 = 기록함, 0 = 이미 {@code pending} 이 아님
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 상태 전이 — markSent 와 같은 축이다. 대상이 id 로 특정된 "
            + "단건이고 학원 범위가 판정에 개입 부재")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushState = :nextState, n.failReason = :reason
             where n.id = :id and n.pushState = :pending
            """)
    int markAttemptFailed(@Param("id") Long id, @Param("pending") PushState pending,
            @Param("nextState") PushState nextState, @Param("reason") String reason);

    /**
     * 워커가 다시 집어야 할 후보 — {@code pending} 이고 재시도 상한에 닿지 않았으며, 아직 시도한 적이
     * 없거나 마지막 시도로부터 최소 간격이 지난 행이다.
     *
     * <p>{@code push_state = 'pending'} 조건이 이 질의의 전부다 — 빠지면 워커가 <b>이미 보낸 알림을
     * 전부 재발송</b>한다. {@code ERD §5} 의 부분 인덱스({@code WHERE push_state='pending'})가 가리키는
     * 술어가 이것이다.
     *
     * <p>지수 백오프의 <b>회차별</b> 간격은 여기서 판정하지 않는다 — 파라미터 하나로는 표현 불가라
     * 최소 간격까지만 SQL 로 좁히고 나머지는 {@code NotificationRetryPolicy} 가 거른다.
     */
    @AcademyScopeExempt(reason = "아웃박스 워커의 미발송분 회수 — 전 학원의 미발송 알림이 대상이고, "
            + "학원으로 좁히면 그 학원 밖 알림이 영영 재발송되지 않는다. 부르는 주체가 사용자 요청이 아니라 "
            + "스케줄러라 요청 주체의 소속 자체가 부재(ARCHITECTURE §11)")
    @Query("""
            select n from NotificationLog n
             where n.pushState = :pending
               and n.pushAttempts < :maxAttempts
               and (n.lastAttemptAt is null or n.lastAttemptAt <= :attemptedBefore)
             order by n.createdAt
            """)
    List<NotificationLog> findRetryCandidates(@Param("pending") PushState pending,
            @Param("maxAttempts") int maxAttempts, @Param("attemptedBefore") OffsetDateTime attemptedBefore,
            Pageable limit);

    /**
     * 발송을 시도했다는 사실을 행에 남긴다 — 시도 횟수를 올리고 마지막 시도 시각을 찍는다.
     *
     * <p>시도 기록이 없으면 재시도 상한도 지수 백오프도 판정할 근거가 부재해, 죽은 단말 하나가
     * 워커의 매 틱을 영원히 잡아먹는다.
     *
     * @return 1 = 기록함
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 시도 기록 — markSent 와 같은 축이다. 대상이 id 로 특정된 "
            + "단건이고 학원 범위가 판정에 개입 부재")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushAttempts = n.pushAttempts + 1, n.lastAttemptAt = :now
             where n.id = :id
            """)
    int recordAttempt(@Param("id") Long id, @Param("now") OffsetDateTime now);
}

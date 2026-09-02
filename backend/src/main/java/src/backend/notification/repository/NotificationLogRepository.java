package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
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
     * 발송할 행을 <b>선점</b>한다 — 시도 횟수를 올리고 마지막 시도 시각을 찍는 한 번의 조건부 UPDATE 다.
     *
     * <p>시도 기록과 선점이 같은 문장인 것이 요점이다. 기록만 하는 무조건 UPDATE 로 두면 즉시 발송과
     * 워커가 <b>둘 다</b> 통과해 수신자가 같은 알림을 두 번 받는다 — 그때도 행은 1건이라
     * {@code dedup_key} UNIQUE 는 아무것도 막지 못한다. 이 경합은 INSERT 경합이 아니라 선점 경합이다.
     *
     * <p>{@code lastAttemptAt} 조건이 선점의 본체다. 진 쪽은 이긴 쪽의 UPDATE 가 커밋될 때까지 행
     * 잠금에서 기다렸다가 <b>새 값으로</b> 조건을 다시 보고 0 행을 얻는다. 그래서
     * {@code MIN_RETRY_INTERVAL} 이 0 이면 안 된다 — 0 이면 방금 찍힌 시각도 조건을 통과한다.
     *
     * @param attemptedBefore 이 시각보다 이전에 시도된 행만 다시 집을 수 있다
     * @return 1 = 선점 성공, 0 = 다른 실행이 이미 집었거나 {@code pending} 이 아님
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 선점 — markSent 와 같은 축이다. 대상이 id 로 특정된 "
            + "단건이고 학원 범위가 판정에 개입 부재")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushAttempts = n.pushAttempts + 1, n.lastAttemptAt = :now
             where n.id = :id and n.pushState = :pending
               and (n.lastAttemptAt is null or n.lastAttemptAt <= :attemptedBefore)
            """)
    int claim(@Param("id") Long id, @Param("pending") PushState pending,
            @Param("attemptedBefore") OffsetDateTime attemptedBefore, @Param("now") OffsetDateTime now);

    /**
     * 알림 목록 조회(API_SPEC §3.12, Phase 12 T2) — {@code type}·{@code unreadOnly} 가 선택이고
     * 보관 14일이 항상 걸린다.
     *
     * <p>{@code type} 은 {@code :type IS NULL OR ...} 형태를 쓴다({@code ExceptionReportRepository#search}
     * 와 같은 근거) — enum 파라미터는 null 이어도 Postgres 가 타입을 추론한다(같은 파일 재확인).
     * {@code unreadOnly} 는 boolean 이라 {@code :unreadOnly = false OR ...} 로 충분하다 — boolean
     * 파라미터는 항상 값이 있어 {@code OffsetDateTime} 과 같은 추론 실패가 재현되지 않는다.
     *
     * <p>{@code retentionFrom} 은 "필터 없음"을 널로 표현하지 않는다 — 14일은 이 목록의
     * <b>항상-켜진</b> 조건이라 선택 필터와 성격이 다르고, 호출부({@code NotificationQueryService})가
     * 이미 구체 시각을 계산해 넘긴다.
     *
     * <p>정렬은 {@code created_at desc}(+ {@code id desc} 동점 결정)다 — {@code sent_at} 은 아직
     * 발송 전이거나(즉시 발송 창) 설정 off 로 건너뛴 행에서 널이라 정렬 축으로 쓰면 그 행들이
     * 순서 없이 흩어진다. {@code ERD notification_log(recipient_account_id, created_at desc)}
     * 인덱스가 가리키는 축과도 같다.
     */
    @Query("""
            select n from NotificationLog n
             where n.academyId = :academyId
               and n.recipientAccountId = :accountId
               and (:type is null or n.type = :type)
               and (:unreadOnly = false or n.readAt is null)
               and n.createdAt >= :retentionFrom
             order by n.createdAt desc, n.id desc
            """)
    Page<NotificationLog> search(@Param("academyId") Long academyId, @Param("accountId") Long accountId,
            @Param("type") NotificationType type, @Param("unreadOnly") boolean unreadOnly,
            @Param("retentionFrom") OffsetDateTime retentionFrom, Pageable pageable);

    /**
     * 미읽음 배지(API_SPEC §3.12 {@code unread_count}) — {@code items[]} 에 걸리는 {@code type}·
     * {@code unreadOnly} 필터와 <b>무관하게</b> 항상 전체 미읽음 수다. 보관 14일만 함께 건다
     * (ERD {@code notification_log(recipient_account_id, created_at desc)} 인덱스 주석이 목록·배지를
     * 같은 축으로 묶어 뒀다) — 14일보다 오래된 미읽음까지 세면 배지가 목록에 없는 항목을 가리키게 된다.
     */
    @Query("""
            select count(n) from NotificationLog n
             where n.academyId = :academyId
               and n.recipientAccountId = :accountId
               and n.readAt is null
               and n.createdAt >= :retentionFrom
            """)
    long countUnread(@Param("academyId") Long academyId, @Param("accountId") Long accountId,
            @Param("retentionFrom") OffsetDateTime retentionFrom);
}

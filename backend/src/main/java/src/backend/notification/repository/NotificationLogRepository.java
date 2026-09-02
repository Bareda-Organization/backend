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
 * 않다. 이 인터페이스에 리포지토리를 <b>더 만들지 않고</b> 여기 얹은 이유는 이 저장소의 47개 리포지토리
 * 전부가 엔티티 1:1 이라 2개로 가르는 선례가 없기 때문이다(Ruling 222) — 격리 워크트리에서 별도 파일로
 * 만들었다가 병합 전 조율자 판정으로 되돌렸다.
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
     * 알림 설정이 꺼져 있어 푸시를 보내지 않고 건너뛴 행을 {@code skipped} 로 옮긴다(Phase 12 목표 8,
     * API_SPEC §3.14). {@code markSent} 와 같은 이유로 {@code pending} 인 행만 대상이다 — 즉시 발송과
     * 워커가 같은 행을 함께 집어도 한쪽만 반영된다.
     *
     * <p>{@code claim} 을 거치지 않는다 — 이 전이는 <b>발송 시도가 아니라 발송을 아예 하지 않기로 한
     * 판정</b>이라, 재시도 횟수({@code push_attempts})를 올리면 "몇 번 시도했다가 실패했나" 라는 그
     * 카운터의 의미가 흐려진다. 대신 이 메서드 자체가 {@code pending} 단건 조건부 UPDATE라 선점과
     * 동등한 경합 안전성을 갖는다.
     *
     * @return 1 = 전이함, 0 = 다른 실행이 이미 옮김(중복 호출 안전)
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 상태 전이 — markSent 와 같은 축이다. 대상이 id 로 특정된 "
            + "단건이고 학원 범위가 판정에 개입 부재")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushState = :skipped
             where n.id = :id and n.pushState = :pending
            """)
    int markSkipped(@Param("id") Long id, @Param("pending") PushState pending,
            @Param("skipped") PushState skipped);

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

    /**
     * 알림 로그 목록(§5.17) — {@code type}·{@code acked} 는 선택적 필터이고 날짜 구간은 항상 구체값이다
     * ({@link src.backend.exception.repository.ExceptionReportRepository#search} 와 같은 근거 —
     * {@code OffsetDateTime} 단독 {@code IS NULL} 비교는 Postgres 파라미터 타입 추론이 실패한다).
     *
     * <p>{@code push_state} 조건을 두지 않는다 — 이 목록은 "전송 알림 전수 조회" 라 푸시 off 로
     * 막혀 {@code SKIPPED} 로 남은 행도 그대로 나와야 한다(§5.17 "푸시 off 로 차단된 건도 레코드로
     * 존치"). {@code sent_at} 이 비어 있을 수 있는 행({@code SKIPPED}·아직 안 보낸 {@code PENDING})은
     * {@code COALESCE(sent_at, created_at)} 으로 정렬·기간필터 모두를 대신한다 — 그렇지 않으면 그런
     * 행이 날짜 필터를 걸 때마다 조용히 빠진다.
     */
    @Query("""
            SELECT n FROM NotificationLog n
             WHERE n.academyId = :academyId
               AND (:type IS NULL OR n.type = :type)
               AND (:acked IS NULL OR n.acked = :acked)
               AND COALESCE(n.sentAt, n.createdAt) >= :from
               AND COALESCE(n.sentAt, n.createdAt) < :to
             ORDER BY COALESCE(n.sentAt, n.createdAt) DESC, n.id DESC
            """)
    Page<NotificationLog> searchForStaffLog(@Param("academyId") Long academyId, @Param("type") NotificationType type,
            @Param("acked") Boolean acked, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * 미확인 배지(§5.17 {@code unacked_count}, ERD §5 부분 인덱스 {@code notification_log(academy_id,
     * acked) WHERE acked = false}) — 목록의 페이지·필터와 무관하게 <b>학원 전체</b>에서 센다. 페이지
     * 안에서만 세면 필터를 걸 때마다 배지 값이 달라져 "확인 안 한 것이 몇 건인가" 라는 원래 의미를
     * 잃는다.
     *
     * <p>정본이 "중요 통지" 만 추적한다고 적지만(FEATURE_SPEC §4.15 NTF-10), 그 분류를 어느
     * {@link NotificationType} 값에 매길지는 정본 어디에도 없다(§9.7·§4.15 재확인 — 판정 근거·열거값
     * 표 부재, 오픈 이슈 X 목록에도 없음). 그래서 이 카운트는 <b>전 종류를 동일하게</b> 센다 — 근거
     * 없이 일부 종류를 빼면 그 자체가 임의 판단이 된다. 정본이 분류를 명시하면 이 조건을 좁힌다.
     */
    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.academyId = :academyId AND n.acked = false")
    long countUnackedForStaffLog(@Param("academyId") Long academyId);
}

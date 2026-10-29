package src.backend.notification.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;

/**
 * 아웃박스 행의 발송 상태 전이 — 선점 · 성공 · 실패 · 건너뜀 · 시도 되돌리기. 전부 조건부 UPDATE 단건이고 {@link NotificationLogRepository} 가 상속한다(호출부는 그 저장소로 부른다).
 */
@NoRepositoryBean
public interface NotificationLogStateTransitions extends Repository<NotificationLog, Long> {

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
     * 알림 설정이 꺼져 있어 푸시를 보내지 않고 건너뛴 행을 {@code skipped} 로 옮긴다(
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
     * 발송기가 채널을 부르지 않고 거절한 행의 시도 횟수를 되돌리고 사유를 남긴다(Ruling 899 E) — 상태는 {@code pending} 그대로다.
     * 선점({@link #claim})이 이미 올린 횟수를 내리는 것이라 {@code push_attempts > 0} 조건을 둔다.
     *
     * @return 1 = 되돌림, 0 = 이미 {@code pending} 이 아니거나 횟수가 0
     */
    @AcademyScopeExempt(reason = "아웃박스 발송 상태 전이 — markSent 와 같은 축이다. 대상이 id 로 특정된 "
            + "단건이고 학원 범위가 판정에 개입 부재")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushAttempts = n.pushAttempts - 1, n.failReason = :reason
             where n.id = :id and n.pushState = :pending and n.pushAttempts > 0
            """)
    int releaseAttempt(@Param("id") Long id, @Param("pending") PushState pending, @Param("reason") String reason);

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
}

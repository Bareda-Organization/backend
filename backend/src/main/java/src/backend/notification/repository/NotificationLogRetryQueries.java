package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.entity.PushState;

/**
 * 아웃박스 워커의 회수 조회 — 다시 집을 후보와 정체 행 실패 전이. {@link NotificationLogRepository} 가 상속한다.
 */
@NoRepositoryBean
public interface NotificationLogRetryQueries extends Repository<NotificationLog, Long> {

    /**
     * 워커가 다시 집어야 할 후보 — {@code pending} 이고 재시도 상한에 닿지 않았으며(비상 알림은 상한과 무관하게 후보 — 포기 시점은
     * {@code NotificationRetryPolicy#exhausted} 가 정한다), 아직 시도한 적이 없거나 마지막 시도로부터 최소 간격이 지난 행이다.
     *
     * <p>{@code push_state = 'pending'} 조건이 이 질의의 전부다 — 빠지면 워커가 <b>이미 보낸 알림을
     * 전부 재발송</b>한다. {@code ERD §5} 의 부분 인덱스({@code WHERE push_state='pending'})가 가리키는
     * 술어가 이것이다.
     *
     * <p>지수 백오프의 <b>회차별</b> 간격은 여기서 판정하지 않는다 — 파라미터 하나로는 표현 불가라
     * 최소 간격까지만 SQL 로 좁히고 나머지는 {@code NotificationRetryPolicy} 가 거른다.
     *
     * <p>정렬은 <b>아직 시도하지 않은 행이 먼저, 그다음 마지막 시도가 오래된 순</b>이다(BR-222) — 생성순으로 자르면
     * 백오프 대기 중인 옛 행이 한 틱의 상한(100건)을 채워 새 알림(비상 포함)이 후보에서 밀린다. 마지막 시도가 오래된
     * 행이 백오프가 먼저 풀리므로 대기 중인 행끼리도 곧 보낼 것이 앞선다.
     * ponytail: 종류별 백오프 차이(비상 10초 · 그 밖 1분)까지 SQL 로 옮기지 않아, 마지막 시도가 더 오래된 대기 행
     * 100건 뒤의 비상 재시도는 최대 1분 늦을 수 있다 — 그게 관측되면 비상 종류를 별도 질의로 먼저 조회한다.
     */
    @AcademyScopeExempt(reason = "아웃박스 워커의 미발송분 회수 — 전 학원의 미발송 알림이 대상이고, "
            + "학원으로 좁히면 그 학원 밖 알림이 영영 재발송되지 않는다. 부르는 주체가 사용자 요청이 아니라 "
            + "스케줄러라 요청 주체의 소속 자체가 부재(ARCHITECTURE §11)")
    @Query("""
            select n from NotificationLog n
             where n.pushState = src.backend.notification.entity.PushState.PENDING
               and (n.pushAttempts < :maxAttempts or n.type in (
                    src.backend.notification.entity.NotificationType.EMERGENCY,
                    src.backend.notification.entity.NotificationType.EMERGENCY_CANCELED))
               and (n.lastAttemptAt is null or n.lastAttemptAt <= :attemptedBefore)
             order by n.lastAttemptAt asc nulls first, n.createdAt
            """)
    List<NotificationLog> findRetryCandidates(@Param("maxAttempts") int maxAttempts, @Param("attemptedBefore") OffsetDateTime attemptedBefore,
            Pageable limit);

    /**
     * 시도 상한에 닿은 채 {@code pending} 으로 멈춘 행을 {@code failed} 로 옮긴다(BR-225) — 선점({@link NotificationLogStateTransitions#claim})이
     * 시도 횟수를 먼저 올리고 결과 기록은 나중이라, 마지막 시도 도중 프로세스가 죽으면 행이 {@code pending}·상한 횟수로
     * 남아 후보 조건({@code push_attempts < 상한})에도 실패 전이에도 걸리지 않는다.
     *
     * <p>{@code stalledBefore} 이전에 시도된 행만 옮긴다 — 방금 마지막 시도를 선점해 발송 중인 행은 남긴다. 비상 알림은 대상이 아니다 — 워커가 상한과
     * 무관하게 다시 집어 포기 시점을 정책이 정한다.
     *
     * @return 옮긴 행 수
     */
    @AcademyScopeExempt(reason = "아웃박스 워커의 정체 행 회수 — 전 학원의 알림이 대상이고 부르는 주체가 스케줄러라 "
            + "요청 주체의 소속 자체가 부재(ARCHITECTURE §11)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationLog n
               set n.pushState = :failed, n.failReason = :reason
             where n.pushState = src.backend.notification.entity.PushState.PENDING and n.pushAttempts >= :maxAttempts
               and n.type not in (src.backend.notification.entity.NotificationType.EMERGENCY,
                    src.backend.notification.entity.NotificationType.EMERGENCY_CANCELED)
               and n.lastAttemptAt <= :stalledBefore
            """)
    int failStalled(@Param("failed") PushState failed,
            @Param("maxAttempts") int maxAttempts, @Param("stalledBefore") OffsetDateTime stalledBefore,
            @Param("reason") String reason);
}

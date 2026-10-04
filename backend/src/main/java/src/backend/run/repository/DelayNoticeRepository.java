package src.backend.run.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.DelayNotice;

/** {@link DelayNotice} 영속성 접근. */
public interface DelayNoticeRepository extends JpaRepository<DelayNotice, Long> {

    /**
     * 그 회차의 직전 발신 1건(Ruling 253 중복·갱신 판정의 대조 대상) — {@code idx_delay_notice_run_sent_at}
     * ({@code run_id}, {@code sent_at DESC})이 이 조회를 받친다.
     *
     * <p>{@code runId} 는 호출부(지연 알림 커맨드 서비스)가 {@code RunAssignmentAccess
     * #assertAssignedEscort} 로 이미 학원 범위에 좁혀 확인한 회차의 식별자라는 전제다 —
     * {@code RunRiderRepository#findByRunIdAndStudentId} 와 같은 근거.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunAssignmentAccess#assertAssignedEscort 로 이미 학원 범위에 "
            + "좁혀 확인한 회차의 식별자라는 전제다 — RunRiderRepository#findByRunIdAndStudentId 와 같은 근거")
    Optional<DelayNotice> findFirstByRunIdOrderBySentAtDesc(Long runId);

    /**
     * 그 회차의 <b>마지막</b> 발신 1건(§3.11 {@code delay} · §5.3 {@code last_delay_notice}, Ruling 810·821) — 기준은 {@code id} 다. 발신은 회차 안에서
     * 차례로 쌓이므로 {@code id} 가 곧 발신 순서이고, 같은 시각({@code sent_at})에 두 건이 있어도 뒤의 것이 이긴다.
     *
     * <p>{@code runId} 는 호출부가 학원 범위로 이미 좁혀 확인한 회차의 식별자라는 전제다({@link #findFirstByRunIdOrderBySentAtDesc} 와 같은 근거).
     */
    @AcademyScopeExempt(reason = "runId 는 호출부(StudentBusPositionQueryService)가 StudentRunsAccess · StudentRunResolver 로 이미 학원 범위에 "
            + "좁혀 확인한 회차의 식별자라는 전제다 — findFirstByRunIdOrderBySentAtDesc 와 같은 근거")
    Optional<DelayNotice> findFirstByRunIdOrderByIdDesc(Long runId);

    /**
     * 회차들의 <b>마지막</b> 발신을 회차당 1건씩(§5.3 {@code last_delay_notice}, Ruling 810) — 기준은 {@link #findFirstByRunIdOrderByIdDesc} 와 같은
     * {@code id} 다. 학원 조건은 {@code run} 조인에 건다({@code delay_notice} 는 {@code academy_id} 컬럼이 없는 부모 경유 자원이다).
     */
    @Query("SELECT d FROM DelayNotice d JOIN Run r ON r.id = d.runId "
            + "WHERE r.academyId = :academyId AND d.runId IN :runIds "
            + "AND d.id = (SELECT MAX(d2.id) FROM DelayNotice d2 WHERE d2.runId = d.runId)")
    List<DelayNotice> findLastByRunIds(@Param("academyId") Long academyId, @Param("runIds") Collection<Long> runIds);

    /**
     * 지연 알림 한 건이 적재한 알림 행 수(§5.3 {@code last_delay_notice.recipient_count}, Ruling 810) — 알림 적재(아웃박스)는 지연 알림과 같은
     * 트랜잭션에서 수신자마다 한 행씩 넣고 {@code dedup_key} 를 {@code delay:{runId}:…} 로 시작하게 한다. 그 행들은 {@code run_id} 를 채우지
     * 않아 키가 회차를 가리키는 유일한 단서이고, 적재 시각({@code created_at})은 발신 시각({@code delay_notice.sent_at}) 이후라 그 이후 행만 이 알림의
     * 몫으로 센다 — {@code noticeIds} 는 회차별 마지막 알림이라 뒤에 더 늦은 알림이 없다.
     *
     * <p>알림 모듈의 저장소를 거치지 않고 표를 직접 읽는 이유: 다른 모듈이 알림 모듈을 참조하지 않는다는 구조 규칙
     * ({@code NotificationModuleIsolationTest}) 때문이다 — 읽기 전용이고 알림 적재 모양({@code DelayNotificationListener})에 기대는 값이라
     * 실제 지연 알림 API 를 거친 건수와 같음을 시험으로 고정했다.
     */
    @Query(value = """
            SELECT d.run_id AS "runId", COUNT(n.id) AS "recipientCount"
              FROM delay_notice d
              JOIN run r ON r.id = d.run_id
              JOIN notification_log n ON n.type = 'delay' AND n.academy_id = r.academy_id
                                     AND n.dedup_key LIKE 'delay:' || d.run_id || ':%' AND n.created_at >= d.sent_at
             WHERE r.academy_id = :academyId AND d.id IN (:noticeIds)
             GROUP BY d.run_id
            """, nativeQuery = true)
    List<DelayRecipientCount> countRecipientsOfNotices(@Param("academyId") Long academyId,
            @Param("noticeIds") Collection<Long> noticeIds);

    /**
     * 최근 발신한 지연 알림(§6.18 {@code recent_events[]} {@code delay_notified}) — 학원 조건은 {@code run} 조인에 건다. 알림은 운행 중 회차에서만 나가므로
     * 운행일 하한({@code sinceDate})으로 먼저 좁히고 발신 시각으로 거른다.
     */
    @Query("SELECT d FROM DelayNotice d JOIN Run r ON r.id = d.runId WHERE r.academyId IN :academyIds "
            + "AND r.serviceDate >= :sinceDate AND d.sentAt >= :since ORDER BY d.sentAt DESC, d.id DESC")
    List<DelayNotice> findRecent(@Param("academyIds") Collection<Long> academyIds,
            @Param("sinceDate") java.time.LocalDate sinceDate, @Param("since") java.time.OffsetDateTime since,
            org.springframework.data.domain.Limit limit);
}

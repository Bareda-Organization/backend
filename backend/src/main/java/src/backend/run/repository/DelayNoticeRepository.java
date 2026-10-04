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
}

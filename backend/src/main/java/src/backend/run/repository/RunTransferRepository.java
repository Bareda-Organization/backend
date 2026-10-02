package src.backend.run.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.RunStatus;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunTransferStatus;

/**
 * {@link RunTransfer} 영속성 접근 — {@code run_transfer} 는 {@code academy_id} 컬럼이
 * 부재한 <b>부모 경유</b> 자원이다(ERD §6.1). 학원 조건을 붙일 자리가 {@code Run} 조인뿐이라
 * {@code RunForcedAdditionRepository} 와 같은 형태이되, 이 표는 출발·도착 두 회차를 동시에
 * 가리켜 두 방향으로 각각 조인해야 한다.
 */
public interface RunTransferRepository extends JpaRepository<RunTransfer, Long> {

    /** 이동 1건을 출발 회차의 학원으로 좁혀 읽는다(취소 §5.8.1) — 타 학원 이동은 부재와 같다. */
    @Query("""
            SELECT rt FROM RunTransfer rt
            JOIN Run r ON r.id = rt.fromRunId
            WHERE rt.id = :id
              AND r.academyId = :academyId
            """)
    Optional<RunTransfer> findByIdAndAcademyId(@Param("id") Long id, @Param("academyId") Long academyId);

    /**
     * 출발 회차 쪽 확정 배치({@code RunConfirmationService#confirmOne})가 명단에서 뺄 대상 전체.
     *
     * <p>상태로 거르지 않는다 — 확정 배치가 도중에 실패해도(원자적 쓰기는 유지) 재시도 시 같은
     * 결과를 다시 계산해야 자기 치유가 성립한다({@link RunTransfer} 자바독).
     */
    @Query("""
            SELECT rt FROM RunTransfer rt
            JOIN Run r ON r.id = rt.fromRunId
            WHERE rt.fromRunId = :runId
              AND r.academyId = :academyId
            """)
    List<RunTransfer> findAllByFromRunIdAndAcademyId(@Param("runId") Long runId,
            @Param("academyId") Long academyId);

    /**
     * 도착 회차 쪽 확정 배치가 명단에 더할 대상 전체. 상태를 거르지 않는 이유는 위와 같다.
     */
    @Query("""
            SELECT rt FROM RunTransfer rt
            JOIN Run r ON r.id = rt.toRunId
            WHERE rt.toRunId = :runId
              AND r.academyId = :academyId
            """)
    List<RunTransfer> findAllByToRunIdAndAcademyId(@Param("runId") Long runId,
            @Param("academyId") Long academyId);

    /**
     * 같은 학생의 처리 대기 중인 이동 신청 존재 여부(TRANSFER_ALREADY_STAGED 판정) — 출발
     * 회차 기준으로 학원 조건을 건다.
     */
    @Query("""
            SELECT COUNT(rt) > 0 FROM RunTransfer rt
            JOIN Run r ON r.id = rt.fromRunId
            WHERE rt.studentId = :studentId
              AND r.academyId = :academyId
              AND rt.status = src.backend.run.entity.RunTransferStatus.STAGED
            """)
    boolean existsStagedByStudentIdAndAcademyId(@Param("studentId") Long studentId,
            @Param("academyId") Long academyId);

    /**
     * 도착 회차 확정이 명단에 더한 이동을 {@code applied} 로 표시한다 — 확정 트랜잭션
     * ({@code RunConfirmationPersistence#persist}) 안에서만 불러, 확정이 롤백되면 표시도 되돌아간다(BR-093).
     */
    @Modifying(flushAutomatically = true)
    @AcademyScopeExempt(reason = "findAllByToRunIdAndAcademyId 가 이미 학원으로 좁혀 읽은 이동 id 를 그대로 "
            + "갱신하는 확정 배치 내부 호출이다 — RunRepository.confirmIfIdle 과 같은 근거")
    @Query("UPDATE RunTransfer rt SET rt.status = :appliedStatus, "
            + "rt.appliedAt = :appliedAt WHERE rt.id IN :ids")
    int markApplied(@Param("ids") Collection<Long> ids, @Param("appliedAt") OffsetDateTime appliedAt,
            @Param("appliedStatus") RunTransferStatus appliedStatus);

    /**
     * 반영 전({@code staged}) 이동 1건을 지운다 — 지운 행 수를 돌려주므로 동시에 같은 행을 지운 쪽이 있으면 0 이다.
     * 출발 회차의 학원으로 좁힌다(부모 경유 자원).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM RunTransfer rt
            WHERE rt.id = :id
              AND rt.status = :stagedStatus
              AND rt.fromRunId IN (SELECT r.id FROM Run r WHERE r.academyId = :academyId)
            """)
    int deleteStagedByIdAndAcademyId(@Param("id") Long id, @Param("academyId") Long academyId,
            @Param("stagedStatus") RunTransferStatus stagedStatus);

    /**
     * 그 회차로 들어오는 반영 전({@code staged}) 이동 중 <b>출발 회차가 이미 확정된(취소되지 않은) 것</b>이 있는지(BR-314) —
     * 출발 회차의 확정이 그 학생을 명단·노선에서 이미 뺐으므로, 이동을 지워도 학생은 출발 회차로 돌아가지 못한다.
     * 호출부({@code RunCancellation})가 도착 회차를 이미 잠근 상태에서 부른다.
     */
    @Query("""
            SELECT COUNT(rt) > 0 FROM RunTransfer rt
            JOIN Run f ON f.id = rt.fromRunId
            WHERE rt.toRunId = :toRunId
              AND f.academyId = :academyId
              AND rt.status = :stagedStatus
              AND f.status <> :idleStatus
              AND f.canceledAt IS NULL
            """)
    boolean existsStagedFromConfirmedRun(@Param("toRunId") Long toRunId, @Param("academyId") Long academyId,
            @Param("stagedStatus") RunTransferStatus stagedStatus, @Param("idleStatus") RunStatus idleStatus);
}

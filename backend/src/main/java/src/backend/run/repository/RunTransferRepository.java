package src.backend.run.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.run.entity.RunTransfer;

/**
 * {@link RunTransfer} 영속성 접근 — {@code run_transfer} 는 {@code academy_id} 컬럼이
 * 부재한 <b>부모 경유</b> 자원이다(ERD §6.1). 학원 조건을 붙일 자리가 {@code Run} 조인뿐이라
 * {@code RunForcedAdditionRepository} 와 같은 형태이되, 이 표는 출발·도착 두 회차를 동시에
 * 가리켜 두 방향으로 각각 조인해야 한다.
 */
public interface RunTransferRepository extends JpaRepository<RunTransfer, Long> {

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
     * 정원 판정(BUS-04)에 쓰는 도착 회차 쪽 대기 건수 — 아직 확정 배치가 반영하지 않은
     * (staged) 이동만 센다. 이미 반영된(applied) 건은 그날의 투영 명단 계산에 이미 잡힌다.
     */
    @Query("""
            SELECT COUNT(rt) FROM RunTransfer rt
            JOIN Run r ON r.id = rt.toRunId
            WHERE rt.toRunId = :runId
              AND r.academyId = :academyId
              AND rt.status = src.backend.run.entity.RunTransferStatus.STAGED
            """)
    long countStagedByToRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);

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
}

package src.backend.run.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.run.entity.RunForcedAddition;

/** {@link RunForcedAddition} 영속성 접근. */
public interface RunForcedAdditionRepository extends JpaRepository<RunForcedAddition, Long> {

    /** 확정 배치({@code RunConfirmationService#confirmOne})가 그날 명단에 합칠 대기 행 전체. */
    List<RunForcedAddition> findAllByRunId(Long runId);

    /**
     * 정원 판정(BUS-04)에 쓰는 대기 건수 — 이미 강제 추가된 학생 수만큼 그날의 투영 명단 위에
     * 더해진다({@code ForcedAdditionCommandService} 자바독).
     */
    long countByRunId(Long runId);

    boolean existsByRunIdAndStudentId(Long runId, Long studentId);
}

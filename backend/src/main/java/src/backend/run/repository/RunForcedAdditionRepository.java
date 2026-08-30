package src.backend.run.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.run.entity.RunForcedAddition;

/**
 * {@link RunForcedAddition} 영속성 접근 — {@code run_forced_addition} 은 {@code academy_id} 컬럼이
 * 부재한 <b>부모 경유</b> 자원이다(ERD §6.1). 학원 조건을 붙일 자리가 {@code Run} 조인뿐이라
 * {@code RunStopRepository}·{@code RunRiderRepository} 와 같은 형태다.
 */
public interface RunForcedAdditionRepository extends JpaRepository<RunForcedAddition, Long> {

    /**
     * 확정 배치({@code RunConfirmationService#confirmOne})가 그날 명단에 합칠 대기 행 전체.
     *
     * <p>{@code academy_id} 컬럼이 부재해 학원 조건을 붙일 자리가 {@code Run} 조인뿐이다(ERD §6.1
     * 부모 경유). 호출부가 이미 {@code Run} 을 학원으로 조회해 뒀어도, 이 조회 자체가 학원 조건을
     * 갖도록 다시 건다 — 횡단 규칙 7(저장소 조회 규약)이 개별 조회마다 조건을 요구한다.
     */
    @Query("""
            SELECT rfa FROM RunForcedAddition rfa
            JOIN Run r ON r.id = rfa.runId
            WHERE rfa.runId = :runId
              AND r.academyId = :academyId
            """)
    List<RunForcedAddition> findAllByRunIdAndAcademyId(@Param("runId") Long runId,
            @Param("academyId") Long academyId);

    /**
     * 정원 판정(BUS-04)에 쓰는 대기 건수 — 이미 강제 추가된 학생 수만큼 그날의 투영 명단 위에
     * 더해진다({@code ForcedAdditionCommandService} 자바독). 근거는 위 조회와 같다.
     */
    @Query("""
            SELECT COUNT(rfa) FROM RunForcedAddition rfa
            JOIN Run r ON r.id = rfa.runId
            WHERE rfa.runId = :runId
              AND r.academyId = :academyId
            """)
    long countByRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);
}

package src.backend.boarding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.boarding.entity.RunRider;

/**
 * {@link RunRider} 영속성 접근 — {@code run_rider} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code saveAll()} 로 회차별 명단을 한 번에 쌓기만 했으나,
 * Phase 8(②구간 승인 미리보기)이 처음으로 그 명단을 다시 읽어야 해 조회 메서드를 더한다.
 */
public interface RunRiderRepository extends JpaRepository<RunRider, Long> {

    /**
     * 회차 1건의 현재 라이더 명단 전체(Phase 8, ②구간 승인 미리보기의 기준선) — 확정 배치가 쌓은
     * 뒤 그동안 승인된 변경까지 반영된 <b>지금</b> 상태를 읽는다({@code weekly_address} 가 아니라
     * 이쪽을 기준선으로 삼는 이유는 승인 미리보기 판단 근거 참조).
     *
     * <p>{@code academy_id} 컬럼이 부재해 학원 조건을 붙일 자리가 <b>부모(Run) 조인뿐</b>이다
     * (ERD §6.1 부모 경유 — {@code WeeklyAddressRepository} 와 같은 형태).
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId = :runId
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);
}

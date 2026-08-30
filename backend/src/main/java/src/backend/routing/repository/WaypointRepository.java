package src.backend.routing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.routing.entity.Waypoint;

/**
 * {@link Waypoint} 영속성 접근(RTE-10, API_SPEC §5.15) — {@code waypoint} 는 {@code academy_id}
 * 컬럼이 부재한 <b>부모 경유</b> 자원이다(ERD §6.1). 학원 조건을 붙일 자리가 {@code Run} 조인뿐이라
 * {@code RunStopRepository}·{@code RunRiderRepository} 와 같은 형태다.
 */
public interface WaypointRepository extends JpaRepository<Waypoint, Long> {

    /**
     * 이미 배포된(applied=true) 경유 지점 하나 — 재최적화 입력의 고정 지점 조립과 DELETE 대상 조회
     * 양쪽에서 쓴다. 제거된(removedAt 존재) 행은 대상에서 뺀다.
     */
    @Query("""
            SELECT w FROM Waypoint w
            JOIN Run r ON r.id = w.runId
            WHERE w.id = :waypointId
              AND w.runId = :runId
              AND r.academyId = :academyId
              AND w.applied = true
              AND w.removedAt IS NULL
            """)
    Optional<Waypoint> findAppliedByIdAndRunIdAndAcademyId(@Param("waypointId") Long waypointId,
            @Param("runId") Long runId, @Param("academyId") Long academyId);

    /**
     * 회차 1건에 이미 배포된 경유 지점 전체(제거되지 않은 것만) — 새 경유 지점 추가·제거를 위한
     * 재최적화가 "지금 노선에 이미 고정된 지점" 을 알아야 그 자리를 유지한 채 계산할 수 있다.
     */
    @Query("""
            SELECT w FROM Waypoint w
            JOIN Run r ON r.id = w.runId
            WHERE w.runId = :runId
              AND r.academyId = :academyId
              AND w.applied = true
              AND w.removedAt IS NULL
            ORDER BY w.createdAt ASC
            """)
    List<Waypoint> findAllAppliedByRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);
}

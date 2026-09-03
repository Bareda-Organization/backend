package src.backend.monitoring.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.manager.entity.Manager;

/**
 * {@link Manager} 의 <b>대시보드 전용</b> 접근(§5.3 {@code metrics.unassigned_managers}) —
 * {@code manager} 는 {@code academy_id} 컬럼을 직접 갖는다(ERD §6.1 직접 보유).
 *
 * <p>{@code manager.repository.ManagerRepository} 를 확장하지 않는 이유는
 * {@link StaffRunRiderStatsRepository} 와 같다(쓰기 소유 경계).
 */
public interface StaffManagerAvailabilityRepository extends JpaRepository<Manager, Long> {

    /**
     * 회차 목록 중 어디에도 배치되지 않은 재직({@code deletedAt IS NULL}) 매니저 수.
     *
     * <p>{@code runIds} 가 빈 컬렉션이면 이 메서드를 부르지 않는다 — JPQL {@code IN ()} 은 빈
     * 컬렉션에서 구문 오류다. 호출부(서비스 계층)가 그 경우
     * {@link #countByAcademyIdAndDeletedAtIsNull} 로 대신한다(오늘 회차가 하나도 없으면 재직
     * 매니저 전원이 미배치다).
     */
    @Query("SELECT COUNT(m) FROM Manager m WHERE m.academyId = :academyId AND m.deletedAt IS NULL "
            + "AND m.id NOT IN (SELECT DISTINCT a.managerId FROM Assignment a WHERE a.runId IN :runIds)")
    long countUnassignedByAcademyIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);

    /** 오늘 회차가 하나도 없을 때의 대체 경로 — 재직 매니저 전원이 미배치다. */
    long countByAcademyIdAndDeletedAtIsNull(Long academyId);
}

package src.backend.monitoring.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.boarding.entity.RunRider;
import src.backend.monitoring.dto.StaffRunRiderAggregateView;

/**
 * {@link RunRider} 의 <b>대시보드 전용</b> 집계 접근(§5.3, MON-01~06) — {@code run_rider} 는
 * {@code academy_id} 컬럼이 부재한 부모 경유 자원이다(ERD §6.1, {@code Run} 경유).
 *
 * <p>{@code boarding.repository.RunRiderRepository} 를 확장하지 않고 이 모듈에 별도로 두는 이유는
 * 쓰기 소유 경계다 — 이 태스크(Phase 13 T1)의 쓰기 소유는 {@code monitoring/} 의 {@code Staff*}
 * 접두 파일로 한정돼 있어({@code p13-task-t1.md}), {@code boarding} 모듈 파일을 고칠 수 없다.
 */
public interface StaffRunRiderStatsRepository extends JpaRepository<RunRider, Long> {

    /**
     * 회차 목록의 탑승자를 {@code (runId, status, change)} 조합으로 묶어 센다(§5.3 {@code metrics.
     * boarded}·{@code no_show}·{@code absent} 및 {@code runs[].boarded_count}·{@code total_count}·
     * {@code added_count}·{@code removed_count}).
     *
     * <p>한 쿼리로 두 축(상태·변경 구분)을 함께 묶는 이유는 {@link StaffRunRiderAggregateView}
     * 자바독을 본다 — 소비 측(서비스 계층)이 필요한 축으로 접어 합산한다.
     */
    @Query("SELECT new src.backend.monitoring.dto.StaffRunRiderAggregateView(rr.runId, rr.status, rr.change, "
            + "COUNT(rr)) FROM RunRider rr JOIN Run r ON r.id = rr.runId "
            + "WHERE r.academyId = :academyId AND rr.runId IN :runIds "
            + "GROUP BY rr.runId, rr.status, rr.change")
    List<StaffRunRiderAggregateView> aggregateByAcademyIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);
}

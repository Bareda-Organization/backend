package src.backend.monitoring.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.manager.entity.Assignment;
import src.backend.monitoring.dto.StaffAssignmentAckView;

/**
 * {@link Assignment} 의 <b>대시보드 전용</b> 접근(§5.3 {@code driver_name}·{@code escort_name}·
 * {@code ack_driver}·{@code ack_escort}, RUN-07) — {@code assignment} 는 {@code academy_id} 컬럼이
 * 부재한 부모 경유 자원이다({@code AssignmentRepository} 와 같은 근거).
 *
 * <p>{@code manager.repository.AssignmentRepository} 를 확장하지 않는 이유는
 * {@link StaffRunRiderStatsRepository} 와 같다(쓰기 소유 경계).
 *
 * <p><b>§5.19({@code GET /staff/runs/{runId}/route}, {@code ack{driver, escort}})를 재사용하지
 * 않는다</b> — 그 엔드포인트는 정본(API_SPEC)에는 있으나 이 코드베이스에 컨트롤러가 아직
 * 구현되어 있지 않다({@code run.controller.RunRouteController} 는 §4.3, 매니저 앱용이다). 대신
 * 판정식은 이 값을 실제로 쓰는 유일한 코드인 {@code RunAckChangesCommandService}(쓰기 경로)에서
 * 그대로 반대로 읽었다 — {@link StaffAssignmentAckView#acked()} 참고. 확신 없는 지점으로 보고서
 * §2 에 남긴다.
 */
public interface StaffAssignmentAckRepository extends JpaRepository<Assignment, Long> {

    /**
     * 회차 목록에 배치된 매니저의 이름과 확인 응답 판정 재료를 한 번에 읽는다.
     *
     * <p>{@code ConfirmedRoute} 를 {@code LEFT JOIN} 하는 이유는 노선이 아직 확정되지 않은 회차도
     * 배치는 있을 수 있기 때문이다 — 그때 {@code currentVersionId} 는 {@code null} 이고,
     * {@link StaffAssignmentAckView#acked()} 는 그 상태를 "미확인" 으로 판정한다(둘 다 null 이어도
     * 확인함으로 세지 않는다).
     */
    @Query("SELECT new src.backend.monitoring.dto.StaffAssignmentAckView(a.runId, a.role, m.name, "
            + "a.ackedRouteVersionId, cr.currentVersionId) "
            + "FROM Assignment a JOIN Manager m ON m.id = a.managerId "
            + "LEFT JOIN ConfirmedRoute cr ON cr.runId = a.runId "
            + "WHERE m.academyId = :academyId AND a.runId IN :runIds")
    List<StaffAssignmentAckView> findAckViewsByAcademyIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);
}

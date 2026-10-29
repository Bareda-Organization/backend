package src.backend.manager.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.dto.ManagerRunAssignment;
import src.backend.manager.dto.ManagerRunWindow;
import src.backend.manager.entity.Assignment;
import src.backend.manager.dto.StaffAssignmentAckView;

/**
 * {@link AssignmentRepository} 의 화면용 조회 묶음 — 배치 행을 매니저 · 회차 · 노선 버전과 조인해 DTO 로 바로 읽는 {@code @Query} 들이다.
 * 원래 저장소가 이 인터페이스를 상속하므로 메서드 이름 · 시그니처 · 애너테이션은 그대로이고 호출부는 {@link AssignmentRepository} 를 쓴다.
 */
@NoRepositoryBean
public interface AssignmentViewQueries extends Repository<Assignment, Long> {

    /**
     * 매니저들의 오늘·내일 미취소 회차 배치(§5.13 목록 {@code assignments[]}, Ruling 817) — 날짜·출발 순. 학원 조건을 회차에 건다.
     */
    @Query("SELECT new src.backend.manager.dto.ManagerRunAssignment(a.managerId, r.id, r.serviceDate, b.busNo, "
            + "r.direction, r.departTime, r.status) FROM Assignment a, Run r, Bus b "
            + "WHERE r.id = a.runId AND b.id = r.busId AND r.academyId = :academyId AND a.managerId IN :managerIds "
            + "AND r.canceledAt IS NULL AND r.serviceDate >= :from AND r.serviceDate <= :to "
            + "ORDER BY r.serviceDate, r.departTime")
    List<ManagerRunAssignment> findAssignmentsBetween(@Param("academyId") Long academyId,
            @Param("managerIds") Collection<Long> managerIds, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * 여러 회차의 배치를 매니저 이름과 함께 한 번에 읽는다(§5.10·§5.14 {@code assignments[]}).
     *
     * <p>학원 조건이 {@code manager} 쪽에 걸려 있다 — 조건이 빠지면 회차 id 만 알면 남의 학원 매니저
     * 이름이 응답에 실린다.
     *
     * <p>삭제된 매니저({@code deleted_at})도 싣는다 — 지난 회차의 담당자가 그만뒀다고 해서 그날 누가
     * 운행했는지가 사라지면 안 된다(MGR-04 를 soft delete 로 둔 이유와 같다).
     */
    @Query("SELECT new src.backend.manager.dto.AssignedManagerView(a.runId, a.managerId, m.name, a.role) "
            + "FROM Assignment a, Manager m "
            + "WHERE m.id = a.managerId AND m.academyId = :academyId AND a.runId IN :runIds "
            + "ORDER BY a.runId, a.role")
    List<AssignedManagerView> findAssignedManagers(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);

    /**
     * 여러 회차의 배치를 기사·동승자 원문 연락처와 함께 한 번에 읽는다(§6.8 메인 관리자 관제) —
     * {@link #findAssignedManagers} 와 조인 구조가 같고 {@code m.phone} 만 더 읽는다.
     *
     * <p>학원 조건이 {@link #findAssignedManagers} 와 같은 자리(조인된 {@code manager})에 걸려 있다.
     * 삭제된 매니저({@code deleted_at})도 싣는다 — {@link #findAssignedManagers} 와 같은 근거로,
     * 지난 회차의 실제 운행자를 답할 수단이 사라지면 안 된다.
     */
    @Query("SELECT new src.backend.manager.dto.AssignedManagerContactView(a.runId, a.managerId, m.name, m.phone, "
            + "a.role) FROM Assignment a, Manager m "
            + "WHERE m.id = a.managerId AND m.academyId = :academyId AND a.runId IN :runIds "
            + "ORDER BY a.runId, a.role")
    List<AssignedManagerContactView> findAssignedManagerContacts(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);

    /**
     * 그 매니저가 배치된 다른 회차들의 시간 창 후보(MGR-06 {@code MANAGER_DOUBLE_BOOKED}, Ruling 193).
     *
     * <p>겹침 자체는 <b>여기서 계산하지 않는다</b> — {@code run.est_duration_min} 이 nullable 이고
     * null 일 때 점으로 접는 규칙이 {@code AssignmentConflictDetector#windowEnd} 에 이미 있는 도메인
     * 판단이다. 같은 규칙을 SQL 에 다시 적으면 둘이 갈릴 때 아무도 못 알아챈다 — 그래서 후보만 좁게
     * 추리고 겹침 판정은 그 도메인 메서드에 맡긴다. {@code r.departTime = :departTime} 로 점 판정하면
     * <b>출발 시각이 정확히 같을 때만</b> 걸린다 — 근무 시간 축은 구간인데
     * 이쪽만 점이면 등원 직후 하원처럼 <b>구간이 겹치지만 출발 시각이 다른</b> 배치를 놓친다.
     *
     * <p>취소된 회차를 세지 않는다 — 임시 취소한 회차의 배치는 그 시각을 점유하지 않는다.
     *
     * <p>이 판정은 {@code work_hours} 를 <b>보지 않는다</b>(Ruling 165 ③) — 근무 시간이 없다고 해서
     * 같은 시각에 두 대를 몰 수 있는 것은 아니라, 근무 시간 판정과 묶으면 이쪽이 근무 시간 미기재
     * 매니저에서 조용히 사라진다.
     *
     * <p>운행일을 {@code fromDate}~{@code toDate} 로 좁힌다(BR-088) — 조건이 없으면 매니저가 지금까지
     * 배치된 회차 전부를 배치 요청마다 읽어 운영 기간에 비례해 행이 늘어난다. 호출부는 배치하려는 회차의
     * 운행일 전후 하루를 넘긴다(자정을 걸친 운행 대비 — 소요가 하루를 넘는 통원 회차는 없다는 전제).
     */
    @Query("SELECT new src.backend.manager.dto.ManagerRunWindow(r.departTime, r.estDurationMin) "
            + "FROM Assignment a, Run r "
            + "WHERE r.id = a.runId AND a.managerId = :managerId AND r.academyId = :academyId "
            + "AND r.serviceDate BETWEEN :fromDate AND :toDate "
            + "AND r.canceledAt IS NULL AND r.id <> :excludedRunId")
    List<ManagerRunWindow> findManagerRunWindows(@Param("academyId") Long academyId,
            @Param("managerId") Long managerId, @Param("excludedRunId") Long excludedRunId,
            @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    /**
     * 그 회차에 배치된 기사·동승자를 계정 식별자와 함께 읽는다 — {@code route_changed} 알림의
     * 수신자 조회 전용이다.
     *
     * <p>학원 조건이 {@link #findAssignedManagers} 와 같은 자리(조인된 {@code manager})에 걸려 있다.
     * 삭제된 매니저({@code deleted_at})는 담지 않는다 — 그만둔 매니저에게 새 회차 확정을 알릴 이유가
     * 없다. MGR-04({@link #existsUnfinishedByManagerId})는 <b>끝나지 않은</b> 회차의 배치만 삭제를
     * 막으므로(BR-023), 지난 회차에 배치된 채 삭제된 매니저는 정상 흐름에서 생긴다 — 그 회차가 다시
     * 확정되는 경로는 없지만, 이 조건이 그 매니저를 수신자에서 확실히 뺀다. 도달 가능성은
     * {@code RunRouteConfirmedNotificationTest#삭제된_매니저는_알림을_받지_않는다} 가 상태를 직접 만들어
     * SQL 수준에서 고정한다.
     */
    @Query("SELECT new src.backend.manager.dto.AssignedManagerAccountView(a.managerId, m.accountId, m.name, a.role) "
            + "FROM Assignment a, Manager m "
            + "WHERE m.id = a.managerId AND m.academyId = :academyId AND m.deletedAt IS NULL AND a.runId = :runId "
            + "ORDER BY a.role")
    List<AssignedManagerAccountView> findAssignedManagerAccounts(@Param("academyId") Long academyId,
            @Param("runId") Long runId);

    /**
     * 회차 목록에 배치된 매니저의 이름과 확인 응답 판정 재료를 한 번에 읽는다(§5.3
     * {@code driver_name}·{@code escort_name}·{@code ack_driver}·{@code ack_escort}, RUN-07).
     * 관계자 웹 대시보드 전용이라는 뜻으로 메서드 이름에 용도를 남긴다(Ruling 238).
     *
     * <p>{@code ConfirmedRoute} 를 {@code LEFT JOIN} 하는 이유는 노선이 아직 확정되지 않은 회차도
     * 배치는 있을 수 있기 때문이다 — 그때 {@code currentVersionId} 는 {@code null} 이고,
     * {@link StaffAssignmentAckView#acked()} 는 그 상태를 "미확인" 으로 판정한다(둘 다 null 이어도
     * 확인함으로 세지 않는다).
     *
     * <p><b>BR-117 정정</b> — §5.19({@code GET /staff/runs/{runId}/route})는
     * {@code run.controller.StaffRunRouteController}(§5.19 전용, {@code run.controller.RunRouteController}
     * 는 §4.3 매니저 앱용이라 별개)가 이미 구현하며, 그 쪽 조회({@code run/query/StaffRunRouteQueryService})가
     * 이 메서드를 {@code runId} 1건짜리 목록으로 그대로 재사용한다 — 대시보드(다건)와 §5.19(단건)가
     * 같은 조회 하나를 공유한다. 판정식({@link StaffAssignmentAckView#acked()})은 이 값을 실제로 쓰는
     * 쓰기 경로 {@code RunAckChangesCommandService} 의 조건을 그대로 반대로 읽은 것이다.
     */
    @Query("SELECT new src.backend.manager.dto.StaffAssignmentAckView(a.runId, a.role, m.name, m.phone, "
            + "a.ackedRouteVersionId, cr.currentVersionId) "
            + "FROM Assignment a JOIN Manager m ON m.id = a.managerId "
            + "LEFT JOIN ConfirmedRoute cr ON cr.runId = a.runId "
            + "WHERE m.academyId = :academyId AND a.runId IN :runIds")
    List<StaffAssignmentAckView> findAckViewsForStaffDashboard(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);

    /** 기사가 배치된 회차 식별자(§6.18 {@code today_runs[].driver_assigned}) — 학원 조건은 {@code run} 조인에 건다. */
    @Query("SELECT DISTINCT a.runId FROM Assignment a JOIN Run r ON r.id = a.runId WHERE r.academyId IN :academyIds "
            + "AND a.runId IN :runIds AND a.role = src.backend.global.common.enums.ManagerRole.DRIVER")
    List<Long> findDriverAssignedRunIds(@Param("academyIds") Collection<Long> academyIds,
            @Param("runIds") Collection<Long> runIds);
}

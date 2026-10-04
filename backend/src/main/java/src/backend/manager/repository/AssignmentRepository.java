package src.backend.manager.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.dto.ManagerRunAssignment;
import src.backend.manager.dto.ManagerRunWindow;
import src.backend.manager.entity.Assignment;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.run.entity.RunStatus;

/**
 * {@link Assignment} 영속성 접근 — <b>배치 여부 판정</b>(MGR-04)과 배치 자체를 만들고 되읽는 경로
 * (§5.14 {@code PATCH /staff/runs/{runId}/assignment})가 함께 있다.
 *
 * <p>{@code assignment} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b> 테이블이라(ERD §6.1·§6.2)
 * 학원 조건을 걸 자리가 조인뿐이다 — 아래 {@code @Query} 둘이 그 조인을 들고 있고, 나머지는
 * {@link AcademyScopeExempt} 로 좁히지 않는 근거를 밝힌다.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * "배치 중" 회차 조건(§5.13) — 취소되지 않았고 종료되지 않았으며, 운행 중이거나 운행일이 오늘 이후다. 삭제·역할 변경 차단과 목록의
     * {@code assigned_run_count} 가 이 한 조각을 함께 쓴다(별칭 {@code r} = Run, 파라미터 {@code finishedStatus}·{@code movingStatus}·{@code today}).
     */
    String UNFINISHED_RUN = "r.canceledAt IS NULL AND r.status <> :finishedStatus "
            + "AND (r.status = :movingStatus OR r.serviceDate >= :today)";

    /**
     * 이 매니저가 <b>끝나지 않은</b> 회차에 배치돼 있는지 본다 — 있으면 삭제와 역할 변경이
     * {@code 409 MANAGER_ASSIGNED} 다(MGR-04 "배치 해제 후 삭제" · API_SPEC §5.13). 두 조작이 이 조회
     * 하나를 쓰는 이유는 기준이 갈리면 삭제는 되는데 역할 변경은 막히는 식으로 어긋나기 때문이다.
     *
     * <p>끝나지 않은 회차 = 취소되지 않았고 종료되지 않았으며, 운행 중이거나 운행일이 오늘 이후. 지난
     * 날짜에 시작하지 않은 채 남은 회차를 세지 않는 것은 그 회차가 앞으로 운행될 일이 없어서다 — 세면
     * 한 번이라도 배치된 매니저는 영구히 삭제되지 않는다(과거 배치를 푸는 API 가 부재, BR-023). 지난
     * 배치는 soft delete 라 행째 남으므로 과거 운행의 담당자 기록은 사라지지 않는다.
     *
     * <p><b>이 선검사가 유일한 방어다.</b> ERD 는 {@code manager → assignment} 를 FK RESTRICT 로 두어
     * "DB 도 방어" 라 적지만, §5.13 의 삭제는 행을 지우지 않는 soft delete({@code deleted_at} UPDATE)라
     * FK 가 발동할 자리가 부재하다.
     */
    @AcademyScopeExempt(reason = "assignment 는 run 부모 경유라 학원 조건을 걸 자리가 조인뿐인데(ERD §6.1), "
            + "여기서 학원으로 좁히면 삭제 차단이 오히려 약해진다 — 어떤 이유로든 타 학원 회차에 붙은 배치가 "
            + "있으면 그것도 막아야 삭제 뒤에 담당자가 사라지는 회차가 생기지 않는다. "
            + "호출부가 학원 조건으로 좁혀 조회한 Manager 의 id 만 넘긴다는 전제 — 요청 파라미터의 "
            + "managerId 를 넘기면 타 학원 매니저의 배치 여부가 새어 이 예외가 우회로가 된다")
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN TRUE ELSE FALSE END FROM Assignment a, Run r "
            + "WHERE r.id = a.runId AND a.managerId = :managerId AND " + UNFINISHED_RUN)
    boolean existsUnfinishedByManagerId(@Param("managerId") Long managerId, @Param("today") LocalDate today,
            @Param("finishedStatus") RunStatus finishedStatus, @Param("movingStatus") RunStatus movingStatus);

    /**
     * 매니저별 배치 중 회차 수(§5.13 목록 {@code assigned_run_count}, Ruling 817) — {@link #existsUnfinishedByManagerId} 와
     * <b>같은 조건 상수</b>({@link #UNFINISHED_RUN})를 쓴다. 두 판정이 따로 있으면 목록의 숫자와 삭제 차단이 어긋난다. 배치가 없는
     * 매니저는 행이 없으니 호출부가 0 으로 읽는다.
     */
    @AcademyScopeExempt(reason = "existsUnfinishedByManagerId 와 같은 근거 — 학원으로 좁히면 타 학원 회차에 붙은 배치를 놓쳐 삭제 차단보다 "
            + "숫자가 작아진다. 호출부(ManagerQueryService)가 학원 조건으로 좁혀 조회한 Manager 의 id 만 넘긴다는 전제다")
    @Query("SELECT a.managerId AS managerId, COUNT(a) AS total FROM Assignment a, Run r "
            + "WHERE r.id = a.runId AND a.managerId IN :managerIds AND " + UNFINISHED_RUN + " GROUP BY a.managerId")
    List<ManagerRunCount> countUnfinishedByManagerIds(@Param("managerIds") Collection<Long> managerIds,
            @Param("today") LocalDate today, @Param("finishedStatus") RunStatus finishedStatus,
            @Param("movingStatus") RunStatus movingStatus);

    /** {@link #countUnfinishedByManagerIds} 의 한 행 — 매니저 id 와 배치 중 회차 수. */
    interface ManagerRunCount {

        Long getManagerId();

        long getTotal();
    }

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
     * 그 계정의 매니저가 그 회차의 그 자리에 배치돼 있는지 한 문장으로 확인한다 — 위치 수신(2초마다)이 매니저 조회 + 배치 조회 + 배치의
     * 담당자 비교 두 번을 이 하나로 줄인다(R46-LATERBE L2). 행이 필요하면 {@link #findByRunIdAndRole} 를 쓴다.
     */
    @AcademyScopeExempt(reason = "findByRunIdAndRole 과 같은 근거 — 배치 생성 시점에 매니저와 회차의 학원이 일치하도록 강제돼 있어 "
            + "(AssignmentCommandService#place 가 매니저를 학원으로 좁혀 조회) 여기서 찾은 배치는 항상 그 계정의 학원과 일치한다. "
            + "계정은 토큰에서 얻은 값이고 runId 는 호출부가 인가 확인에 쓰는 값이다")
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN TRUE ELSE FALSE END FROM Assignment a, Manager m "
            + "WHERE m.id = a.managerId AND a.runId = :runId AND a.role = :role AND m.accountId = :accountId")
    boolean existsByRunIdAndRoleAndManagerAccountId(@Param("runId") Long runId, @Param("role") ManagerRole role,
            @Param("accountId") Long accountId);

    /**
     * 그 회차의 그 자리에 이미 붙어 있는 배치(MGR-05, §5.14) — 있으면 <b>교체</b>이고 없으면 신규다.
     *
     * <p>이미 배치된 역할에 다른 매니저를 지정하는 것은 관리 화면의 정상 조작이라 거부하지 않는다.
     * 조회하지 않고 매번 새로 넣으면 {@code uk_assignment_run_role} 이 그 정상 조작을 409 로 막는다.
     */
    @AcademyScopeExempt(reason = "회차의 한 자리를 지목하는 조회라 학원을 좁힐 대상이 runId 뿐이다 — "
            + "호출부가 이미 학원으로 좁혀 꺼낸 Run 의 id 만 넘긴다는 전제이고, 요청 파라미터의 runId 를 "
            + "그대로 넘기면 타 학원 회차의 배치가 교체 대상이 되어 이 예외가 우회로가 된다")
    Optional<Assignment> findByRunIdAndRole(Long runId, ManagerRole role);

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
     * 여러 회차의 배치를 기사·동승자 원문 연락처와 함께 한 번에 읽는다(§6.8 메인 관리자 관제,
     * 목표 8) — {@link #findAssignedManagers} 와 조인 구조가 같고 {@code m.phone} 만 더 읽는다.
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
     * 추리고 겹침 판정은 그 도메인 메서드에 맡긴다. 예전에는 {@code r.departTime = :departTime} 로
     * <b>출발 시각이 정확히 같을 때만</b> 점 판정했었다 — 근무 시간 축은 이미 구간으로 올라가 있는데
     * 이쪽만 점으로 남아 등원 직후 하원처럼 <b>구간이 겹치지만 출발 시각이 다른</b> 배치를 놓쳤다.
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
     * 그 회차에 배치된 기사·동승자를 계정 식별자와 함께 읽는다 — {@code route_changed} 알림(Phase 7 T3)의
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
     * 이 매니저가 그 회차에 배치돼 있는지(§4.2·§4.3 매니저 앱 회차 접근 판정, RUN-03·M-08·M-09,
     * Ruling 205) — 있으면 {@code role} 이 응답의 {@code role_in_run} 이고, 없으면 호출부가
     * {@code 403 FORBIDDEN} 이다(§1.5 매니저 배치 범위).
     *
     * <p>{@code runId} 는 호출부가 {@link src.backend.run.repository.RunRepository#findByIdAndAcademyId}
     * 로 이미 학원 범위를 확인한 값이라는 전제다 — 그 확인 없이 이 메서드만 부르면 타 학원 회차의
     * 배치 여부가 그대로 새어 이 예외가 우회로가 된다.
     */
    @AcademyScopeExempt(reason = "호출부가 RunRepository#findByIdAndAcademyId 로 이미 학원 범위를 확인한 runId 와, "
            + "ManagerRepository#findByAccountId 로 이미 계정에서 뽑은 managerId 만 넘긴다는 전제 — "
            + "두 id 모두 이 시점에 이미 학원으로 좁혀져 있어 다시 조건을 걸 자리가 없다")
    Optional<Assignment> findByRunIdAndManagerId(Long runId, Long managerId);

    /**
     * 이 매니저가 배치된 회차 목록(§4.1 {@code GET /manager/runs}, RUN-01·M-02·M-07) — 그 날짜로
     * 좁힌다.
     *
     * <p>학원 조건을 {@code run} 쪽 조인에 건다({@link #findAssignedManagers} 와 같은 방어 이중화) —
     * managerId 자체가 이미 한 학원에 속하지만, 조건을 명시해 두면 이 조회 하나만 떼어 다른 곳에서
     * 재사용할 때도 학원 격리가 코드에 남는다.
     *
     * <p>취소된 회차를 빼는 이유는 {@link src.backend.run.repository.RunRepository
     * #findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc}(관계자 웹 §5.10)와 다르다 — 매니저 앱은
     * "오늘 내가 나갈 회차" 카드 목록이라, 취소된 회차까지 카드로 띄우면 매니저가 취소분으로 출근하는
     * 사고로 이어진다.
     */
    @Query("SELECT a FROM Assignment a JOIN Run r ON r.id = a.runId "
            + "WHERE a.managerId = :managerId AND r.academyId = :academyId AND r.serviceDate = :serviceDate "
            + "AND r.canceledAt IS NULL "
            + "ORDER BY r.departTime ASC")
    List<Assignment> findByManagerIdAndAcademyIdAndServiceDate(@Param("managerId") Long managerId,
            @Param("academyId") Long academyId, @Param("serviceDate") LocalDate serviceDate);

    /**
     * 회차 목록에 배치된 매니저의 이름과 확인 응답 판정 재료를 한 번에 읽는다(§5.3
     * {@code driver_name}·{@code escort_name}·{@code ack_driver}·{@code ack_escort}, RUN-07,
     * Phase 13 T1). 관계자 웹 대시보드 전용이라는 뜻으로 메서드 이름에 용도를 남긴다(Ruling 238).
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

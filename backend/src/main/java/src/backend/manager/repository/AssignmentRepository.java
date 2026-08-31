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
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.dto.ManagerRunWindow;
import src.backend.manager.entity.Assignment;

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
     * 이 매니저가 어느 회차에든 배치돼 있는지 본다 — 있으면 삭제가 {@code 409 MANAGER_ASSIGNED} 다
     * (MGR-04 · API_SPEC §5.13).
     *
     * <p><b>이 선검사가 유일한 방어다.</b> ERD 는 {@code manager → assignment} 를 FK RESTRICT 로 두어
     * "DB 도 방어" 라 적지만, §5.13 의 삭제는 행을 지우지 않는 soft delete({@code deleted_at} UPDATE)라
     * FK 가 발동할 자리가 부재하다. 이 조회를 건너뛰면 배치된 매니저가 조용히 삭제되고, 그 회차의
     * 담당자는 목록에서 사라진 채 남는다.
     *
     * <p>회차의 취소·종료 여부를 조건에 넣지 않는다 — 지난 회차의 배치도 그 매니저가 실제로 운행한
     * 기록이라, 지우면 과거 운행의 담당자를 답할 수단이 사라진다.
     */
    @AcademyScopeExempt(reason = "assignment 는 run 부모 경유라 학원 조건을 걸 자리가 조인뿐인데(ERD §6.1), "
            + "여기서 학원으로 좁히면 삭제 차단이 오히려 약해진다 — 어떤 이유로든 타 학원 회차에 붙은 배치가 "
            + "있으면 그것도 막아야 삭제 뒤에 담당자가 사라지는 회차가 생기지 않는다. "
            + "호출부가 학원 조건으로 좁혀 조회한 Manager 의 id 만 넘긴다는 전제 — 요청 파라미터의 "
            + "managerId 를 넘기면 타 학원 매니저의 배치 여부가 새어 이 예외가 우회로가 된다")
    boolean existsByManagerId(Long managerId);

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
     */
    @Query("SELECT new src.backend.manager.dto.ManagerRunWindow(r.departTime, r.estDurationMin) "
            + "FROM Assignment a, Run r "
            + "WHERE r.id = a.runId AND a.managerId = :managerId AND r.academyId = :academyId "
            + "AND r.canceledAt IS NULL AND r.id <> :excludedRunId")
    List<ManagerRunWindow> findManagerRunWindows(@Param("academyId") Long academyId,
            @Param("managerId") Long managerId, @Param("excludedRunId") Long excludedRunId);

    /**
     * 그 회차에 배치된 기사·동승자를 계정 식별자와 함께 읽는다 — {@code route_changed} 알림(Phase 7 T3)의
     * 수신자 조회 전용이다.
     *
     * <p>학원 조건이 {@link #findAssignedManagers} 와 같은 자리(조인된 {@code manager})에 걸려 있다.
     * 삭제된 매니저({@code deleted_at})는 담지 않는다 — 그만둔 매니저에게 새 회차 확정을 알릴 이유가
     * 없다. 다만 이 조건이 정상 흐름에서 실제로 걸러내는 행은 없다: MGR-04(
     * {@code AssignmentRepository#existsByManagerId})가 배치가 남아 있는 매니저의 삭제 자체를
     * 막아, "배치는 있는데 매니저는 삭제됨" 이라는 상태가 애초에 만들어지지 않는다(Phase 8 목표 17
     * — 이전에는 "{@code accountId} 가 다른 매니저로 재배정될 위험" 을 근거로 적었으나, 계정 재연결
     * 자체가 {@code Manager#linkAccount} 의 {@code ALREADY_LINKED} 가드로 막혀 있어 그 서술은
     * 부정확했다). 그래서 이 조건은 지금 당장 걸러내는 것이 있어서가 아니라, MGR-04 의 보장이
     * 훗날 완화될 때를 대비한 <b>방어적 불변 조건</b>으로 남겨 둔다 — 도달 가능성은
     * {@code RunRouteConfirmedNotificationTest#삭제된_매니저는_알림을_받지_않는다} 가 서비스 계층
     * 가드를 우회해 상태를 직접 만들어 SQL 수준에서 고정한다.
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
}

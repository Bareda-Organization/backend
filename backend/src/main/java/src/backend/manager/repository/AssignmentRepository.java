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
import src.backend.manager.entity.Assignment;
import src.backend.run.entity.RunStatus;

/**
 * {@link Assignment} 영속성 접근 — <b>배치 여부 판정</b>(MGR-04)과 배치 자체를 만들고 되읽는 경로
 * (§5.14 {@code PATCH /staff/runs/{runId}/assignment})가 함께 있다.
 *
 * <p>{@code assignment} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b> 테이블이라(ERD §6.1·§6.2)
 * 학원 조건을 걸 자리가 조인뿐이다 — 조인을 들고 있는 {@code @Query} 들은 {@link AssignmentViewQueries} 에 있고, 여기 남은 조회는
 * {@link AcademyScopeExempt} 로 좁히지 않는 근거를 밝힌다.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, Long>, AssignmentViewQueries {

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
     * 그 계정의 매니저가 그 회차의 그 자리에 배치돼 있는지 한 문장으로 확인한다 — 위치 수신(2초마다)이 매니저 조회 + 배치 조회 + 배치의
     * 담당자 비교 두 번을 이 하나로 줄인다. 삭제된 매니저는 배치가 남아 있어도 거짓이다(Ruling 885 ⑥). 행이 필요하면 {@link #findByRunIdAndRole} 를 쓴다.
     */
    @AcademyScopeExempt(reason = "findByRunIdAndRole 과 같은 근거 — 배치 생성 시점에 매니저와 회차의 학원이 일치하도록 강제돼 있어 "
            + "(AssignmentCommandService#place 가 매니저를 학원으로 좁혀 조회) 여기서 찾은 배치는 항상 그 계정의 학원과 일치한다. "
            + "계정은 토큰에서 얻은 값이고 runId 는 호출부가 인가 확인에 쓰는 값이다")
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN TRUE ELSE FALSE END FROM Assignment a, Manager m "
            + "WHERE m.id = a.managerId AND a.runId = :runId AND a.role = :role AND m.accountId = :accountId "
            + "AND m.deletedAt IS NULL")
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

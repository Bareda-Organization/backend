package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.global.security.authz.Permissions;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * 메인 관리자 콘솔 · 관제 대시보드(API_SPEC §6)와 관측 게이지가 읽는 {@link Run} 집계 조회 — 전 학원 또는 선택한 학원들을 가로지르는 읽기 전용 저장소다.
 * 도메인 흐름({@link RunRepository})의 조회와 갈라, 대시보드 집계가 늘어도 회차 저장소가 커지지 않게 한다.
 */
public interface RunDashboardRepository extends Repository<Run, Long> {

    /**
     * 한 학원에서 그 상태인 미취소 회차가 있는 <b>차량의 호차 이름</b>(학원 상세 {@code stats.moving_bus_nos[]}, §6.3 · Ruling 806) — 운행일이
     * {@code since} 이후인 회차만 센다. {@code since} 는 {@code MovingRunWindowPolicy#earliestServiceDate} 라
     * 근접 판정·유실 집계·노선 잠금과 같은 범위다(Ruling 701) — 그보다 이른 {@code moving} 회차는 끝나지 않은 회차로
     * {@link MovingRunQueries#countStaleMoving} 이 센다. 차량이 같으면 한 번만 나온다(회차 수가 아니다, BR-057). 호차 이름은 학원 안에서 유일하다
     * ({@code uk_bus_academy_bus_no}) — 그래서 이 목록의 크기가 곧 {@code moving_bus_count} 이고, 두 값이 같은 범위일 수밖에 없다.
     */
    @Query("SELECT DISTINCT b.busNo FROM Run r JOIN Bus b ON b.id = r.busId AND b.academyId = r.academyId "
            + "WHERE r.academyId = :academyId AND r.status = :status "
            + "AND r.canceledAt IS NULL AND r.serviceDate >= :since ORDER BY b.busNo")
    List<String> findBusNosByStatusFromServiceDate(@Param("academyId") Long academyId,
            @Param("status") RunStatus status, @Param("since") LocalDate since);

    /** 학원 여러 곳 안에서 id 로 일괄 조회한다 — 메인 관리자 대시보드(§6.18)가 학원을 가로질러 읽되 선택한 학원 안으로만 좁힌다. */
    List<Run> findAllByAcademyIdInAndIdIn(Collection<Long> academyIds, Collection<Long> ids);

    /**
     * 회차 id 목록을 학원 조건 없이 읽는다(AdminEmergencyQueryService, §6.11) — 호출부가 넘기는
     * id 는 이미 emergency_alert.run_id 에서 온 값이고, 그 조회(EmergencyAlertRepository
     * #findAllByState) 자체가 메인 관리자 콘솔의 명시적 전 학원 예외라 여기서 다시
     * 학원으로 좁힐 근거가 부재하다(AccountRepository#findAllByIdIn 과 같은 근거).
     */
    @AcademyScopeExempt(reason = "메인 관리자 콘솔의 전 학원 비상 알림 조회(AdminEmergencyQueryService) — 호출부가 "
            + "넘기는 runId 는 emergency_alert.run_id 에서 온 값이라 학원별로 미리 좁힐 수 없다. emergency_alert "
            + "조회 자체가 이미 §6.11 의 명시적 전 학원 예외다(AccountRepository#findAllByIdIn 과 같은 근거)")
    List<Run> findAllByIdIn(Collection<Long> ids);

    /**
     * {@code schoolbus.run.unconfirmed} 게이지(TECH_DECISIONS §13.1·§13.4)의 조회 대상 — 판정 시각을
     * 5분 넘긴(경고 여유) idle 회차 수. {@code threshold} 에 {@code now - 5분} 을 넘겨받는다 — 알럿
     * 조건과 확정 배치 조회의 문턱이 다르므로({@code +5분} 여유) 이 메서드를 따로 둔다.
     *
     * <p>{@link RunRepository#findDueForConfirmation} 과 같은 두 조건을 건다. ① {@code canceled_at IS NULL} — 취소된 회차는 애초에
     * 확정 대상이 아니라 "노선을 못 받은" 위험이 없다. ② {@code service_date >= today}(BR-231) — 지난 날짜 idle 회차는
     * 확정 배치가 집지 않아 영구히 idle 이고, 세면 "0 이 아니면 곧 운행 사고" 경보가 다시 꺼지지 않는다.
     */
    @AcademyScopeExempt(reason = "미확정 회차 게이지는 시각이 촉발하는 전 학원 대상 집계라 좁힐 학원이 "
            + "부재하다 — findDueForConfirmation 와 같은 근거. "
            + "호출부는 관측 스케줄러(RunUnconfirmedGaugeScheduler)뿐이라는 전제")
    @Query("SELECT COUNT(r) FROM Run r WHERE r.status = :status AND r.confirmAt <= :threshold "
            + "AND r.serviceDate >= :today AND r.canceledAt IS NULL")
    long countOverdueUnconfirmed(@Param("status") RunStatus status, @Param("threshold") OffsetDateTime threshold,
            @Param("today") LocalDate today);

    /**
     * 학원별로 <b>오늘 확정에 실패 중인</b> 회차 수 — 임시 취소를 뺀 오늘 회차 중 아직 {@code idle} 이면서
     * {@code consecutive_failures > 0} 인 것(§6.15, Ruling 543). 이미 확정된 회차의 옛 실패 횟수는 읽지 않는다. 상태를 enum 리터럴로
     * 쓰는 이유는 {@link #countDelayedByAcademy} 와 같다({@code ix_run_status_confirm_at} 의 {@code status = 'idle'} 부분 조건).
     */
    @AcademyScopeExempt(reason = "메인 관리자 전체 관제(§6.15)가 전 학원을 학원별로 묶어 세는 조회라 좁힐 학원이 부재하다 — "
            + "호출부는 @Can(Permissions.MONITOR_ALL) 로 보호되는 AdminRunAttentionQueryService 뿐이라는 전제 — 요청 경로에서 학원 권한으로 "
            + "부르면 이 예외가 우회로가 된다(findDueForConfirmation 과 같은 근거)")
    @Query("SELECT r.academyId AS academyId, COUNT(r) AS runCount FROM Run r "
            + "WHERE r.serviceDate = :today AND r.canceledAt IS NULL "
            + "AND r.status = src.backend.run.entity.RunStatus.IDLE "
            + "AND r.consecutiveFailures > 0 GROUP BY r.academyId")
    List<AcademyRunCount> countConfirmFailedByAcademy(@Param("today") LocalDate today);

    /**
     * 학원·상태별로 <b>그날 미취소 회차</b> 수(§6.15 {@code today[]}, Ruling 805) — 전체 관제의 학원 레일·지표 칸이 학원마다 §6.8 을
     * 부르지 않고 한 번에 읽는다. 회차가 없는 학원·상태는 행이 없다(호출부가 0 으로 채운다).
     */
    @AcademyScopeExempt(reason = "메인 관리자 전체 관제(§6.15 today[])가 전 학원을 학원·상태별로 묶어 세는 조회라 좁힐 학원이 부재하다 — "
            + "countConfirmFailedByAcademy 와 같은 근거와 같은 호출부 전제")
    @Query("SELECT r.academyId AS academyId, r.status AS status, COUNT(r) AS runCount FROM Run r "
            + "WHERE r.serviceDate = :day AND r.canceledAt IS NULL GROUP BY r.academyId, r.status")
    List<AcademyRunStatusCount> countByAcademyAndStatus(@Param("day") LocalDate day);

    /**
     * 학원별로 <b>오늘 지연 알림이 나간 채 아직 끝나지 않은</b> 회차 수(§6.15, Ruling 543) — 임시 취소를 뺀 오늘
     * 회차 중 {@code finished} 가 아니고 {@code delay_notice} 가 1건 이상인 것. 알림이 여러 건이어도 회차는 한 번만
     * 센다({@code EXISTS}). 상태를 파라미터가 아니라 enum 리터럴로 쓰는 이유는 부분 인덱스
     * {@code ix_run_open_service_date}({@code status <> 'finished'} 조건)가 일반(generic) 계획에서도 쓰이게 하려는 것이다 —
     * 파라미터이면 플래너가 조건이 인덱스 조건을 함의하는지 알 수 없다(Ruling 673).
     */
    @AcademyScopeExempt(reason = "메인 관리자 전체 관제(§6.15)가 전 학원을 학원별로 묶어 세는 조회라 좁힐 학원이 부재하다 — "
            + "countConfirmFailedByAcademy 와 같은 근거와 같은 호출부 전제")
    @Query("SELECT r.academyId AS academyId, COUNT(r) AS runCount FROM Run r "
            + "WHERE r.serviceDate = :today AND r.canceledAt IS NULL "
            + "AND r.status <> src.backend.run.entity.RunStatus.FINISHED "
            + "AND EXISTS (SELECT 1 FROM DelayNotice d WHERE d.runId = r.id) GROUP BY r.academyId")
    List<AcademyRunCount> countDelayedByAcademy(@Param("today") LocalDate today);

    /**
     * 학원·운행일별 회차 집계(§6.18, Ruling 801~802) — 미취소 회차 수 · 취소 수 · 시작한 수 · 정시 출발 수(Ruling 802: 실제 시작이 출발 예정 + {@code PolicyConstants.DELAY_UNIT_MINUTES}분 이내,
     * 정확히 +5분은 정시) · 지연 알림이 나간 회차 수(알림이 여러 건이어도 회차는 한 번)를 한 번에 센다. 학원 조건이 {@code academy_id IN} 으로 걸려
     * {@code ix_run_academy_date_depart} 를 탄다 — 30일 범위에서도 전 행 스캔이 아니다.
     */
    @AcademyScopeExempt(reason = "메인 관리자 대시보드(§6.18)가 학원 선택(academy_id) 또는 전 학원 목록으로 묶어 세는 조회다 — academyIds 는 "
            + "호출부(AdminDashboardQueryService)가 존재하는 학원 식별자로만 채운다. 요청 경로에서 학원 권한으로 부르면 이 예외가 우회로가 된다")
    @Query(value = """
            SELECT r.service_date AS "serviceDate", r.academy_id AS "academyId",
                   COUNT(*) FILTER (WHERE r.canceled_at IS NULL) AS "runCount",
                   COUNT(*) FILTER (WHERE r.canceled_at IS NOT NULL) AS "canceledCount",
                   COUNT(*) FILTER (WHERE r.canceled_at IS NULL AND r.started_at IS NOT NULL) AS "startedCount",
                   COUNT(*) FILTER (WHERE r.canceled_at IS NULL AND r.started_at IS NOT NULL
                                    AND r.started_at <= r.depart_time + make_interval(mins => :#{T(src.backend.global.policy.PolicyConstants).DELAY_UNIT_MINUTES})) AS "onTimeCount",
                   COUNT(*) FILTER (WHERE r.canceled_at IS NULL
                                    AND EXISTS (SELECT 1 FROM delay_notice d WHERE d.run_id = r.id)) AS "delayedCount"
              FROM run r
             WHERE r.academy_id IN (:academyIds) AND r.service_date BETWEEN :from AND :to
             GROUP BY r.service_date, r.academy_id
            """, nativeQuery = true)
    List<RunDailyAggregate> aggregateDaily(@Param("academyIds") Collection<Long> academyIds,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 그날 미취소 회차 — 출발 순(§6.18 {@code today_runs[]}, 학원 선택에 따라 좁힌다). */
    List<Run> findAllByAcademyIdInAndServiceDateAndCanceledAtIsNullOrderByDepartTimeAsc(Collection<Long> academyIds,
            LocalDate serviceDate);

    /**
     * 오늘 지연 회차(§6.18 {@code attention.delayed_runs[]}) — {@link #countDelayedByAcademy}(§6.15)와 같은 정의다: 미취소 · 아직 {@code finished}
     * 가 아님 · 지연 알림 1건 이상.
     */
    @Query("SELECT r FROM Run r WHERE r.academyId IN :academyIds AND r.serviceDate = :today AND r.canceledAt IS NULL "
            + "AND r.status <> src.backend.run.entity.RunStatus.FINISHED "
            + "AND EXISTS (SELECT 1 FROM DelayNotice d WHERE d.runId = r.id) ORDER BY r.departTime ASC, r.id ASC")
    List<Run> findDelayedToday(@Param("academyIds") Collection<Long> academyIds, @Param("today") LocalDate today);

    /** 학원 선택에 따라 좁힌 끝나지 않은 이동 중 회차 수(§6.18 {@code attention.stale_runs}) — {@link MovingRunQueries#STALE_MOVING} 조건을 {@link MovingRunQueries#countStaleMoving} 과 같이 쓴다. */
    @Query("SELECT COUNT(r) FROM Run r WHERE r.academyId IN :academyIds AND " + MovingRunQueries.STALE_MOVING)
    long countStaleMovingByAcademyIds(@Param("academyIds") Collection<Long> academyIds,
            @Param("before") LocalDate before);

    /** 최근 확정된 회차(§6.18 {@code recent_events[]} {@code run_confirmed}) — 시각 내림차순 · 최대 {@code limit} 건. */
    @Query("SELECT r FROM Run r WHERE r.academyId IN :academyIds AND r.serviceDate >= :sinceDate "
            + "AND r.canceledAt IS NULL AND r.confirmedAt >= :since ORDER BY r.confirmedAt DESC, r.id DESC")
    List<Run> findRecentlyConfirmed(@Param("academyIds") Collection<Long> academyIds,
            @Param("sinceDate") LocalDate sinceDate, @Param("since") OffsetDateTime since, Limit limit);

    /** 최근 출발한 회차({@code run_started}) — 시각 내림차순 · 최대 {@code limit} 건. */
    @Query("SELECT r FROM Run r WHERE r.academyId IN :academyIds AND r.serviceDate >= :sinceDate "
            + "AND r.canceledAt IS NULL AND r.startedAt >= :since ORDER BY r.startedAt DESC, r.id DESC")
    List<Run> findRecentlyStarted(@Param("academyIds") Collection<Long> academyIds,
            @Param("sinceDate") LocalDate sinceDate, @Param("since") OffsetDateTime since, Limit limit);

    /** 최근 끝난 회차({@code run_finished}) — 시각 내림차순 · 최대 {@code limit} 건. */
    @Query("SELECT r FROM Run r WHERE r.academyId IN :academyIds AND r.serviceDate >= :sinceDate "
            + "AND r.canceledAt IS NULL AND r.finishedAt >= :since ORDER BY r.finishedAt DESC, r.id DESC")
    List<Run> findRecentlyFinished(@Param("academyIds") Collection<Long> academyIds,
            @Param("sinceDate") LocalDate sinceDate, @Param("since") OffsetDateTime since, Limit limit);
}

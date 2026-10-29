package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.boarding.entity.RiderStatus;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.ManagerRepository;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 운행 대시보드 조회(§5.3 {@code GET /staff/dashboard}, MON-01·02·03·04·06, A-03).
 *
 * <p>{@code run.query.RunQueryService}(§5.10 회차 목록)와 <b>다른 것</b>이다(Ruling 153) — 이쪽은
 * 지표 집계 + 회차별 승하차·변경·확인 응답까지 얹은 관제 요약이고, 그쪽은 배치 정보만 실은 순수
 * 목록이다. 그래서 이 서비스는 {@code RunQueryService} 를 고치거나 재사용하지 않고, 같은 기반 조회
 * ({@code RunRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc}·
 * {@code BusRepository.findAllByAcademyIdAndIdIn})만 같은 패턴으로 다시 부른다(중복은 의도된
 * 트레이드오프 — {@code RunLiveStateResolver} 자바독과 같은 판단).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffDashboardQueryService {

    private final RunRepository runRepository;

    private final ManagerRepository managerRepository;

    private final StaffDashboardLookupLoader lookupLoader;

    private final Clock clock;

    /**
     * 소속 학원의 그날 대시보드 — 날짜를 주지 않으면 오늘이다({@code RunQueryService.list} 와 같은
     * 근거로 주입된 {@code Clock} 을 쓴다, 횡단 규칙 1).
     *
     * @param requestedDate {@code YYYY-MM-DD}. {@code null} 이면 오늘
     */
    public StaffDashboardResponse dashboard(AuthUser requester, String requestedDate) {
        LocalDate serviceDate = requestedDate == null ? LocalDate.now(clock) : ApiValues.date(requestedDate);
        // 임시 취소된 회차는 표와 지표에서 뺀다(Ruling 375) — 운행하지 않는 회차가 idle 로 섞이고, 그 회차의 배치가 매니저를 "배치됨" 으로 센다.
        List<Run> runs = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(
                requester.academyId(), serviceDate).stream().filter(run -> !run.isCanceled()).toList();
        if (runs.isEmpty()) {
            int unassignedManagers = (int) managerRepository
                    .countActiveForStaffDashboard(requester.academyId());
            return new StaffDashboardResponse(
                    new StaffDashboardResponse.Metrics(0, 0, 0, 0, unassignedManagers), List.of());
        }

        List<Long> runIds = runs.stream().map(Run::getId).toList();
        StaffDashboardRunLookups lookups = lookupLoader.load(requester, runs, runIds);
        List<StaffDashboardResponse.Run> runResponses = runs.stream()
                .map(run -> StaffDashboardRunAssembler.toRunResponse(run, lookups))
                .toList();

        StaffDashboardResponse.Metrics metrics = metricsOf(requester, runs, runIds, lookups.riderAggByRun());
        return new StaffDashboardResponse(metrics, runResponses);
    }

    /**
     * {@code metrics.moving_buses}·{@code boarded}·{@code no_show}·{@code absent}·
     * {@code unassigned_managers} — 앞 넷은 {@code runs[]} 자체와 탑승자 집계를 학원 전체로
     * 합산하고, 마지막은 별도 조회다({@link ManagerRepository#countUnassignedForStaffDashboard} 자바독).
     */
    private StaffDashboardResponse.Metrics metricsOf(AuthUser requester, List<Run> runs, List<Long> runIds,
            Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun) {
        int movingBuses = (int) runs.stream().filter(run -> run.getStatus() == RunStatus.MOVING).count();
        List<StaffRunRiderAggregateView> allAgg = riderAggByRun.values().stream().flatMap(List::stream).toList();
        int boarded = StaffDashboardRunAssembler.boardedSoFar(allAgg);
        int noShow = StaffDashboardRunAssembler.sumByStatus(allAgg, RiderStatus.NO_SHOW);
        int absent = StaffDashboardRunAssembler.sumByStatus(allAgg, RiderStatus.ABSENT);
        int unassignedManagers = (int) managerRepository
                .countUnassignedForStaffDashboard(requester.academyId(), runIds);
        return new StaffDashboardResponse.Metrics(movingBuses, boarded, noShow, absent, unassignedManagers);
    }
}

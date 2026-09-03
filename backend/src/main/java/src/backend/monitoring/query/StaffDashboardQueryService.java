package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.monitoring.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.monitoring.dto.StaffNoShowCaseView;
import src.backend.monitoring.dto.StaffRunRiderAggregateView;
import src.backend.monitoring.repository.StaffAssignmentAckRepository;
import src.backend.monitoring.repository.StaffManagerAvailabilityRepository;
import src.backend.monitoring.repository.StaffNoShowCaseRepository;
import src.backend.monitoring.repository.StaffRunRiderStatsRepository;
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

    private final BusRepository busRepository;

    private final StaffRunRiderStatsRepository staffRunRiderStatsRepository;

    private final StaffAssignmentAckRepository staffAssignmentAckRepository;

    private final StaffManagerAvailabilityRepository staffManagerAvailabilityRepository;

    private final StaffNoShowCaseRepository staffNoShowCaseRepository;

    private final Clock clock;

    /**
     * 소속 학원의 그날 대시보드 — 날짜를 주지 않으면 오늘이다({@code RunQueryService.list} 와 같은
     * 근거로 주입된 {@code Clock} 을 쓴다, 횡단 규칙 1).
     *
     * @param requestedDate {@code YYYY-MM-DD}. {@code null} 이면 오늘
     */
    public StaffDashboardResponse dashboard(AuthUser requester, String requestedDate) {
        LocalDate serviceDate = requestedDate == null ? LocalDate.now(clock) : ApiValues.date(requestedDate);
        List<Run> runs = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(
                requester.academyId(), serviceDate);
        if (runs.isEmpty()) {
            int unassignedManagers = (int) staffManagerAvailabilityRepository
                    .countByAcademyIdAndDeletedAtIsNull(requester.academyId());
            return new StaffDashboardResponse(
                    new StaffDashboardResponse.Metrics(0, 0, 0, 0, unassignedManagers), List.of());
        }

        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<Long, String> busNos = busNosOf(requester, runs);
        Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun = riderAggregatesOf(requester, runIds);
        Map<Long, List<StaffAssignmentAckView>> ackViewsByRun = ackViewsOf(requester, runIds);
        Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun = noShowViewsOf(requester, runIds);

        List<StaffDashboardResponse.Run> runResponses = runs.stream()
                .map(run -> toRunResponse(run, busNos, riderAggByRun, ackViewsByRun, noShowViewsByRun))
                .toList();

        StaffDashboardResponse.Metrics metrics = metricsOf(requester, runs, runIds, riderAggByRun);
        return new StaffDashboardResponse(metrics, runResponses);
    }

    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    private Map<Long, List<StaffRunRiderAggregateView>> riderAggregatesOf(AuthUser requester, List<Long> runIds) {
        return staffRunRiderStatsRepository.aggregateByAcademyIdAndRunIdIn(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffRunRiderAggregateView::runId));
    }

    private Map<Long, List<StaffAssignmentAckView>> ackViewsOf(AuthUser requester, List<Long> runIds) {
        return staffAssignmentAckRepository.findAckViewsByAcademyIdAndRunIdIn(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffAssignmentAckView::runId));
    }

    private Map<Long, List<StaffNoShowCaseView>> noShowViewsOf(AuthUser requester, List<Long> runIds) {
        return staffNoShowCaseRepository.findActiveByAcademyIdAndRunIdIn(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffNoShowCaseView::runId));
    }

    /**
     * {@code metrics.moving_buses}·{@code boarded}·{@code no_show}·{@code absent}·
     * {@code unassigned_managers} — 앞 넷은 {@code runs[]} 자체와 탑승자 집계를 학원 전체로
     * 합산하고, 마지막은 별도 조회다({@link StaffManagerAvailabilityRepository} 자바독).
     */
    private StaffDashboardResponse.Metrics metricsOf(AuthUser requester, List<Run> runs, List<Long> runIds,
            Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun) {
        int movingBuses = (int) runs.stream().filter(run -> run.getStatus() == RunStatus.MOVING).count();
        List<StaffRunRiderAggregateView> allAgg = riderAggByRun.values().stream().flatMap(List::stream).toList();
        int boarded = sumByStatus(allAgg, RiderStatus.BOARDED);
        int noShow = sumByStatus(allAgg, RiderStatus.NO_SHOW);
        int absent = sumByStatus(allAgg, RiderStatus.ABSENT);
        int unassignedManagers = (int) staffManagerAvailabilityRepository
                .countUnassignedByAcademyIdAndRunIdIn(requester.academyId(), runIds);
        return new StaffDashboardResponse.Metrics(movingBuses, boarded, noShow, absent, unassignedManagers);
    }

    private StaffDashboardResponse.Run toRunResponse(Run run, Map<Long, String> busNos,
            Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun,
            Map<Long, List<StaffAssignmentAckView>> ackViewsByRun,
            Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun) {
        List<StaffRunRiderAggregateView> agg = riderAggByRun.getOrDefault(run.getId(), List.of());
        int boardedCount = sumByStatus(agg, RiderStatus.BOARDED);
        int totalCount = (int) agg.stream().mapToLong(StaffRunRiderAggregateView::count).sum();
        int addedCount = sumByChange(agg, ChangeType.ADDED);
        int removedCount = sumByChange(agg, ChangeType.REMOVED);

        List<StaffAssignmentAckView> acks = ackViewsByRun.getOrDefault(run.getId(), List.of());
        String driverName = nameOf(acks, ManagerRole.DRIVER);
        String escortName = nameOf(acks, ManagerRole.ESCORT);
        boolean ackDriver = ackedOf(acks, ManagerRole.DRIVER);
        boolean ackEscort = ackedOf(acks, ManagerRole.ESCORT);

        List<StaffDashboardResponse.NoShowCase> noShowCases = noShowViewsByRun
                .getOrDefault(run.getId(), List.of()).stream()
                .map(view -> new StaffDashboardResponse.NoShowCase(view.studentName(), view.stopName(),
                        view.expiresAt()))
                .toList();

        return new StaffDashboardResponse.Run(run.getId(), busNos.get(run.getBusId()),
                lower(run.getDirection().name()), run.getDepartTime(), driverName, escortName, boardedCount,
                totalCount, lower(run.getStatus().name()), addedCount, removedCount, ackDriver, ackEscort,
                noShowCases);
    }

    private int sumByStatus(List<StaffRunRiderAggregateView> agg, RiderStatus status) {
        return (int) agg.stream().filter(view -> view.status() == status).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }

    private int sumByChange(List<StaffRunRiderAggregateView> agg, ChangeType change) {
        return (int) agg.stream().filter(view -> view.change() == change).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }

    private String nameOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::name).findFirst()
                .orElse(null);
    }

    private boolean ackedOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).findFirst().map(StaffAssignmentAckView::acked)
                .orElse(false);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

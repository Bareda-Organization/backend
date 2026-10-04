package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.NoShowContact;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.repository.NoShowContactRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.exception.dto.StaffNoShowCaseView;
import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.notification.repository.DelayRecipientCount;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.DelayNotice;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.DelayNoticeRepository;
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

    private final RunRiderRepository runRiderRepository;

    private final AssignmentRepository assignmentRepository;

    private final ManagerRepository managerRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    private final NoShowContactRepository noShowContactRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final NotificationLogRepository notificationLogRepository;

    private final RunOrderedStopsLoader runOrderedStopsLoader;

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
        Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun = riderAggregatesOf(requester, runIds);
        Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun = noShowViewsOf(requester, runIds);
        RunLookups lookups = new RunLookups(busNosOf(requester, runs), riderAggByRun,
                ackViewsOf(requester, runIds), noShowViewsByRun, contactsOf(noShowViewsByRun),
                stopsOfMovingRuns(requester, runs), lastDelayNoticesOf(requester, runIds));

        List<StaffDashboardResponse.Run> runResponses = runs.stream()
                .map(run -> toRunResponse(run, lookups))
                .toList();

        StaffDashboardResponse.Metrics metrics = metricsOf(requester, runs, runIds, riderAggByRun);
        return new StaffDashboardResponse(metrics, runResponses);
    }

    /**
     * 회차 하나를 응답 한 행으로 만드는 데 필요한, 회차 수와 무관하게 미리 한 번씩 읽어 둔 조회 결과 묶음(BR-101) —
     * {@code stopsByRun} 은 지연 분 계산용이라 {@code moving} 회차만 담긴다.
     */
    private record RunLookups(Map<Long, String> busNos, Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun,
            Map<Long, List<StaffAssignmentAckView>> ackViewsByRun,
            Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun, Map<Long, List<NoShowContact>> contactsByCaseId,
            Map<Long, List<RunStop>> stopsByRun, Map<Long, StaffDashboardResponse.LastDelayNotice> lastDelayNoticeByRun) {
    }

    /** 지연 분(§5.18 과 같은 계산)은 운행 중 회차만 읽으므로 그 회차들의 확정 노선 정차 항목만 배치로 읽는다(BR-247). */
    private Map<Long, List<RunStop>> stopsOfMovingRuns(AuthUser requester, List<Run> runs) {
        List<Run> moving = runs.stream().filter(run -> run.getStatus() == RunStatus.MOVING).toList();
        return moving.isEmpty() ? Map.of() : runOrderedStopsLoader.load(requester.academyId(), moving);
    }

    /**
     * 회차별 마지막 지연 알림 + 그 알림이 적재한 수신 건수(Ruling 810) — 알림 적재가 지연 알림 행에 회차 식별자를 따로 두지 않아 건수는
     * {@link NotificationLogRepository#countDelayRecipients} 가 {@code dedup_key} 로 센다.
     */
    private Map<Long, StaffDashboardResponse.LastDelayNotice> lastDelayNoticesOf(AuthUser requester,
            List<Long> runIds) {
        List<DelayNotice> lastNotices = delayNoticeRepository.findLastByRunIds(requester.academyId(), runIds);
        if (lastNotices.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> recipientCounts = notificationLogRepository
                .countDelayRecipients(requester.academyId(), lastNotices.stream().map(DelayNotice::getId).toList())
                .stream().collect(Collectors.toMap(DelayRecipientCount::getRunId, DelayRecipientCount::getRecipientCount));
        return lastNotices.stream().collect(Collectors.toMap(DelayNotice::getRunId,
                notice -> new StaffDashboardResponse.LastDelayNotice(notice.getMinutes(),
                        lower(notice.getReason().name()), notice.getSentAt(),
                        recipientCounts.getOrDefault(notice.getRunId(), 0L))));
    }

    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    private Map<Long, List<StaffRunRiderAggregateView>> riderAggregatesOf(AuthUser requester, List<Long> runIds) {
        return runRiderRepository.aggregateForStaffDashboard(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffRunRiderAggregateView::runId));
    }

    private Map<Long, List<StaffAssignmentAckView>> ackViewsOf(AuthUser requester, List<Long> runIds) {
        return assignmentRepository.findAckViewsForStaffDashboard(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffAssignmentAckView::runId));
    }

    private Map<Long, List<StaffNoShowCaseView>> noShowViewsOf(AuthUser requester, List<Long> runIds) {
        return noShowCaseRepository.findActiveForStaffDashboard(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffNoShowCaseView::runId));
    }

    /** 케이스별 연락 시도(시각순) — 방금 학원 범위로 읽은 진행 중 케이스의 식별자로만 읽는다. */
    private Map<Long, List<NoShowContact>> contactsOf(Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun) {
        List<Long> caseIds = noShowViewsByRun.values().stream().flatMap(List::stream).map(StaffNoShowCaseView::caseId)
                .toList();
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        return noShowContactRepository.findAllByNoShowCaseIdInOrderByAttemptedAtAscIdAsc(caseIds).stream()
                .collect(Collectors.groupingBy(NoShowContact::getNoShowCaseId));
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
        int boarded = sumByStatus(allAgg, RiderStatus.BOARDED);
        int noShow = sumByStatus(allAgg, RiderStatus.NO_SHOW);
        int absent = sumByStatus(allAgg, RiderStatus.ABSENT);
        int unassignedManagers = (int) managerRepository
                .countUnassignedForStaffDashboard(requester.academyId(), runIds);
        return new StaffDashboardResponse.Metrics(movingBuses, boarded, noShow, absent, unassignedManagers);
    }

    /** 회차 하나를 응답 한 행으로 접는다 — 재료는 {@link RunLookups} 에 회차 수와 무관하게 미리 모아 둔 조회 결과다(BR-101). */
    private StaffDashboardResponse.Run toRunResponse(Run run, RunLookups lookups) {
        List<StaffRunRiderAggregateView> agg = lookups.riderAggByRun().getOrDefault(run.getId(), List.of());
        int boardedCount = sumByStatus(agg, RiderStatus.BOARDED);
        int totalCount = (int) agg.stream().mapToLong(StaffRunRiderAggregateView::count).sum();
        int addedCount = sumByChange(agg, ChangeType.ADDED);
        int removedCount = sumByChange(agg, ChangeType.REMOVED);

        List<StaffAssignmentAckView> acks = lookups.ackViewsByRun().getOrDefault(run.getId(), List.of());
        String driverName = nameOf(acks, ManagerRole.DRIVER);
        String escortName = nameOf(acks, ManagerRole.ESCORT);
        boolean ackDriver = ackedOf(acks, ManagerRole.DRIVER);
        boolean ackEscort = ackedOf(acks, ManagerRole.ESCORT);

        List<StaffDashboardResponse.NoShowCase> noShowCases = lookups.noShowViewsByRun()
                .getOrDefault(run.getId(), List.of()).stream()
                .map(view -> noShowCaseOf(view, lookups.contactsByCaseId().getOrDefault(view.caseId(), List.of())))
                .toList();
        Integer delayMinutes = run.getStatus() == RunStatus.MOVING
                ? RunDelayCalculator.minutesOf(run, lookups.stopsByRun().getOrDefault(run.getId(), List.of()))
                : null;

        return new StaffDashboardResponse.Run(run.getId(), lookups.busNos().get(run.getBusId()),
                lower(run.getDirection().name()), run.getDepartTime(), driverName, escortName, boardedCount,
                totalCount, lower(run.getStatus().name()), addedCount, removedCount, ackDriver, ackEscort,
                noShowCases, run.getStartedAt(), run.getFinishedAt(), estArrivalTimeOf(run),
                phoneOf(acks, ManagerRole.DRIVER), phoneOf(acks, ManagerRole.ESCORT),
                sumByStatus(agg, RiderStatus.NO_SHOW), sumByStatus(agg, RiderStatus.ABSENT), delayMinutes,
                lookups.lastDelayNoticeByRun().get(run.getId()));
    }

    /** 연락 시도는 시각순으로 들어온다 — 마지막 원소가 가장 늦은 시도다. */
    private StaffDashboardResponse.NoShowCase noShowCaseOf(StaffNoShowCaseView view, List<NoShowContact> contacts) {
        String lastResult = contacts.isEmpty() ? null : lower(contacts.get(contacts.size() - 1).getResult().name());
        return new StaffDashboardResponse.NoShowCase(view.studentName(), view.stopName(), view.expiresAt(),
                contacts.size(), lastResult);
    }

    /**
     * 예정 도착 = 예정 출발 + 소요 시간 추정치(R21-B2). {@code est_duration_min} 은 스케줄 생성 시점에
     * 정해지고 그 뒤 안 바뀐다({@code Run} 자바독 — setter 부재) — 이 회차엔 도착 예정을 계산할 근거가
     * 없다는 뜻이라 {@code null} 로 둔다({@code AssignmentConflictDetector.windowEnd} 와 같은 null
     * 가드 방식).
     */
    private OffsetDateTime estArrivalTimeOf(Run run) {
        return run.getEstDurationMin() == null ? null : run.getDepartTime().plusMinutes(run.getEstDurationMin());
    }

    private int sumByStatus(List<StaffRunRiderAggregateView> agg, RiderStatus status) {
        return (int) agg.stream().filter(view -> view.status() == status).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }

    private int sumByChange(List<StaffRunRiderAggregateView> agg, ChangeType change) {
        return (int) agg.stream().filter(view -> view.change() == change).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }

    private String phoneOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::phone).findFirst()
                .orElse(null);
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

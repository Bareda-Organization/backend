package src.backend.monitoring.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.StaffNoShowCaseView;
import src.backend.exception.entity.NoShowContact;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.repository.NoShowContactRepository;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.routing.entity.RunStop;
import src.backend.routing.query.RunOrderedStopsLoader;
import src.backend.run.entity.DelayNotice;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.run.repository.DelayRecipientCount;

/**
 * 운행 대시보드가 회차 수와 무관하게 한 번씩 읽는 조회(버스 번호 · 탑승 집계 · 배치 확인 · 노쇼 · 지연 알림 · 정차)를
 * {@link StaffDashboardRunLookups} 로 모은다. 트랜잭션은 호출하는 {@link StaffDashboardQueryService} 가 연다.
 */
@Component
@RequiredArgsConstructor
class StaffDashboardLookupLoader {

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final AssignmentRepository assignmentRepository;

    private final NoShowCaseRepository noShowCaseRepository;

    private final NoShowContactRepository noShowContactRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final RunOrderedStopsLoader runOrderedStopsLoader;

    /** 회차들에 필요한 조회 결과를 모두 읽어 묶는다. */
    StaffDashboardRunLookups load(AuthUser requester, List<Run> runs, List<Long> runIds) {
        Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun = riderAggregatesOf(requester, runIds);
        Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun = noShowViewsOf(requester, runIds);
        return new StaffDashboardRunLookups(busNosOf(requester, runs), riderAggByRun,
                ackViewsOf(requester, runIds), noShowViewsByRun, contactsOf(noShowViewsByRun),
                stopsOfMovingRuns(requester, runs), lastDelayNoticesOf(requester, runIds));
    }

    /** 지연 분(§5.18 과 같은 계산)은 운행 중 회차만 읽으므로 그 회차들의 확정 노선 정차 항목만 배치로 읽는다(BR-247). */
    private Map<Long, List<RunStop>> stopsOfMovingRuns(AuthUser requester, List<Run> runs) {
        List<Run> moving = runs.stream().filter(run -> run.getStatus() == RunStatus.MOVING).toList();
        return moving.isEmpty() ? Map.of() : runOrderedStopsLoader.load(requester.academyId(), moving);
    }

    /**
     * 회차별 마지막 지연 알림 + 그 알림이 적재한 수신 건수(Ruling 810) — 알림 적재가 지연 알림 행에 회차 식별자를 따로 두지 않아 건수는
     * {@link DelayNoticeRepository#countRecipientsOfNotices} 가 {@code dedup_key} 로 센다.
     */
    private Map<Long, StaffDashboardResponse.LastDelayNotice> lastDelayNoticesOf(AuthUser requester,
            List<Long> runIds) {
        List<DelayNotice> lastNotices = delayNoticeRepository.findLastByRunIds(requester.academyId(), runIds);
        if (lastNotices.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> recipientCounts = delayNoticeRepository
                .countRecipientsOfNotices(requester.academyId(), lastNotices.stream().map(DelayNotice::getId).toList())
                .stream().collect(Collectors.toMap(DelayRecipientCount::getRunId, DelayRecipientCount::getRecipientCount));
        return lastNotices.stream().collect(Collectors.toMap(DelayNotice::getRunId,
                notice -> new StaffDashboardResponse.LastDelayNotice(notice.getMinutes(),
                        QueryLabels.lower(notice.getReason().name()), notice.getSentAt(),
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
}

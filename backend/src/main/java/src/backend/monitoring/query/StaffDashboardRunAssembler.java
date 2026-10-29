package src.backend.monitoring.query;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.boarding.entity.RiderStatus;
import src.backend.exception.dto.StaffNoShowCaseView;
import src.backend.exception.entity.NoShowContact;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/** 운행 대시보드의 회차 한 행을 미리 읽어 둔 조회 결과로 조립하고, 탑승 집계 합산을 맡는다. */
final class StaffDashboardRunAssembler {

    private StaffDashboardRunAssembler() {
    }

    /** 회차 하나를 응답 한 행으로 접는다 — 재료는 {@link StaffDashboardRunLookups} 에 회차 수와 무관하게 미리 모아 둔 조회 결과다(BR-101). */
    static StaffDashboardResponse.Run toRunResponse(Run run, StaffDashboardRunLookups lookups) {
        List<StaffRunRiderAggregateView> agg = lookups.riderAggByRun().getOrDefault(run.getId(), List.of());
        List<StaffAssignmentAckView> acks = lookups.ackViewsByRun().getOrDefault(run.getId(), List.of());
        return StaffDashboardResponse.Run.builder()
                .runId(run.getId()).busNo(lookups.busNos().get(run.getBusId()))
                .direction(QueryLabels.lower(run.getDirection().name())).departTime(run.getDepartTime())
                .driverName(QueryLabels.nameOf(acks, ManagerRole.DRIVER))
                .escortName(QueryLabels.nameOf(acks, ManagerRole.ESCORT))
                .boardedCount(boardedSoFar(agg)).totalCount((int) agg.stream().mapToLong(StaffRunRiderAggregateView::count).sum())
                .runStatus(QueryLabels.lower(run.getStatus().name())).addedCount(sumByChange(agg, ChangeType.ADDED))
                .removedCount(sumByChange(agg, ChangeType.REMOVED))
                .ackDriver(QueryLabels.ackedOf(acks, ManagerRole.DRIVER))
                .ackEscort(QueryLabels.ackedOf(acks, ManagerRole.ESCORT)).noShowCases(noShowCasesOf(run, lookups))
                .startedAt(run.getStartedAt()).finishedAt(run.getFinishedAt()).estArrivalTime(estArrivalTimeOf(run))
                .driverPhone(QueryLabels.phoneOf(acks, ManagerRole.DRIVER))
                .escortPhone(QueryLabels.phoneOf(acks, ManagerRole.ESCORT))
                .noShowCount(sumByStatus(agg, RiderStatus.NO_SHOW)).absentCount(sumByStatus(agg, RiderStatus.ABSENT))
                .delayMinutes(delayMinutesOf(run, lookups)).lastDelayNotice(lookups.lastDelayNoticeByRun().get(run.getId())).build();
    }

    private static List<StaffDashboardResponse.NoShowCase> noShowCasesOf(Run run, StaffDashboardRunLookups lookups) {
        return lookups.noShowViewsByRun().getOrDefault(run.getId(), List.of()).stream()
                .map(view -> noShowCaseOf(view, lookups.contactsByCaseId().getOrDefault(view.caseId(), List.of())))
                .toList();
    }

    private static Integer delayMinutesOf(Run run, StaffDashboardRunLookups lookups) {
        return run.getStatus() == RunStatus.MOVING
                ? RunDelayCalculator.minutesOf(run, lookups.stopsByRun().getOrDefault(run.getId(), List.of()))
                : null;
    }

    /** 연락 시도는 시각순으로 들어온다 — 마지막 원소가 가장 늦은 시도다. */
    private static StaffDashboardResponse.NoShowCase noShowCaseOf(StaffNoShowCaseView view,
            List<NoShowContact> contacts) {
        String lastResult = contacts.isEmpty() ? null : QueryLabels.lower(contacts.get(contacts.size() - 1).getResult().name());
        return new StaffDashboardResponse.NoShowCase(view.studentName(), view.stopName(), view.expiresAt(),
                contacts.size(), lastResult);
    }

    /**
     * 예정 도착 = 예정 출발 + 소요 시간 추정치. {@code est_duration_min} 은 스케줄 생성 시점에
     * 정해지고 그 뒤 안 바뀐다({@code Run} 자바독 — setter 부재) — 이 회차엔 도착 예정을 계산할 근거가
     * 없다는 뜻이라 {@code null} 로 둔다({@code AssignmentConflictDetector.windowEnd} 와 같은 null
     * 가드 방식).
     */
    private static OffsetDateTime estArrivalTimeOf(Run run) {
        return run.getEstDurationMin() == null ? null : run.getDepartTime().plusMinutes(run.getEstDurationMin());
    }

    /** 상태가 {@code status} 인 탑승자 수의 합. */
    static int sumByStatus(List<StaffRunRiderAggregateView> agg, RiderStatus status) {
        return (int) agg.stream().filter(view -> view.status() == status).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }

    /** 탑승 완료 누계(Ruling 879) — 지금 타고 있는 학생({@code boarded})과 이미 내린 학생({@code alighted})을 함께 센다. */
    static int boardedSoFar(List<StaffRunRiderAggregateView> agg) {
        return sumByStatus(agg, RiderStatus.BOARDED) + sumByStatus(agg, RiderStatus.ALIGHTED);
    }

    private static int sumByChange(List<StaffRunRiderAggregateView> agg, ChangeType change) {
        return (int) agg.stream().filter(view -> view.change() == change).mapToLong(StaffRunRiderAggregateView::count)
                .sum();
    }
}

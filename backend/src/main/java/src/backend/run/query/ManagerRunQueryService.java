package src.backend.run.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.run.dto.ManagerRunResponse;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 매니저 앱의 담당 회차 목록(API_SPEC §4.1 {@code GET /manager/runs}, RUN-01·M-02·M-07,
 * Phase 9 목표 6·15·16·17) — 배치({@code assignment}) 가 회차 접근 범위 자체다.
 *
 * <p>배치가 없는 매니저는 빈 목록을 받는다(§4.1 "배정 회차 부재는 빈 items[] 로 반환") — 이 조회는
 * {@link ManagerRunAccess#requireManager} 로 매니저 레코드만 확인하고, 특정 회차 1건에 대한
 * {@link ManagerRunAccess#requireAssignedRun} 판정은 §4.2·§4.3 이 쓴다(이 목록 자체가 이미
 * "배치된 회차만" 이라 회차별 재판정이 필요 없다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerRunQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final Clock clock;

    /**
     * 요청 주체가 배치된 그 날짜의 회차 카드 전부 — 날짜를 주지 않으면 오늘이다.
     *
     * <p>회차 하나마다 명단·확정 노선을 되읽는다({@link #addedRemovedOf}·{@link #ackRequiredOf}) —
     * 매니저 한 명의 하루 담당 회차는 차량 1대 기준 왕복 2건 안팎이라, 이 규모에서는 회차별 조회를
     * 미리 묶어 두는 것보다 그대로 도는 편이 코드를 단순하게 유지한다(§5.10 {@code RunQueryService}
     * 의 N+1 회피와 다른 판단 — 그쪽은 학원 전체 회차라 규모가 다르다).
     */
    public List<ManagerRunResponse> list(AuthUser requester, String requestedDate) {
        Manager manager = managerRunAccess.requireManager(requester);
        LocalDate serviceDate = requestedDate == null ? LocalDate.now(clock) : ApiValues.date(requestedDate);
        List<Assignment> assignments = assignmentRepository.findByManagerIdAndAcademyIdAndServiceDate(
                manager.getId(), requester.academyId(), serviceDate);
        if (assignments.isEmpty()) {
            return List.of();
        }
        List<Long> runIds = assignments.stream().map(Assignment::getRunId).toList();
        List<Run> runs = runRepository.findAllByIdInAndAcademyId(runIds, requester.academyId());
        Map<Long, Run> runsById = runs.stream().collect(Collectors.toMap(Run::getId, run -> run));
        Map<Long, String> busNos = busNosOf(requester, runs);
        return assignments.stream()
                .map(assignment -> runsById.get(assignment.getRunId()) == null ? null
                        : toResponse(requester, runsById.get(assignment.getRunId()),
                                busNos.get(runsById.get(assignment.getRunId()).getBusId()), assignment))
                .filter(response -> response != null)
                .toList();
    }

    private ManagerRunResponse toResponse(AuthUser requester, Run run, String busNo, Assignment assignment) {
        long[] addedRemoved = addedRemovedOf(requester, run);
        boolean ackRequired = ackRequiredOf(requester, run, assignment);
        return ManagerRunResponse.of(run, busNo, assignment, addedRemoved[0], addedRemoved[1], ackRequired);
    }

    /** {@code [added_count, removed_count]} — ②구간 승인이 이 회차 명단에 반영한 변경 배지. */
    private long[] addedRemovedOf(AuthUser requester, Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), requester.academyId());
        long added = riders.stream().filter(rider -> rider.getChange() == ChangeType.ADDED).count();
        long removed = riders.stream().filter(rider -> rider.getChange() == ChangeType.REMOVED).count();
        return new long[] { added, removed };
    }

    /** 확정 노선의 현재 버전과 이 배치가 마지막으로 확인한 버전이 다르면 확인 응답이 미완료다(RUN-07). */
    private boolean ackRequiredOf(AuthUser requester, Run run, Assignment assignment) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return false;
        }
        return !currentVersionId.equals(assignment.getAckedRouteVersionId());
    }

    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }
}

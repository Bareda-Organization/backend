package src.backend.run.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
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
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.RunStartWindowPolicy;
import src.backend.run.dto.ManagerRunResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 매니저 앱의 담당 회차 목록(API_SPEC §4.1 {@code GET /manager/runs}, RUN-01·M-02·M-07) — 배치({@code assignment}) 가 회차 접근 범위 자체다.
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

    private final RunStopRepository runStopRepository;

    private final RunStartWindowPolicy runStartWindowPolicy;

    private final Clock clock;

    /**
     * 요청 주체가 배치된 그 날짜의 회차 카드 전부 — 날짜를 주지 않으면 오늘이다.
     *
     * <p>명단 · 확정 노선 · 정차 순서는 회차 묶음으로 한 번씩 읽는다 — 회차마다 읽으면 출근 시간에 매니저 수 × 회차 수만큼 쿼리가 쌓인다. 차량 행을 찾지 못한 회차는 차량 번호 ·
     * 차량 표시를 비워 돌려준다.
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
        Map<Long, Bus> buses = busesOf(requester, runs);
        Materials materials = materialsOf(requester, runs);
        return assignments.stream()
                .map(assignment -> runsById.get(assignment.getRunId()) == null ? null
                        : toResponse(requester, runsById.get(assignment.getRunId()),
                                buses.get(runsById.get(assignment.getRunId()).getBusId()), assignment, materials))
                .filter(response -> response != null)
                .toList();
    }

    private ManagerRunResponse toResponse(AuthUser requester, Run run, Bus bus, Assignment assignment,
            Materials materials) {
        List<RunRider> riders = materials.riders().getOrDefault(run.getId(), List.of());
        long added = riders.stream().filter(rider -> rider.getChange() == ChangeType.ADDED).count();
        long removed = riders.stream().filter(rider -> rider.getChange() == ChangeType.REMOVED).count();
        boolean ackRequired = ackRequiredOf(run, assignment, materials);
        return ManagerRunResponse.of(run, bus == null ? null : bus.getBusNo(), bus == null ? null : bus.getPlateNo(),
                assignment, added, removed, ackRequired, headcountOf(run, riders, materials), runStartWindowPolicy);
    }

    /** 회차 묶음의 명단 · 확정 노선 현재 버전 · 정차 순서 — 회차 수와 무관하게 쿼리 세 번으로 읽는다. */
    private Materials materialsOf(AuthUser requester, List<Run> runs) {
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<Long, List<RunRider>> riders = runRiderRepository.findAllByRunIdInAndAcademyId(runIds, requester.academyId())
                .stream().collect(Collectors.groupingBy(RunRider::getRunId));
        Map<Long, Long> versionByRun = confirmedRouteRepository
                .findAllByRunIdInAndAcademyId(runIds, requester.academyId()).stream()
                .filter(route -> route.getCurrentVersionId() != null)
                .collect(Collectors.toMap(ConfirmedRoute::getRunId, ConfirmedRoute::getCurrentVersionId));
        Map<Long, List<RunStop>> stopsByVersion = versionByRun.isEmpty() ? Map.of()
                : runStopRepository.findAllByRouteVersionIdInAndAcademyId(Set.copyOf(versionByRun.values()),
                        requester.academyId()).stream().collect(Collectors.groupingBy(RunStop::getRouteVersionId));
        return new Materials(riders, versionByRun, stopsByVersion);
    }

    /**
     * 홈 · 운행 준비 화면의 "학생 14명 · 승하차지 6곳 · 미등원 2명"(Ruling 822) — 확정 전이면 명단·정차 항목이 아직 없어 {@code null} 이다.
     * 탑승 예정은 {@code absent} 를 뺀 명단 수, 미등원은 §4.2 {@code counts.absent_n} 과 같은 정의(버스 간 이동으로 빠진 학생은 미등원이 아니다),
     * 승하차지는 §4.2 {@code stops[]} 에서 도착지·강제 경유 지점을 뺀 수(승하차지 마스터를 가리키는 정차 항목)다.
     */
    private ManagerRunResponse.Headcount headcountOf(Run run, List<RunRider> riders, Materials materials) {
        if (run.getStatus() == RunStatus.IDLE) {
            return null;
        }
        int riderCount = (int) riders.stream().filter(rider -> rider.getStatus() != RiderStatus.ABSENT).count();
        int absentCount = (int) riders.stream()
                .filter(rider -> rider.getStatus() == RiderStatus.ABSENT && rider.getChange() != ChangeType.REMOVED)
                .count();
        Long versionId = materials.versionByRun().get(run.getId());
        int stopCount = versionId == null ? 0
                : (int) materials.stopsByVersion().getOrDefault(versionId, List.of()).stream()
                        .filter(stop -> stop.getStopId() != null).count();
        return new ManagerRunResponse.Headcount(riderCount, absentCount, stopCount);
    }

    /** 확정 노선의 현재 버전과 이 배치가 마지막으로 확인한 버전이 다르면 확인 응답이 미완료다(RUN-07). */
    private boolean ackRequiredOf(Run run, Assignment assignment, Materials materials) {
        Long currentVersionId = materials.versionByRun().get(run.getId());
        return currentVersionId != null && !currentVersionId.equals(assignment.getAckedRouteVersionId());
    }

    private Map<Long, Bus> busesOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, bus -> bus));
    }

    /** 목록 한 번이 읽는 회차 묶음의 재료 — 회차 id 와 확정 노선 버전 id 로 찾는다. */
    private record Materials(Map<Long, List<RunRider>> riders, Map<Long, Long> versionByRun,
            Map<Long, List<RunStop>> stopsByVersion) {
    }
}

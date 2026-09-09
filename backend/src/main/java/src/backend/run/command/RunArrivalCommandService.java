package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RemainingRiderView;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.dto.RunArriveResponse;
import src.backend.run.dto.RunArriveResponse.NextStopResponse;
import src.backend.run.dto.RunArriveResponse.RemainingRiderResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.run.event.StopArrivedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 기사의 승하차지 도착 처리(API_SPEC §4.5, RUN-04) — Phase 9 goal 5(기사 전용 인가) · goal 9(등원
 * 최종 지점 전원 자동 하차) · goal 10(하원 최종 지점 미하차 잔류 시 종료 보류)을 담당한다.
 *
 * <p>하원 잔류가 0명에 도달한 <b>뒤</b>의 종료 전이(마지막 탑승자가 개별 하차 처리될 때)는 이
 * 서비스의 책임이 아니다 — {@link RunCompletionService} 가 그 시점(§4.6 승하차 처리)에 호출된다.
 * 이 서비스는 최종 지점 도착 그 순간의 판정(즉시 종료 대 보류)만 담당한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunArrivalCommandService {

    private final RunRepository runRepository;

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public RunArriveResponse arrive(AuthUser requester, Long runId, Long stopId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        Long routeVersionId = currentVersionIdOf(runId);
        RunStop target = runStopRepository.findByRouteVersionIdAndStopId(routeVersionId, stopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOP_NOT_FOUND));
        if (target.getArrivedAt() != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_ARRIVE);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        target.markArrived(now);

        List<RunStop> ordered = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(routeVersionId,
                requester.academyId());
        boolean isFinal = isLastStop(ordered, target);
        NextStopResponse nextStop = isFinal ? null : nextStopAfter(ordered, target);

        eventPublisher.publishEvent(new StopArrivedEvent(runId, run.getAcademyId(), target.getStopId(),
                target.getSeq(), nameOf(target), now, nextStop == null ? null : nextStop.stopId()));

        Integer autoAlightedCount = null;
        List<RemainingRiderResponse> remaining = List.of();

        if (isFinal) {
            if (run.getDirection() == Direction.TO_ACADEMY) {
                autoAlightedCount = alightAllBoarded(run, now);
            } else {
                long stillBoarded = runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.BOARDED);
                if (stillBoarded == 0) {
                    run.finish(now);
                } else {
                    run.deferFinish();
                    remaining = remainingRidersOf(runId);
                }
            }
        }

        return RunArriveResponse.of(now, nextStop, isFinal, run, remaining, autoAlightedCount);
    }

    private Long currentVersionIdOf(Long runId) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        return confirmedRoute.getCurrentVersionId();
    }

    private boolean isLastStop(List<RunStop> ordered, RunStop target) {
        return !ordered.isEmpty() && ordered.get(ordered.size() - 1).getId().equals(target.getId());
    }

    private NextStopResponse nextStopAfter(List<RunStop> ordered, RunStop target) {
        int index = ordered.indexOf(target);
        RunStop next = ordered.get(index + 1);
        return new NextStopResponse(next.getStopId() != null ? next.getStopId() : next.getWaypointId(),
                nameOf(next));
    }

    private String nameOf(RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(Stop::getName).orElse(null);
        }
        return waypointRepository.findById(stop.getWaypointId()).map(Waypoint::getLabel).orElse(null);
    }

    /** 등원 최종 지점 도착 — 아직 탑승 중인 전원을 하차 처리하고 학생별 자동 하차 이벤트를 발행한다(C-07·goal 9). */
    private int alightAllBoarded(Run run, OffsetDateTime now) {
        List<RunRider> boarded = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId())
                .stream()
                .filter(rider -> rider.getStatus() == RiderStatus.BOARDED)
                .toList();
        boarded.forEach(rider -> {
            rider.alight(now);
            eventPublisher.publishEvent(new RunAutoAlightedEvent(run.getId(), run.getAcademyId(),
                    rider.getStudentId(), now));
        });
        run.finish(now);
        return boarded.size();
    }

    private List<RemainingRiderResponse> remainingRidersOf(Long runId) {
        List<RemainingRiderView> views = runRiderRepository.findRemainingByRunIdAndStatus(runId, RiderStatus.BOARDED);
        return views.stream()
                .map(view -> new RemainingRiderResponse(view.getRiderId(), view.getName(), view.getStopName()))
                .toList();
    }
}

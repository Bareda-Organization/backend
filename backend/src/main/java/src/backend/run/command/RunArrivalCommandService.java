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
import src.backend.location.proximity.StopDepartureService;
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

    /**
     * 다음 승하차지 도착 시 이전 정차지에 거는 출발 강제 폴백(Ruling 308, 목표 7) — 위치 신호 유실·
     * 기사의 도착 미처리로 출발 판정(100m 이탈)이 안 됐어도, 다음 정차지 도착 처리가 그 이전 정차지를
     * 대신 출발 처리한다.
     */
    private final StopDepartureService stopDepartureService;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public RunArriveResponse arrive(AuthUser requester, Long runId, Long stopId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 폴백(Ruling 308, 목표 7) — 이 새 도착 처리보다 먼저 도착했지만 아직 출발 판정이 안 된
        // 정차지가 있으면 강제로 출발 처리한다. run·target 을 로드하기 <b>전에</b> 부른다 —
        // claimDeparture 의 clearAutomatically 가 영속성 컨텍스트를 비우는데, 그 뒤에 로드한
        // 엔티티만 이 트랜잭션 끝까지 안전하게 관리된다(먼저 로드해 두면 이 호출이 그 엔티티를
        // detach 시켜, 그 뒤의 markArrived·finish 같은 변경이 커밋되지 않고 조용히 사라진다).
        stopDepartureService.forceAllRemaining(runId, requester.academyId(), now);

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
                // 마지막 승하차지 강제 출발(Ruling 312, 목표 8) — 방금 도착 처리한 이 최종 지점은
                // 다음 정차지가 없어 위쪽 폴백을 받지 못한다. **alightAllBoarded 보다 먼저** 불러야
                // 한다 — 순서가 거꾸로면 이 최종 지점에서 승차한 학생의 상태가 이미 ALIGHTED 로
                // 바뀐 뒤라 그 정차지의 확정 결과가 "승차"가 아니라 "하차"로 잘못 나가고,
                // RunAutoAlightedEvent 의 ALIGHTING 과 중복까지 된다(조율자 R15-T3 후속 지적).
                stopDepartureService.forceAllRemaining(runId, run.getAcademyId(), now);
                // 위 호출의 claimDeparture(clearAutomatically) 가 영속성 컨텍스트를 비워 run 을
                // detach 시킨다 — 다시 로드해야 alightAllBoarded 안의 run.finish(now) 가 유실되지
                // 않는다(RunArrivalCommandService 클래스 상단 주석과 같은 근거).
                run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
                autoAlightedCount = alightAllBoarded(run, now);
            } else {
                long stillBoarded = runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.BOARDED);
                if (stillBoarded == 0) {
                    run.finish(now);
                    // 같은 근거(Ruling 312, 목표 8) — 하원 최종 지점도 즉시 종료되면 다음 정차지가 없다.
                    stopDepartureService.forceAllRemaining(runId, run.getAcademyId(), now);
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

package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.command.RunRiderPersistence;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.run.event.RunEndedEvent;
import src.backend.boarding.repository.RemainingRiderView;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.ChangeType;
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

    @PersistenceContext
    private EntityManager entityManager;

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunRiderPersistence runRiderPersistence;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final AcademyRepository academyRepository;

    /**
     * 다음 승하차지 도착 시 이전 정차지에 거는 출발 강제 폴백(Ruling 308, 목표 7) — 위치 신호 유실·
     * 기사의 도착 미처리로 출발 판정(100m 이탈)이 안 됐어도, 다음 정차지 도착 처리가 그 이전 정차지를
     * 대신 출발 처리한다.
     */
    private final StopDepartureService stopDepartureService;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 기사가 배치된 회차인지 확인한 뒤 정차지 도착을 기록하고, 최종 지점이면 등원은 전원 자동 하차·
     * 하원은 미하차 잔류에 따라 즉시 종료 또는 보류를 판정한다(§4.5).
     *
     * <p>CODE_CONVENTIONS §20.2 — 본문·중첩이 기준을 넘긴 채 둔다. "도착 기록 → 최종 지점 여부 → (최종이면) 방향별
     * 분기" 가 한 번의 도착 처리가 겪는 단일 판정 트리라, private 메서드로 쪼개면 그 트리를 따라가기
     * 위해 여러 파일을 오가야 한다.
     *
     * @param runStopId 정차 항목 id({@code run_stop.id}) — 학생 승하차지 · 경유 지점 · 학원 항목을 한 값으로
     *                  가리킨다(Ruling 327). 매니저 앱은 API_SPEC §4.2 명단의 {@code stop_id} 를 그대로 넘긴다
     */
    public RunArriveResponse arrive(AuthUser requester, Long runId, Long runStopId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 폴백(Ruling 308, 목표 7 · R51 H2) — 이 새 도착 처리보다 앞 순번이면서 아직 출발 판정이 안 된
        // 정차지가 있으면 도착 처리 여부와 무관하게 강제로 출발 처리한다(기사가 도착을 안 누른 정차지 포함). run·target 을 로드하기 <b>전에</b> 부른다 —
        // claimDeparture 의 clearAutomatically 가 영속성 컨텍스트를 비우는데, 그 뒤에 로드한
        // 엔티티만 이 트랜잭션 끝까지 안전하게 관리된다(먼저 로드해 두면 이 호출이 그 엔티티를
        // detach 시켜, 그 뒤의 markArrived·finish 같은 변경이 커밋되지 않고 조용히 사라진다).
        // 등원 학원 항목 도착이면 이 호출이 마지막 승차지까지 전부 출발시킨다 — 그래서 아래 자동
        // 하차보다 먼저 일어나 그 승차지의 확정 결과가 "승차" 로 나간다(R15-T3 후속과 같은 순서).
        stopDepartureService.forceAllBefore(runId, requester.academyId(), runStopId, now);

        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        // 회차 행을 잠그고 다시 읽은 뒤 판정한다(BR-041) — 같은 정차 항목 도착 두 건, 하원 최종 도착과 마지막
        // 하차({@link RunCompletionService} 도 같은 행을 잠근다)가 겹쳐도 한쪽이 끝난 뒤에 세고 판정한다.
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        if (run.getStatus() != RunStatus.MOVING) {
            // 이미 처리된 도착의 재전송은 회차가 끝난 뒤에도 DUPLICATE_ARRIVE 다(API_SPEC §4.5 · Ruling 859) — 앱은 이 짝만 성공으로
            // 흡수하므로, 회차를 끝낸 도착이나 끝난 뒤 재생된 앞 도착이 RUN_NOT_MOVING 으로 돌아오면 처리된 도착이 "안 됨" 으로 센다.
            if (isAlreadyArrived(runId, requester.academyId(), runStopId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_ARRIVE);
            }
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        List<RunStop> ordered = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                currentVersionIdOf(runId), requester.academyId());
        RunStop target = ordered.stream()
                .filter(stop -> stop.getId().equals(runStopId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOP_NOT_FOUND));
        if (target.getArrivedAt() != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_ARRIVE);
        }
        target.markArrived(now);

        boolean isFinal = isFinalStop(ordered, target);
        RunStop next = isFinal ? null : nextStopAfter(ordered, target);
        NextStopResponse nextStop = next == null ? null : new NextStopResponse(next.getId(), nameOf(run, next));

        eventPublisher.publishEvent(new StopArrivedEvent(runId, run.getAcademyId(), target.getId(),
                target.getSeq(), nameOf(run, target), now, next == null ? null : next.getId()));

        Integer autoAlightedCount = null;
        List<RemainingRiderResponse> remaining = List.of();

        if (isFinal) {
            if (run.getDirection() == Direction.TO_ACADEMY) {
                autoAlightedCount = alightAllBoarded(run, now);
                // 종료 3경로 모두 run_ended 의 재료를 낸다(BR-037, §4.10) — 전에는 하원 보류 해제 경로만 냈다.
                eventPublisher.publishEvent(new RunEndedEvent(runId, run.getAcademyId(), now, autoAlightedCount));
            } else {
                long stillBoarded = runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.BOARDED);
                if (stillBoarded == 0) {
                    run.finish(now);
                    // 마지막 승하차지 강제 출발(Ruling 312, 목표 8) — 하원 최종 지점은 방금 도착한 하차지
                    // 자신이라 위쪽 폴백을 받지 못한다.
                    stopDepartureService.forceAllRemaining(runId, run.getAcademyId(), now);
                    eventPublisher.publishEvent(new RunEndedEvent(runId, run.getAcademyId(), now, 0));
                } else {
                    run.deferFinish();
                    remaining = remainingRidersOf(runId);
                }
            }
        }

        return RunArriveResponse.of(now, nextStop, isFinal, run, remaining, autoAlightedCount);
    }

    /** 이 회차의 확정 노선에서 그 정차 항목이 이미 도착 처리됐는가 — 확정 노선이 없거나 다른 회차의 항목이면 {@code false}. */
    private boolean isAlreadyArrived(Long runId, Long academyId, Long runStopId) {
        return confirmedRouteRepository.findById(runId)
                .map(route -> runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                        route.getCurrentVersionId(), academyId))
                .orElse(List.of()).stream()
                .anyMatch(stop -> stop.getId().equals(runStopId) && stop.getArrivedAt() != null);
    }

    private Long currentVersionIdOf(Long runId) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        return confirmedRoute.getCurrentVersionId();
    }

    /**
     * 최종 지점 — 뒤에 도착 처리할 항목이 없다(C-15). 경유 지점은 지나가는 점이라 도착 처리 대상이 아니고,
     * 미경유({@code skipped})는 서지 않으므로 둘 다 "남은 항목" 으로 세지 않는다(BR-015 — 맨 뒤 경유
     * 지점 때문에 마지막 하차지 도착이 최종이 안 되던 결함). 등원은 학원 항목이 늘 맨 뒤라 그 도착만 최종이다.
     */
    private boolean isFinalStop(List<RunStop> ordered, RunStop target) {
        return ordered.stream().noneMatch(stop -> stop.getSeq() > target.getSeq() && stop.getWaypointId() == null
                && stop.getChange() != ChangeType.SKIPPED);
    }

    /** 전진된 포인터 — 방금 도착한 항목 뒤의 첫 항목. 미경유는 건너뛴다(§4.3 {@code next_stop} 과 같은 규칙). */
    private RunStop nextStopAfter(List<RunStop> ordered, RunStop target) {
        return ordered.stream()
                .filter(stop -> stop.getSeq() > target.getSeq() && stop.getChange() != ChangeType.SKIPPED)
                .findFirst()
                .orElse(null);
    }

    private String nameOf(Run run, RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(Stop::getName).orElse(null);
        }
        if (stop.getWaypointId() != null) {
            return waypointRepository.findById(stop.getWaypointId()).map(Waypoint::getLabel).orElse(null);
        }
        return academyRepository.findById(run.getAcademyId()).map(Academy::getName).orElse(null);
    }

    /** 등원 최종 지점 도착 — 아직 탑승 중인 전원을 하차 처리하고 학생별 자동 하차 이벤트를 발행한다(C-07·goal 9). */
    private int alightAllBoarded(Run run, OffsetDateTime now) {
        List<RunRider> boarded = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId())
                .stream()
                .filter(rider -> rider.getStatus() == RiderStatus.BOARDED)
                .toList();
        boarded.forEach(rider -> {
            runRiderPersistence.alightBySystem(rider, now);
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

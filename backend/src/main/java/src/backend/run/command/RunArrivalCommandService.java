package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.dto.RunArriveResponse;
import src.backend.run.dto.RunArriveResponse.NextStopResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.StopArrivedEvent;
import src.backend.run.repository.RunRepository;

/**
 * 기사의 승하차지 도착 처리(API_SPEC §4.5, RUN-04) — 기사 전용 인가 · 등원 최종 지점 전원 자동 하차 · 하원 최종 지점 미하차 잔류 시 종료 보류를 담당한다.
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

    /** 회차 행 잠금 — 앞 승하차지 강제 출발보다 먼저 쥐어 승하차 기록(PATCH)과 같은 잠금 순서를 지킨다({@link RunRowLock}). */
    private final RunRowLock runRowLock;

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunFinalArrival finalArrival;

    private final RunStopLabels stopLabels;

    /**
     * 다음 승하차지 도착 시 이전 정차지에 거는 출발 강제 폴백(Ruling 308) — 위치 신호 유실·
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
     * @param runStopId 정차 항목 id({@code run_stop.id}) — 학생 승하차지 · 경유 지점 · 학원 항목을 한 값으로
     *                  가리킨다(Ruling 327). 매니저 앱은 API_SPEC §4.2 명단의 {@code stop_id} 를 그대로 넘긴다
     */
    public RunArriveResponse arrive(AuthUser requester, Long runId, Long runStopId) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Long academyId = requester.academyId();
        Run locked = lockedRun(runId, academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        assertMoving(locked, academyId, runStopId);
        Run run = departEarlierStops(runId, academyId, runStopId, now);
        Arrival arrival = recordArrival(run, runStopId, now);
        RunFinalArrival.Outcome outcome = arrival.isFinal() ? finalArrival.handle(run, now) : RunFinalArrival.Outcome.NONE;
        return RunArriveResponse.of(now, arrival.nextStop(), arrival.isFinal(), run, outcome.remaining(),
                outcome.autoAlightedCount());
    }

    /**
     * 회차 행을 가장 먼저 쥐고 상태를 새로 읽는다 — 이어지는 앞 승하차지 강제 출발이 승하차지 행을 먼저 쥐면, 승하차지 행을 쥔 채 회차 행을 기다리는
     * 승하차 기록(PATCH)과 서로를 기다린다. 도착 시각은 잠금을 얻은 뒤에 정해 도착 순서가 커밋 순서를 따른다.
     */
    private Run lockedRun(Long runId, Long academyId) {
        Run run = runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        runRowLock.acquire(run);
        return run;
    }

    /**
     * 폴백(Ruling 308) — 이 새 도착 처리보다 앞 순번이면서 아직 출발 판정이 안 된
     * 정차지가 있으면 도착 처리 여부와 무관하게 강제로 출발 처리한다(기사가 도착을 안 누른 정차지 포함). run·target 은 이 호출 <b>뒤에</b> 다시 읽는다 —
     * claimDeparture 의 clearAutomatically 가 영속성 컨텍스트를 비우는데, 그 뒤에 로드한
     * 엔티티만 이 트랜잭션 끝까지 안전하게 관리된다(먼저 로드해 두면 이 호출이 그 엔티티를
     * detach 시켜, 그 뒤의 markArrived·finish 같은 변경이 커밋되지 않고 조용히 사라진다).
     * 등원 학원 항목 도착이면 이 호출이 마지막 승차지까지 전부 출발시킨다 — 그래서 아래 자동
     * 하차보다 먼저 일어나 그 승차지의 확정 결과가 "승차" 로 나간다.
     *
     * <p>잠금은 이미 쥐었다 — 같은 정차 항목 도착 두 건, 하원 최종 도착과 마지막 하차({@link RunCompletionService} 도 같은 행을 잠근다)가 겹쳐도
     * 한쪽이 끝난 뒤에 세고 판정한다(BR-041).
     */
    private Run departEarlierStops(Long runId, Long academyId, Long runStopId, OffsetDateTime now) {
        stopDepartureService.forceAllBefore(runId, academyId, runStopId, now);
        return runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    /** 정차 항목에 도착 시각을 기록하고 도착 이벤트를 낸다 — 최종 지점 여부와 전진한 포인터를 함께 돌려준다. */
    private Arrival recordArrival(Run run, Long runStopId, OffsetDateTime now) {
        List<RunStop> ordered = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                currentVersionIdOf(run.getId()), run.getAcademyId());
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
        NextStopResponse nextStop = next == null ? null : new NextStopResponse(next.getId(), stopLabels.nameOf(run, next));

        eventPublisher.publishEvent(new StopArrivedEvent(run.getId(), run.getAcademyId(), target.getId(),
                target.getSeq(), stopLabels.nameOf(run, target), now, next == null ? null : next.getId()));
        return new Arrival(isFinal, nextStop);
    }

    /** 도착 기록의 결과 — 최종 지점 여부와 전진한 포인터. */
    private record Arrival(boolean isFinal, NextStopResponse nextStop) {
    }

    /**
     * 운행 중이 아니면 거절한다. 이미 처리된 도착의 재전송은 회차가 끝난 뒤에도 DUPLICATE_ARRIVE 다(API_SPEC §4.5 · Ruling 859) — 앱은 이 짝만 성공으로
     * 흡수하므로, 회차를 끝낸 도착이나 끝난 뒤 재생된 앞 도착이 RUN_NOT_MOVING 으로 돌아오면 처리된 도착이 "안 됨" 으로 센다.
     */
    private void assertMoving(Run run, Long academyId, Long runStopId) {
        if (run.getStatus() == RunStatus.MOVING) {
            return;
        }
        if (isAlreadyArrived(run.getId(), academyId, runStopId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_ARRIVE);
        }
        throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
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

}

package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.RunRiderPersistence;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RemainingRiderView;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.run.dto.RunArriveResponse.RemainingRiderResponse;
import src.backend.run.entity.Run;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.run.event.RunEndedEvent;

/**
 * 최종 지점 도착의 방향별 판정(API_SPEC §4.5, C-15) — 등원은 탑승 중인 전원을 자동 하차시켜 종료하고, 하원은 미하차 잔류가 없으면 즉시 종료하고 있으면 종료를 보류한다.
 * {@link RunArrivalCommandService#arrive} 가 부르는 같은 트랜잭션 안의 쓰기다.
 */
@Component
@RequiredArgsConstructor
class RunFinalArrival {

    private final RunRiderRepository runRiderRepository;

    private final RunRiderPersistence runRiderPersistence;

    /**
     * 정차지 도착 처리 응답에 실리는 최종 도착의 결과.
     *
     * @param autoAlightedCount 등원 최종 도착에서 자동 하차시킨 인원, 하원이면 {@code null}
     * @param remaining         하원 종료가 보류됐을 때 남은 탑승자, 아니면 빈 목록
     */
    record Outcome(Integer autoAlightedCount, List<RemainingRiderResponse> remaining) {

        /** 최종 지점이 아닌 도착 — 자동 하차도 잔류도 없다. */
        static final Outcome NONE = new Outcome(null, List.of());
    }

    private final StopDepartureService stopDepartureService;

    private final ApplicationEventPublisher eventPublisher;

    /** 최종 지점에 도착한 회차를 방향에 따라 종료하거나 종료를 보류한다. */
    Outcome handle(Run run, OffsetDateTime now) {
        if (run.getDirection() == Direction.TO_ACADEMY) {
            int autoAlightedCount = alightAllBoarded(run, now);
            // 종료 3경로 모두 run_ended 의 재료를 낸다(BR-037, §4.10).
            eventPublisher.publishEvent(new RunEndedEvent(run.getId(), run.getAcademyId(), now, autoAlightedCount));
            return new Outcome(autoAlightedCount, List.of());
        }
        long stillBoarded = runRiderRepository.countByRunIdAndStatus(run.getId(), RiderStatus.BOARDED);
        if (stillBoarded == 0) {
            run.finish(now);
            // 마지막 승하차지 강제 출발(Ruling 312) — 하원 최종 지점은 방금 도착한 하차지
            // 자신이라 위쪽 폴백을 받지 못한다.
            stopDepartureService.forceAllRemaining(run.getId(), run.getAcademyId(), now);
            eventPublisher.publishEvent(new RunEndedEvent(run.getId(), run.getAcademyId(), now, 0));
            return Outcome.NONE;
        }
        run.deferFinish();
        return new Outcome(null, remainingRidersOf(run.getId()));
    }

    /** 등원 최종 지점 도착 — 아직 탑승 중인 전원을 하차 처리하고 학생별 자동 하차 이벤트를 발행한다(C-07). */
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

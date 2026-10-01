package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.event.StopDepartedEvent;

/**
 * 정차지 출발 선점 + {@link StopDepartedEvent} 발행을 한 곳에 모은다(Ruling 308·312, docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3) —
 * {@link src.backend.location.proximity.ProximityNotificationService#judgeRun}(100m 이탈
 * 판정) · {@code RunArrivalCommandService#arrive}(다음 승하차지 도착 시 폴백) · 운행 종료 강제 적용
 * (Ruling 312) 세 호출부가 이 클래스 하나를 통해서만 {@code claimDeparture} 를 부른다 — 세 곳이
 * 각자 조건부 UPDATE 뒤 이벤트 발행을 반복하면 그중 한 곳이 발행을 빠뜨려도 드러나지 않는다.
 *
 * <p>{@code REQUIRES_NEW} 를 쓰지 않는다 — 호출자의 트랜잭션에 그대로 참여해, 이 선점 뒤 벌어지는
 * 나머지 처리가 실패하면 선점도 함께 롤백되어 다음 판정 기회로 미뤄진다
 * ({@code RunStopRepository#claimDeparture} 자바독과 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class StopDepartureService {

    private final RunStopRepository runStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 정차 항목 1건의 출발을 선점하고, 실제로 선점됐을 때만(1행 갱신) {@link StopDepartedEvent} 를
     * 발행한다 — 이미 다른 경로가 먼저 선점했으면(0행) 조용히 반환한다(멱등, Ruling 308).
     */
    @Transactional
    public void claimAndPublish(RunStop stop, Long runId, Long academyId, OffsetDateTime now) {
        int claimed = runStopRepository.claimDeparture(stop.getId(), now);
        if (claimed == 0) {
            return;
        }
        eventPublisher.publishEvent(new StopDepartedEvent(runId, academyId, stop.getStopId(), now));
    }

    /**
     * 그 회차에 도착했지만 아직 출발 처리되지 않은 정차지 전부를 강제로 출발 처리한다(Ruling 312) —
     * 다음 승하차지 도착 시 이전 정차지에 거는 폴백(목표 7), 운행 종료 시 마지막 정차지에 거는 강제
     * 적용(목표 8) 둘 다 이 메서드 하나로 처리한다. 대상이 없으면(정상적으로 이미 전부 출발
     * 처리됐으면) 아무 일도 하지 않는다.
     */
    @Transactional
    public void forceAllRemaining(Long runId, Long academyId, OffsetDateTime now) {
        Optional<ConfirmedRoute> confirmedRoute = confirmedRouteRepository.findById(runId);
        if (confirmedRoute.isEmpty() || confirmedRoute.get().getCurrentVersionId() == null) {
            return;
        }
        List<RunStop> remaining = runStopRepository
                .findAllArrivedNotDeparted(confirmedRoute.get().getCurrentVersionId());
        for (RunStop stop : remaining) {
            claimAndPublish(stop, runId, academyId, now);
        }
    }
}

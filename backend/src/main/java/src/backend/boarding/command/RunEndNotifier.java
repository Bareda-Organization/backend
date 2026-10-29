package src.backend.boarding.command;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.run.command.RunCompletionService;
import src.backend.run.command.StopDepartureService;
import src.backend.run.entity.Run;
import src.backend.run.event.RunEndedEvent;

/** 하원 자동 종료 경계 협력자 — 호출부 트랜잭션 안에서만 부른다. */
@Component
@RequiredArgsConstructor
class RunEndNotifier {

    /**
     * 하원 자동 종료 판정 협력자 — 이 클래스는 마지막 하차 뒤 호출만 하고 전이
     * 로직 자체는 구현하지 않는다. {@code alighted} 처리마다 무조건 호출해도 안전하다(내부에서
     * 하원·종료 대상이 아니면 no-op).
     */
    private final RunCompletionService runCompletionService;

    /**
     * 운행 종료 시 도착 여부와 무관하게 미출발로 남은 승하차지 전부를 강제로 출발 처리하는 협력자(Ruling 312) —
     * 마지막 승하차지는 다음 정차지가 없어 {@code RunArrivalCommandService} 의 폴백을
     * 못 받으므로, 이 시점에 한 번 더 쓸어낸다.
     */
    private final StopDepartureService stopDepartureService;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 하원 자동 종료 경계 — 방금 처리한 하차·미승차(BR-031)가 그 회차의 마지막 잔여 탑승자였는지를
     * {@link RunCompletionService} 에 위임해 묻고, 그 호출로 실제 종료됐을 때만
     * {@link RunEndedEvent} 를 발행한다. 반환값을 무시하고 매번(또는 전혀) 발행하면 알림이 잔류자가
     * 있는 회차에도 나가거나 정작 종료된 회차에서 나가지 않는 결함이 된다 — 이 분기 자체가 검사 대상인
     * 갈래다(전이 로직은 여기서 구현하지 않는다).
     */
    void notifyIfRunJustEnded(Run run, OffsetDateTime now) {
        boolean justEnded = runCompletionService.completeIfAllAlighted(run, now);
        if (justEnded) {
            // 마지막 승하차지 강제 출발(Ruling 312) — 이 시점의 최종 지점은 다음 정차지가
            // 없어 RunArrivalCommandService 의 폴백을 받지 못한다.
            stopDepartureService.forceAllRemaining(run.getId(), run.getAcademyId(), now);
            // auto_alighted_count 는 등원 종료 때 자동 하차된 수다. 이 경로는 하원의 마지막 수동 하차라 0 이다
            // (하원 종료는 어느 경로든 0 — RunArrivalCommandService 의 하원 최종 지점 도착과 같은 값).
            eventPublisher.publishEvent(new RunEndedEvent(run.getId(), run.getAcademyId(), now, 0));
        }
    }
}

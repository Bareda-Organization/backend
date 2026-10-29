package src.backend.boarding.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.boarding.entity.ActorType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RiderStatusHistory;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.entity.VerifyMethod;
import src.backend.boarding.event.RiderRecordedAfterDepartureEvent;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RiderStatusHistoryRepository;
import src.backend.global.security.AuthUser;
import src.backend.run.entity.Run;

/** PATCH 승하차 처리의 기록 단계 협력자 — 전이 적용 · 이력 저장 · 목표 상태별 후속 처리. 호출부 트랜잭션 안에서만 부른다. */
@Component
@RequiredArgsConstructor
class RiderStatusRecorder {

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    private final StopDepartureGuard stopDepartureGuard;

    private final NoShowStep noShowStep;

    private final RunEndNotifier runEndNotifier;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 전이를 적용하고 이력 · 출발 후 기록 이벤트를 남긴 뒤 목표 상태별 후속 처리로 넘긴다. */
    RiderStatusUpdateResponse record(AuthUser requester, Run run, RunRider rider,
            RiderStatusUpdateRequest request) {
        RiderStatus targetStatus = BoardingRules.parseTargetStatus(request.status());
        VerifyMethod verifyMethod = BoardingRules.parseVerifyMethod(request.verifyMethod());
        RiderStatus fromStatus = rider.getStatus();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 출발 판정과 이 기록이 같은 순간이어도 알림이 0건·2건이 되지 않도록 run_stop 잠금을 먼저 쥐고 출발 여부를 읽는다(Ruling 878).
        boolean stopDeparted = stopDepartureGuard.isStopDepartedLocked(run.getId(), rider.getStopId());
        BoardingRules.applyTransition(rider, run.getDirection(), fromStatus, targetStatus, now);
        RiderStatusHistory history = riderStatusHistoryRepository
                .save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(), fromStatus, targetStatus,
                        false, null, verifyMethod, request.clientKey(), request.occurredAt(), ActorType.ESCORT, now,
                        requester.accountId())));
        if (stopDeparted) {
            eventPublisher.publishEvent(new RiderRecordedAfterDepartureEvent(run.getId(), run.getAcademyId(),
                    run.getDirection(), rider.getStudentId(), rider.getId(), targetStatus, now));
        }
        return finishStatus(run, rider, targetStatus, history, now);
    }

    /** 목표 상태별 후속 처리 — 미승차는 케이스 생성, 하차는 종료 경계 확인, 그 밖은 상태 변경 이벤트 발행. */
    private RiderStatusUpdateResponse finishStatus(Run run, RunRider rider, RiderStatus targetStatus,
            RiderStatusHistory history, OffsetDateTime now) {
        if (targetStatus == RiderStatus.NO_SHOW) {
            RiderStatusUpdateResponse response = noShowStep.handleNoShow(run, rider, now);
            history.recordStopSkipped(response.stopSkipped());
            // 하원 보류 회차 — 마지막 탑승자가 미승차로 빠져도 boarded 0명이다(BR-031, C-15).
            runEndNotifier.notifyIfRunJustEnded(run, now);
            return response;
        }
        if (targetStatus == RiderStatus.ALIGHTED) {
            runEndNotifier.notifyIfRunJustEnded(run, now);
        }
        String statusName = BoardingRules.statusName(targetStatus);
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                rider.getStudentId(), rider.getId(), statusName, now, false));
        return RiderStatusUpdateResponse.of(rider.getId(), statusName, now, false);
    }
}

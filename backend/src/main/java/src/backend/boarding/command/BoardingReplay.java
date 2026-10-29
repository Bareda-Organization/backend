package src.backend.boarding.command;

import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RiderStatusHistory;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RiderStatusHistoryRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.command.NoShowCaseAccess;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 승하차 처리의 {@code client_key} 재전송 응답 재구성 협력자. 호출부 트랜잭션 안에서만 부른다. */
@Component
@RequiredArgsConstructor
class BoardingReplay {

    private final RunRiderRepository runRiderRepository;

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    private final NoShowCaseAccess noShowCaseAccess;

    /** 같은 {@code client_key} 재전송의 키 잠금 · 이전 처리 조회 절차(BR-226). */
    private final ClientKeyReplay clientKeyReplay;

    /** 키 잠금을 잡고 이전 처리가 있으면 그 결과를 재구성해 돌려준다 — 키가 없거나 이전 처리가 없으면 비어 있다. */
    Optional<RiderStatusUpdateResponse> replay(RiderStatusUpdateRequest request, Long runId, Long riderId) {
        return clientKeyReplay.replay(request.clientKey(),
                riderStatusHistoryRepository::findByClientKey,
                history -> replayResponse(history, runId, riderId, request.status()));
    }

    /**
     * 재생 대상이 이 요청과 같은 처리인지 대조한다(BR-078) — {@code findByClientKey} 는 학원 범위 밖 조회라
     * 대조 책임이 서비스에 있다({@code RiderStatusHistoryRepository} 예외 사유). 같은 회차의 같은 탑승자·같은
     * 상태가 아니면 키가 다른 처리에 재사용된 것이라, 남의 결과를 돌려주고 새 처리를 버리는 대신 422 로 거절한다.
     */
    private RunRider assertSameRequest(RiderStatusHistory history, Long runId, Long riderId, String status) {
        Optional<RunRider> rider = runRiderRepository.findByIdAndRunIdAndStatusNot(riderId, runId, RiderStatus.ABSENT);
        boolean sameRequest = history.getRunRiderId().equals(riderId)
                && history.getToStatus() == BoardingRules.parseTargetStatus(status) && rider.isPresent();
        if (!sameRequest) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return rider.get();
    }

    /**
     * 재전송 응답 재구성 — 새로 아무것도 만들지 않고 최초 처리 결과를 그대로 옮긴다.
     * {@code no_show_case} 는 최초 처리 때 만들어진 케이스를 다시 찾아 싣고, {@code stop_skipped} 는 이력 행에 남긴 최초 응답 값을 싣는다.
     */
    private RiderStatusUpdateResponse replayResponse(RiderStatusHistory history, Long runId, Long riderId,
            String status) {
        RunRider rider = assertSameRequest(history, runId, riderId, status);
        RiderStatus toStatus = history.getToStatus();
        String toName = BoardingRules.statusName(toStatus);
        if (toStatus != RiderStatus.NO_SHOW) {
            return RiderStatusUpdateResponse.of(rider.getId(), toName, history.getChangedAt(), false);
        }
        boolean stopSkipped = history.isStopSkipped();
        return noShowCaseAccess.find(rider.getId())
                .map(noShowCase -> RiderStatusUpdateResponse.withNoShowCase(rider.getId(), history.getChangedAt(),
                        new RiderStatusUpdateResponse.NoShowCaseSummary(noShowCase.caseId(), noShowCase.startedAt(),
                                noShowCase.expiresAt(), noShowCase.contacts()),
                        stopSkipped))
                .orElseGet(() -> RiderStatusUpdateResponse.of(rider.getId(), toName,
                        history.getChangedAt(), stopSkipped));
    }
}

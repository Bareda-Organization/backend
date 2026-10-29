package src.backend.exception.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.dto.EmergencyAckResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/** 비상 신고 확인의 처리 단계 — 호출하는 {@code EmergencyCommandService#ack} 의 트랜잭션 안에서 돈다. */
@Component
@RequiredArgsConstructor
class EmergencyAckStep {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final AccountRepository accountRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 신고 조회 → 조건부 확인 → 확인자 이름 조회 → 이벤트 발행 순으로 확인하고 결과를 돌려준다. */
    EmergencyAckResponse ack(AuthUser requester, Long emergencyId, String ackMemo) {
        EmergencyAlert alert = findAckableAlert(emergencyId, requester.academyId());

        OffsetDateTime now = OffsetDateTime.now(clock);
        int acked = emergencyAlertRepository.ackIfUnacked(alert.getId(), requester.accountId(), now,
                EmergencyRequestRules.normalizeMemo(ackMemo));
        if (acked == 0) {
            throw lostAckRace(emergencyId, requester.academyId());
        }

        String ackedByName = accountRepository.findById(requester.accountId()).map(Account::getName).orElse(null);
        eventPublisher.publishEvent(
                new EmergencyAckedEvent(alert.getId(), alert.getAcademyId(), alert.getRunId(), ackedByName, now));
        return new EmergencyAckResponse(alert.getId(), now);
    }

    /** 호출자 학원의 신고를 찾는다 — 없으면 {@code EMERGENCY_NOT_FOUND}, 취소됐으면 {@code EMERGENCY_CANCELED}, 이미 확인됐으면 {@code ALREADY_ACKED}. */
    private EmergencyAlert findAckableAlert(Long emergencyId, Long academyId) {
        EmergencyAlert alert = emergencyAlertRepository.findByIdAndAcademyId(emergencyId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_NOT_FOUND));
        if (alert.isCanceled()) {
            throw new BusinessException(ErrorCode.EMERGENCY_CANCELED);
        }
        if (alert.isAcked()) {
            throw new BusinessException(ErrorCode.ALREADY_ACKED);
        }
        return alert;
    }

    /**
     * 읽은 뒤 동시에 먼저 처리한 쪽이 있다 — 취소가 먼저면 EMERGENCY_CANCELED, 확인이 먼저면 ALREADY_ACKED(BR-079 · Ruling 885 ①).
     * 조건부 UPDATE 가 영속성 컨텍스트를 비웠으므로 다시 읽으면 커밋된 최신 행이다.
     */
    private BusinessException lostAckRace(Long emergencyId, Long academyId) {
        boolean canceledFirst = emergencyAlertRepository.findByIdAndAcademyId(emergencyId, academyId)
                .map(EmergencyAlert::isCanceled).orElse(false);
        return new BusinessException(canceledFirst ? ErrorCode.EMERGENCY_CANCELED : ErrorCode.ALREADY_ACKED);
    }
}

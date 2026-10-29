package src.backend.exception.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.exception.dto.EmergencyCancelResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;

/** 비상 신고 취소의 처리 단계 — 호출하는 {@code EmergencyCommandService#cancel} 의 트랜잭션 안에서 돈다. */
@Component
@RequiredArgsConstructor
class EmergencyCancelStep {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 배치 확인 → 신고 조회 → 취소 창 확인 → 조건부 취소 → 이벤트 발행 순으로 취소하고 결과를 돌려준다. */
    EmergencyCancelResponse cancel(AuthUser requester, Long runId, Long emergencyId) {
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);
        EmergencyAlert alert = findAlertInRun(emergencyId, runId, requester.academyId());
        if (alert.isCanceled()) {
            return new EmergencyCancelResponse(alert.getId(), alert.getCanceledAt());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        assertWithinCancelWindow(alert.getReceivedAt(), now);

        // 엔티티를 바꿔 전체 컬럼 UPDATE 로 쓰지 않는다 — 읽은 뒤 먼저 커밋된 확인 기록(acked_*)을 null 로 되돌린다
        if (emergencyAlertRepository.cancelIfOpen(alert.getId(), now) == 0) {
            return canceledByConcurrentRequest(emergencyId, runId, requester.academyId());
        }

        eventPublisher.publishEvent(
                new EmergencyCanceledEvent(alert.getId(), requester.academyId(), runId, alert.getBusNo(), now));
        return new EmergencyCancelResponse(alert.getId(), now);
    }

    /** 호출자 학원의 해당 회차 신고를 찾는다 — 없거나 다른 회차 것이면 {@code EMERGENCY_NOT_FOUND}. */
    private EmergencyAlert findAlertInRun(Long emergencyId, Long runId, Long academyId) {
        return emergencyAlertRepository.findByIdAndRunIdAndAcademyId(emergencyId, runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMERGENCY_NOT_FOUND));
    }

    /**
     * 읽은 뒤 동시에 먼저 취소한 쪽이 있다 — 최초 취소 결과를 그대로 돌려준다(재취소와 같다). 조건부 UPDATE 가 영속성 컨텍스트를 비웠으므로
     * 다시 읽으면 최신 행이다.
     */
    private EmergencyCancelResponse canceledByConcurrentRequest(Long emergencyId, Long runId, Long academyId) {
        EmergencyAlert canceled = findAlertInRun(emergencyId, runId, academyId);
        return new EmergencyCancelResponse(canceled.getId(), canceled.getCanceledAt());
    }

    /**
     * "발신 후 1분 이내" 의 기준 시각은 {@code occurredAt}(클라이언트가 신고했다고 주장하는 시각,
     * 조작 가능)이 아니라 {@code receivedAt}(서버가 실제로 접수한 시각)이다 — 취소 창을 신뢰할 수
     * 없는 클라이언트 시계에 맡기지 않기 위함이다.
     *
     * <p>경계는 <b>포함</b>이다 — 정확히 60.000초에 취소 요청이 오면 성공으로 본다("1분 이내"를 닫힌
     * 구간으로 읽는 판단, 보고서 항목). {@code compareTo(...) > 0} 만 창 닫힘으로 판정해, 정확히
     * 같은 값은 아직 열린 것으로 남긴다. 기준 창은 {@link EmergencyAlert#CANCEL_WINDOW} 하나를
     * §4.15 {@code cancelable_until} 계산과 공유한다 — 두 곳이 다른 규칙으로 계산되게 두지 않는다.
     */
    private void assertWithinCancelWindow(OffsetDateTime receivedAt, OffsetDateTime now) {
        if (Duration.between(receivedAt, now).compareTo(EmergencyAlert.CANCEL_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.EMERGENCY_CANCEL_WINDOW_CLOSED);
        }
    }
}

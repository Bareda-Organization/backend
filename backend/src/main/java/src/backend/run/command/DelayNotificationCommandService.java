package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.policy.PolicyConstants;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.dto.DelayRequest;
import src.backend.run.dto.DelayResponse;
import src.backend.run.entity.DelayNotice;
import src.backend.run.entity.DelayReason;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.DelayRequestedEvent;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.run.repository.RunRepository;

/**
 * 지연 알림 신고(NTF-06, M-05, API_SPEC §4.9).
 *
 * <p>수신자 조회·발신은 알림 모듈의 {@code DelayNotificationListener} 로 넘긴다(ARCHITECTURE §3.3 규칙 17 · BRD-04). 이 서비스가 그 모듈을 직접 부르면 승인
 * 트랜잭션 안에서 알림 적재가 돌아, 그 적재가 실패했을 때 지연 신고 자체가 롤백된다 — 그래서
 * {@link ApplicationEventPublisher} 로 {@link DelayRequestedEvent} 만 발행한다.
 *
 * <p>응답의 세 불리언({@code notifiedGuardians} 등)은 <b>리스너 실행 결과에 기대지 않는다</b> — 이
 * 서비스가 이벤트에 실어 보내는 수신자 3집합을 그대로 계산해 그 집합의 비어 있음 여부로 채운다(이벤트
 * 발행은 리스너가 실제로 몇 건을 적재했는지 동기적으로 돌려주지 않는다). 그래서 관계자·학부모·학생
 * 조회({@link DelayRecipientResolver})는 이 모듈이 맡고, 리스너는 그 결과를 그대로 받아 적재만 한다.
 *
 * <p>{@code minutes}·{@code reason} 을 여기서 검증하는 이유는 {@link DelayRequest} 자바독과 같다 —
 * 값 도메인 위반(5의 배수가 아님·정의되지 않은 사유 문자열)은 400 이 아니라 422 여야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DelayNotificationCommandService {

    private final RunRepository runRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final DelayRecipientResolver recipientResolver;

    private final RunAssignmentAccess runAssignmentAccess;

    private final RunRowLock runRowLock;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 매니저가 배치된 회차인지 확인하고 {@code minutes}·{@code reason} 값 도메인을 검증한 뒤 지연을
     * 기록한다(§4.6, NTF-06).
     */
    public DelayResponse notifyDelay(AuthUser requester, Long runId, DelayRequest request) {
        runAssignmentAccess.assertAssignedEscort(requester, runId);
        Run run = lockedMovingRun(runId, requester.academyId());
        int minutes = validateMinutes(request.minutes());
        DelayReason reason = parseReason(request.reason());
        String message = request.message();
        rejectDuplicate(runId, minutes, reason, message);
        OffsetDateTime now = OffsetDateTime.now(clock);
        DelayRecipientResolver.Recipients recipients = recipientResolver.resolve(run);
        eventPublisher.publishEvent(new DelayRequestedEvent(runId, run.getAcademyId(), minutes, reason, message, now,
                recipients.staff(), recipients.guardians(), recipients.students()));
        delayNoticeRepository.save(DelayNotice.onSend(runId, requester.accountId(), minutes, reason, message, now));
        return new DelayResponse(!recipients.guardians().isEmpty(), !recipients.students().isEmpty(),
                !recipients.staff().isEmpty());
    }

    /**
     * 회차를 읽어 행 잠금을 걸고 상태를 다시 읽은 뒤 운행 중인지 판정한다 — 같은 내용의 동시 신고가 둘 다 중복 검사를 지나거나 운행이 막 끝난 회차에 신고가 통과하지 않게 한다.
     */
    private Run lockedMovingRun(Long runId, Long academyId) {
        Run run = runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        runRowLock.acquire(run);
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }
        return run;
    }

    /** 직전 발신과 분 · 사유 · 메시지가 전부 같으면 중복이다. */
    private void rejectDuplicate(Long runId, int minutes, DelayReason reason, String message) {
        delayNoticeRepository.findFirstByRunIdOrderBySentAtDesc(runId)
                .filter(previous -> previous.isSameContent(minutes, reason, message))
                .ifPresent(previous -> {
                    throw new BusinessException(ErrorCode.DELAY_DUPLICATE);
                });
    }

    private int validateMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0 || minutes % PolicyConstants.DELAY_UNIT_MINUTES != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return minutes;
    }

    private DelayReason parseReason(String reason) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "traffic" -> DelayReason.TRAFFIC;
            case "weather" -> DelayReason.WEATHER;
            case "vehicle_check" -> DelayReason.VEHICLE_CHECK;
            case "prev_stop_wait" -> DelayReason.PREV_STOP_WAIT;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
}

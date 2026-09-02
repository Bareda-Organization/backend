package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.notification.domain.NotificationRetryPolicy;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.notification.repository.NotificationSettingRepository;

/**
 * 적재된 아웃박스 행 하나를 실제로 발송하고 결과를 행에 옮긴다 — 즉시 발송(커밋 직후)과 워커 재시도가
 * <b>같은 절차</b>를 쓴다(TECH_DECISIONS §7.2).
 *
 * <p>두 경로가 같은 코드를 쓰는 것이 중요하다. 나누면 상한·사유 기록·상태 전이가 두 벌이 되고, 한쪽만
 * 고쳤을 때 <b>양쪽 테스트가 계속 통과한다</b>.
 *
 * <p>이 클래스에 트랜잭션 애너테이션이 부재한 것은 의도다 — 발송은 트랜잭션 밖이어야 하고
 * (§7.5), 상태 전이만 저장소 메서드가 각자 {@code REQUIRES_NEW} 로 연다.
 *
 * <p>알림 설정 on/off 판정(Phase 12 목표 8, API_SPEC §3.14)도 이 클래스 한 곳에서만 한다 — 16개
 * 리스너 각자가 발송 여부를 판단하게 하지 않는다. 아웃박스 행 생성({@code NotificationOutbox})은
 * 이 판정과 무관하게 항상 일어나므로 "설정이 꺼져도 기록은 남는다" 는 그쪽을 전혀 건드리지 않고도
 * 이미 성립한다 — 이 클래스가 손대는 것은 <b>푸시 채널</b> 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationLogRepository notificationLogRepository;

    private final NotificationSettingRepository notificationSettingRepository;

    private final NotificationRetryPolicy retryPolicy;

    private final PushSender pushSender;

    private final Clock clock;

    /** 발송 실패 사유를 {@code fail_reason}(varchar 200) 에 담을 때의 상한. */
    private static final int FAIL_REASON_MAX_LENGTH = 200;

    /**
     * 한 건을 발송한다. 발송 실패는 예외로 새어 나가지 않고 행에 기록된다 — 즉시 발송 경로가
     * {@code AFTER_COMMIT} 리스너라, 예외를 던지면 <b>이미 커밋된</b> 상태 변경의 응답이 뒤집힌다.
     *
     * <p><b>선점에 실패하면 아무것도 하지 않는다.</b> 즉시 발송과 워커가 같은 행을 겨냥하는 것은
     * 정상이며(하나는 빠르라고, 하나는 잃지 말라고 있다), 둘 다 보내는 것이 사고다.
     *
     * <p>설정이 꺼져 있으면 {@link #claim} 을 거치지 않고 바로 {@code skipped} 로 옮긴다 — 발송
     * 시도 자체가 없으니 {@code push_attempts} 를 올릴 이유가 없다({@code markSkipped} 자바독).
     */
    public void dispatch(Long notificationId) {
        NotificationLog target = notificationLogRepository.findById(notificationId).orElse(null);
        if (target == null) {
            return;
        }
        if (!isPushEnabled(target)) {
            notificationLogRepository.markSkipped(notificationId, PushState.PENDING, PushState.SKIPPED);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (notificationLogRepository.claim(notificationId, PushState.PENDING,
                retryPolicy.attemptedBefore(now), now) == 0) {
            return;
        }
        send(target, target.getPushAttempts() + 1, now);
    }

    /**
     * 이 알림을 받을 계정의 설정이 이 종류를 허용하는지 본다.
     *
     * <p>설정 행이 없으면(스태프 등 애초에 설정 대상이 아닌 역할, 또는 아직 자가 치유가 일어나지
     * 않은 학부모·학생 계정) 켜진 것으로 본다 — {@code notification_setting} DDL 의
     * {@code DEFAULT true} 와 같은 기본값이고, 행 부재를 off 로 읽으면 지금 이 테이블에 행이 하나도
     * 없는 상태에서 <b>기존 알림이 전부 죽는다</b>.
     */
    private boolean isPushEnabled(NotificationLog target) {
        return notificationSettingRepository.findById(target.getRecipientAccountId())
                .map(setting -> setting.isEnabledFor(target.getType()))
                .orElse(true);
    }

    /**
     * 발송하고 결과를 옮긴다 — 성공은 {@code sent}, 실패는 사유를 남긴 채 상한에 따라 갈린다.
     *
     * @param attempts 이번 시도까지 포함한 횟수. 읽어 둔 엔티티는 시도 기록 <b>이전</b>의 값이라
     *                 그대로 쓰면 상한 판정이 한 회차씩 늦어져 실제로는 4회를 시도한다
     */
    private void send(NotificationLog target, int attempts, OffsetDateTime now) {
        try {
            pushSender.send(PushMessage.from(target));
            notificationLogRepository.markSent(target.getId(), PushState.PENDING, PushState.SENT, now);
        } catch (RuntimeException e) {
            PushState nextState = retryPolicy.exhausted(attempts) ? PushState.FAILED : PushState.PENDING;
            notificationLogRepository.markAttemptFailed(target.getId(), PushState.PENDING, nextState,
                    failReason(e));
        }
    }

    /** 사유는 컬럼 길이를 넘지 않게 자른다 — 자르지 않으면 실패 기록 자체가 제약 위반으로 또 실패한다. */
    private String failReason(RuntimeException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        return reason.length() <= FAIL_REASON_MAX_LENGTH
                ? reason
                : reason.substring(0, FAIL_REASON_MAX_LENGTH);
    }
}

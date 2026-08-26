package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.event.SignupDecidedEvent;
import src.backend.notification.domain.SignupDecidedMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 가입 결정을 {@code signup_decided} 알림으로 옮기는 구독자(API_SPEC §9.7 — 수신자는 신청자 본인).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 <b>평범한</b> {@code @EventListener} 인 것이
 * 핵심이다. 발행 시점의 트랜잭션 안에서 그대로 실행돼야 적재가 상태 변경과 원자적이 된다
 * (TECH_DECISIONS §7.2) — 커밋 이후로 미루면 그 사이에 앱이 죽었을 때 "보내야 했다" 는 사실 자체가
 * 남지 않는다. 발송을 커밋 밖으로 미는 것은 {@link NotificationDispatchListener} 의 일이다.
 */
@Component
@RequiredArgsConstructor
public class SignupDecidedNotificationListener {

    /**
     * {@code dedup_key} 형태 — ERD 의 {@code {event}:{run_id}:{대상}:{판정 시각}} 을 그대로 따른다.
     *
     * <p>회차가 부재한 알림이라 두 번째 자리는 {@code na} 다(시드의 {@code signup_decided:na:8:seed}
     * 와 같은 형태). 네 번째 자리를 <b>요청 식별자가 아니라 판정 시각</b>으로 두는 이유는 재신청
     * (AUTH-03) 때문이다 — 같은 계정이 거절·재신청·재거절을 밟으면 두 통지는 서로 다른 알림인데,
     * 계정만으로 키를 만들면 두 번째가 조용히 차단된다.
     */
    private static final String DEDUP_KEY_FORMAT = "signup_decided:na:%d:%s";

    private final NotificationOutbox notificationOutbox;

    /** 신청자 본인 앞으로 발송 대기 행을 적재한다 — 수락·거절 <b>양쪽</b>이 대상이다(§9.7). */
    @EventListener
    public void appendSignupDecided(SignupDecidedEvent event) {
        SignupDecidedMessage message = SignupDecidedMessage.of(event.accepted(), event.rejectReason());
        notificationOutbox.append(new NotificationDraft(event.academyId(), event.accountId(),
                event.accountName(), event.accountRole(), NotificationType.SIGNUP_DECIDED,
                message.title(), message.body(),
                DEDUP_KEY_FORMAT.formatted(event.accountId(), event.decidedAt())));
    }
}

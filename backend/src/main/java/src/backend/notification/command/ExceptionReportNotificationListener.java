package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.global.common.enums.Role;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;
import src.backend.notification.entity.NotificationType;

/**
 * 현장 예외 보고 접수(EXC-02·03, API_SPEC §4.13)를 {@code exception_reported} 알림으로 옮기는
 * 구독자 — 수신자는 §4.13 이 명시한 "관계자"(학원 재직 관계자 전원, {@link IntentNotificationListener}
 * 와 같은 대상 규칙이라 조회도 같은 {@link AcademyStaffRepository#findActiveAccountsByAcademyId}
 * 를 쓴다).
 *
 * <p>{@code @TransactionalEventListener} 가 아니라 평범한 {@code @EventListener} 인 이유는
 * {@link IntentNotificationListener} 와 같다 — {@code ExceptionReportCommandService} 의 열린
 * 트랜잭션 안에서 발행되므로, 이 리스너도 그 트랜잭션이 아직 열려 있는 동안 실행돼 "즉시 통지"
 * (§4.13)가 보고 저장과 같은 커밋 경계에 든다.
 */
@Component
@RequiredArgsConstructor
public class ExceptionReportNotificationListener {

    private static final String DEDUP_KEY_FORMAT = "exception_reported:%d:%d:%s";

    private final AcademyStaffRepository academyStaffRepository;

    private final NotificationOutbox notificationOutbox;

    private final NotificationComposer<ExceptionReportedEvent> exceptionReportedComposer;

    /** 그 학원 재직 관계자 전원에게 적재한다 — 관계자가 없으면(드묾) 아무 것도 하지 않는다. */
    @EventListener
    public void appendExceptionReported(ExceptionReportedEvent event) {
        List<AcademyStaffAccountView> staff = academyStaffRepository
                .findActiveAccountsByAcademyId(event.academyId());
        if (staff.isEmpty()) {
            return;
        }

        NotificationMessage message = exceptionReportedComposer.compose(event);
        for (AcademyStaffAccountView recipient : staff) {
            notificationOutbox.append(new NotificationDraft(event.academyId(), recipient.accountId(),
                    recipient.name(), Role.STAFF, NotificationType.EXCEPTION_REPORTED, message.title(),
                    message.body(),
                    DEDUP_KEY_FORMAT.formatted(event.reportId(), recipient.accountId(), event.reportedAt())));
        }
    }
}

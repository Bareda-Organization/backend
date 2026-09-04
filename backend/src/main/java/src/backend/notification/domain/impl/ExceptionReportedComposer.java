package src.backend.notification.domain.impl;

import org.springframework.stereotype.Component;

import src.backend.exception.event.ExceptionReportedEvent;
import src.backend.notification.domain.spec.NotificationComposer;
import src.backend.notification.domain.spec.NotificationMessage;

/**
 * 현장 예외 보고 접수 알림의 문구(API_SPEC §9.7 {@code exception_reported}) — 수신자는 §4.13 이
 * 명시한 "관계자"(학원 재직 관계자 전원)다.
 *
 * <p>본문에 보고 유형·학생 정보를 싣지 않는다 — {@link ApprovalRequestedComposer} 와 같은 근거
 * (FEATURE_SPEC §6.3, 개인정보는 인증된 화면에서만). 관계자는 이 알림을 보고 예외 보고 목록
 * 화면(API_SPEC §5.20)을 열어 내용을 확인한다.
 */
@Component
public class ExceptionReportedComposer implements NotificationComposer<ExceptionReportedEvent> {

    private static final String TITLE = "현장 예외 보고 접수";

    private static final String BODY = "현장에서 예외 상황이 보고됐습니다. 목록에서 확인해 주세요.";

    @Override
    public NotificationMessage compose(ExceptionReportedEvent subject) {
        return new NotificationMessage(TITLE, BODY);
    }
}

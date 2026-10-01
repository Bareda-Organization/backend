package src.backend.global.sms.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import src.backend.global.sms.spec.SmsSender;

/**
 * 로그로 발송을 대신하는 개발용 구현(Ruling 512) — {@code app.sms.sender=logging} 일 때만 뜬다.
 *
 * <p><b>번호는 끝 4자리만, 본문은 길이만 남긴다.</b> 본문에는 인증 코드·임시 비밀번호가 실리므로 원문을 찍으면 로그를
 * 읽을 수 있는 누구나 계정을 가져간다. 그래서 이 구현으로는 코드를 눈으로 볼 수 없다 — 활성 경로는 시험의 가짜 발송기로
 * 확인하고, 실제 문자는 업체 구현체를 더한 배포 때 연결한다.
 *
 * <p>{@code PushSender} 의 로그 구현과 달리 값이 없으면 뜨지 않는다 — 문자가 아무에게도 가지 않는데 복구가 열려 있는
 * 상태(발송 없는 인증)를 기본값으로 만들지 않기 위해서다. prod 에서 이 구현이 뜨면 기동을 실패시킨다.
 */
@Component
@ConditionalOnProperty(name = "app.sms.sender", havingValue = "logging")
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    private static final int VISIBLE_PHONE_TAIL = 4;

    public LoggingSmsSender(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("prod 에서 문자 발송이 로그 전용이다 — app.sms.sender 를 업체 구현으로 바꿔라");
        }
    }

    @Override
    public void send(String phone, String text) {
        log.info("[sms] to=***{} length={}", tail(phone), text.length());
    }

    private static String tail(String phone) {
        return phone.length() <= VISIBLE_PHONE_TAIL ? "" : phone.substring(phone.length() - VISIBLE_PHONE_TAIL);
    }
}

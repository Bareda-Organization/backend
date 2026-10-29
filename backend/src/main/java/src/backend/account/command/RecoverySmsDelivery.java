package src.backend.account.command;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;

import src.backend.global.sms.spec.SmsSender;

/** 전화번호 복구 문자를 서킷 {@code sms} 로 감싸 보낸다 — 트랜잭션은 걸지 않는다. */
@Component
@RequiredArgsConstructor
class RecoverySmsDelivery {

    /** {@code resilience4j.circuitbreaker.instances} 의 이름 — 값은 FCM 과 같다(Ruling 899 F). */
    private static final String SMS_CIRCUIT = "sms";

    private final CircuitBreakerRegistry circuitBreakers;

    /** 문자 발송기를 서킷 {@code sms} 로 감싸 부른다 — 열려 있으면 업체를 부르지 않고 즉시 예외다. */
    void deliver(SmsSender sender, String phone, String text) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(SMS_CIRCUIT);
        circuitBreaker.executeRunnable(() -> sender.send(phone, text));
    }
}

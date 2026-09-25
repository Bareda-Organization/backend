package src.backend.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import src.backend.notification.push.impl.LoggingPushSender;

/**
 * BR-040 · Ruling 331 — prod 에서 발송 구현체가 로그 전용이면 기동이 실패해야 한다. 조용히 미발송으로 운영되는
 * 상태(승하차 알림이 단말에 하나도 안 가는데 로그에는 "발송" 으로 남음)를 막는다. 로컬·데모는 그대로 뜬다.
 */
class LoggingPushSenderGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingPushSender.class);

    @Test
    void prod_에서_로그_전용_발송이면_기동이_실패한다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 로컬에서는_로그_전용_발송으로_뜬다() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LoggingPushSender.class));
    }
}

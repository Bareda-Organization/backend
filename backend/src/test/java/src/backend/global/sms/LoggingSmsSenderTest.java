package src.backend.global.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import src.backend.global.sms.impl.LoggingSmsSender;
import src.backend.global.sms.spec.SmsSender;

/**
 * Ruling 512 — 로그 전용 문자 발송기는 번호·본문 원문을 로그에 남기지 않고, 값이 없으면 뜨지 않으며, prod 에서는 기동을
 * 막는다. 본문에는 인증 코드·임시 비밀번호가 실리므로 원문이 로그에 있으면 로그를 읽는 누구나 계정을 가져간다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingSmsSenderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingSmsSender.class);

    @Test
    void 번호는_끝_4자리만_본문은_길이만_남긴다(CapturedOutput output) {
        SmsSender sender = new LoggingSmsSender(new MockEnvironment());

        sender.send("010-1234-5678", "[바래다] 인증번호 987654 (5분 안에 입력)");

        assertThat(output.getAll()).contains("***5678").doesNotContain("010-1234").doesNotContain("987654")
                .doesNotContain("인증번호");
    }

    @Test
    void 설정_값이_없으면_어떤_발송기도_뜨지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SmsSender.class));
    }

    @Test
    void 값이_logging_이면_뜨고_prod_에서는_기동이_실패한다() {
        runner.withPropertyValues("app.sms.sender=logging")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LoggingSmsSender.class));
        runner.withPropertyValues("app.sms.sender=logging")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasFailed());
    }
}

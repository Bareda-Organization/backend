package src.backend.global.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** 같은 곳의 반복 실패는 한 간격에 한 번만 스택과 함께 남기고, 다음에 남길 때 생략 건수를 알린다(R46 S-3). */
class RateLimitedWarnTest {

    private final AtomicLong nanos = new AtomicLong();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Test
    @DisplayName("간격 안의 반복은 건수만 세고, 간격이 지난 첫 실패에 생략 건수가 붙는다")
    void 간격_안의_반복은_세고_다음_간격에_알린다() {
        Logger logger = (Logger) LoggerFactory.getLogger("rate-limited-warn-test");
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitedWarn warn = new RateLimitedWarn(logger, Duration.ofMinutes(1), nanos::get);

            for (int i = 0; i < 100; i++) {
                nanos.addAndGet(Duration.ofMillis(100).toNanos());
                warn.warn(new IllegalStateException("redis down"), "실패 id={}", i);
            }
            assertThat(appender.list).as("10초 동안 100번 실패해도 한 건").hasSize(1);
            assertThat(appender.list.get(0).getThrowableProxy()).as("첫 건은 스택이 붙는다").isNotNull();
            assertThat(appender.list.get(0).getFormattedMessage()).contains("id=0");

            nanos.addAndGet(Duration.ofMinutes(1).toNanos());
            warn.warn(new IllegalStateException("redis down"), "실패 id={}", 100);

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(1).getFormattedMessage()).as("그동안 생략한 99건이 알려진다")
                    .contains("id=100").contains("99건");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

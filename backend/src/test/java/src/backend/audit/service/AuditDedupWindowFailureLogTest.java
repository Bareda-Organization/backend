package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 감사 묶기 기록의 Redis 실패가 반복돼도(판정 · 되돌리기 각각) 경고 로그는 한 간격에 한 건이다. */
class AuditDedupWindowFailureLogTest {

    private static final int FAILURES = 5;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final AuditDedupWindow window = new AuditDedupWindow(Clock.systemUTC(), redis);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger("src.backend.audit.service.AuditRecorder");

    @BeforeEach
    void 준비한다() {
        given(redis.opsForValue()).willReturn(values);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void 정리한다() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("판정이 Redis 에서 반복 실패해도 경고는 한 건이다")
    void 판정_실패는_경고_한_건() {
        given(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new RedisConnectionFailureException("끊김"));

        for (int i = 0; i < FAILURES; i++) {
            window.claimNewStudents(1L, Map.of("student_ids", List.of(String.valueOf(i)), "fields", List.of("note")),
                    Instant.parse("2026-11-18T01:00:00Z"), new ArrayList<>());
        }

        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("되돌리기가 Redis 에서 반복 실패해도 경고는 한 건이다")
    void 되돌리기_실패는_경고_한_건() {
        given(redis.delete(anyString())).willThrow(new RedisConnectionFailureException("끊김"));

        for (int i = 0; i < FAILURES; i++) {
            window.releaseClaim(new AuditDedupWindow.ViewedStudent(1L, String.valueOf(i), "[note]"));
        }

        assertThat(warnings()).isEqualTo(1);
    }

    private long warnings() {
        return appender.list.stream().filter(event -> event.getLevel().levelStr.equals("WARN")).count();
    }
}

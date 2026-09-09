package src.backend.global.config;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 — BaseTimeEntity 의 createdAt/updatedAt 을 주입된 Clock 기준으로 기록한다.
 * {@code dateTimeProviderRef} 를 지정하지 않으면 기본 제공자가 시스템 시계를 직접 읽어
 * {@link ClockConfig} 의 Clock 이 무시된다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /** 주입된 Clock 을 경유해 감사 시각을 OffsetDateTime 으로 공급한다. */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(OffsetDateTime.now(clock));
    }
}

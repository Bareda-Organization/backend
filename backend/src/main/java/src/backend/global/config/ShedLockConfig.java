package src.backend.global.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 스케줄 분산 락(TECH_DECISIONS §3.2, Phase 11) — 인스턴스가 2대 이상일 때 같은 {@code @Scheduled}
 * 배치가 동시에 판정을 수행하는 것을 막는다. 어떤 스케줄러가 이 락을 실제로 쓰는지는 각 스케줄러
 * 파일의 {@code @SchedulerLock} 을 본다 — 확정 배치({@code RunConfirmationScheduler})는 조건부
 * UPDATE 로 이미 안전해 락을 붙이지 않는다.
 *
 * <p>{@code usingDbTime()} 을 쓰는 이유 — 인스턴스마다 시스템 시계가 미세하게 어긋나면 락 만료
 * 판정이 인스턴스별로 달라질 수 있다. DB 시각 하나를 공통 기준으로 삼아 이 문제를 없앤다.
 *
 * <p>{@code defaultLockAtMostFor} 는 각 스케줄러가 {@code lockAtMostFor} 를 명시하지 않았을 때만
 * 쓰이는 안전망이다 — 이 Phase 의 4개 스케줄러는 전부 명시하므로 실질적으로는 미래에 락 지정 없이
 * 새 스케줄러가 추가되는 실수를 막는 방어값이다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}

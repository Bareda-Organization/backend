package src.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 — BaseTimeEntity 의 createdAt/updatedAt 자동 기록을 켠다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}

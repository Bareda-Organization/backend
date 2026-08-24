package testsupport.timeaudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import src.backend.BackendApplication;
import src.backend.global.config.JpaAuditingConfig;

/**
 * BaseTimeEntity 의 감사 시각이 고정 Clock 을 경유하고 오프셋을 보존하는지, 실제 Postgres 로 검증한다.
 *
 * <p>Flyway 는 끄고 Hibernate {@code ddl-auto=create-drop} 으로 테스트 전용 엔티티만 매핑한다 —
 * 도메인 스키마와 무관하게 BaseTimeEntity 의 감사 동작만 확인하기 위함이다.
 *
 * <p>패키지를 {@code src.backend} 트리 밖({@code testsupport}) 에 둔 이유 — {@code BackendApplication}
 * 의 기본 엔티티 스캔은 자신의 base package({@code src.backend}) 하위를 전부 훑는다. 이 테스트 전용
 * 엔티티가 그 아래 있으면 다른 {@code @SpringBootTest}(전체 컨텍스트) 가 이 엔티티까지 함께 로드하고,
 * 실제 로컬 Postgres 에 없는 테이블이라 {@code ddl-auto=validate} 가 실패한다 — 다른 테스트의 회귀다.
 * 대신 {@code @ContextConfiguration} 으로 부트스트랩 기준 클래스를 명시하고, {@code @EntityScan}·
 * {@code @EnableJpaRepositories} 로 이 패키지만 별도로 스캔 대상에 넣는다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = AuditingProbeEntity.class)
@EnableJpaRepositories(basePackageClasses = AuditingProbeEntityRepository.class)
@Testcontainers
@Import({JpaAuditingConfig.class, BaseTimeEntityAuditingTest.FixedClockConfig.class})
class BaseTimeEntityAuditingTest {

    /** 판정 시각 — 시스템 시각과 반드시 달라야 "Clock 을 실제로 경유했다"는 것을 증명할 수 있다. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T07:35:00Z");
    /** 수정 시점 판정 시각 — createdAt 과 달라야 "값이 실제로 보존된 것"과 "고정값이라 우연히 같은 것"을 구분할 수 있다. */
    private static final Instant SECOND_FIXED_INSTANT = Instant.parse("2026-08-24T09:10:00Z");
    private static final ZoneOffset SEOUL_OFFSET = ZoneOffset.ofHours(9);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private AuditingProbeEntityRepository repository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    /** MutableClock 은 컨텍스트에 하나뿐인 공유 빈이라, 이전 테스트가 넘긴 시각이 다음 테스트로 새어 들어간다 —
     *  각 테스트 전에 최초 고정 시각으로 되돌려 실행 순서와 무관하게 만든다. */
    @BeforeEach
    void 공유_Clock_을_최초_고정_시각으로_되돌린다() {
        clock.advanceTo(FIXED_INSTANT);
    }

    @Test
    void 고정된_Clock_을_주입하면_엔티티의_createdAt_이_그_시각으로_채워진다() {
        AuditingProbeEntity saved = repository.saveAndFlush(AuditingProbeEntity.of("A"));

        OffsetDateTime createdAt = saved.getCreatedAt();

        assertThat(createdAt.toInstant())
                .as("고정 Clock(%s) 값과 같아야 한다 — 다르면 시스템 시계를 그대로 읽은 것", FIXED_INSTANT)
                .isEqualTo(FIXED_INSTANT);
        assertThat(createdAt.toInstant())
                .as("고정 시각은 실제 시스템 시각과 달라야 이 단언이 Clock 경유를 증명한다")
                .isNotEqualTo(Instant.now());
    }

    @Test
    void 고정된_Clock_을_주입하면_수정_시_updatedAt_만_갱신되고_createdAt_은_유지된다() {
        AuditingProbeEntity saved = repository.saveAndFlush(AuditingProbeEntity.of("A"));
        OffsetDateTime originalCreatedAt = saved.getCreatedAt();

        clock.advanceTo(SECOND_FIXED_INSTANT);
        saved.changeLabel("B");
        AuditingProbeEntity updated = repository.saveAndFlush(saved);

        assertThat(updated.getCreatedAt().toInstant())
                .as("수정해도 createdAt 은 최초 생성 시각(%s)을 유지해야 한다 — 갱신 시각(%s)으로 바뀌면 안 된다",
                        FIXED_INSTANT, SECOND_FIXED_INSTANT)
                .isEqualTo(FIXED_INSTANT);
        assertThat(updated.getUpdatedAt().toInstant())
                .as("updatedAt 은 수정 시점의 두 번째 고정 Clock 값(%s)이어야 한다 — createdAt 과 같으면 시계가 안 바뀐 것",
                        SECOND_FIXED_INSTANT)
                .isEqualTo(SECOND_FIXED_INSTANT);
    }

    @Test
    void 감사_시각은_오프셋을_보존한다() throws Exception {
        repository.saveAndFlush(AuditingProbeEntity.of("A"));

        String columnType = createdAtColumnType();

        assertThat(columnType)
                .as("created_at 컬럼은 timestamptz(오프셋 보존)여야 한다 — 오프셋 없는 시각 타입은 timestamp 로 매핑돼 버려진다")
                .isEqualTo("timestamp with time zone");
    }

    /** information_schema 를 직접 읽어 Java 필드 타입과 무관하게 실제 DB 컬럼 타입을 확인한다. */
    private String createdAtColumnType() throws Exception {
        String sql = "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'auditing_probe_entity' AND column_name = 'created_at'";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString("data_type");
        }
    }

    /** 테스트가 직접 시각을 앞으로 넘겨 createdAt · updatedAt 을 서로 다른 시각으로 갈라 보기 위한 설정. */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(FIXED_INSTANT, SEOUL_OFFSET);
        }
    }

    /** 저장 시점 사이에 시각을 앞으로 넘길 수 있는 고정 Clock — {@code Clock.fixed} 는 항상 같은 값만 내어 준다. */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        /** 다음 저장부터 이 시각을 반환하도록 앞으로 넘긴다. */
        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

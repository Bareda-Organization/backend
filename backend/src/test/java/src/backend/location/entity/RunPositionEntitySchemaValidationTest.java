package src.backend.location.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManager;

import src.backend.BackendApplication;

import testsupport.db.MigratedPostgresTestBase;

/**
 * location 모듈 엔티티 1개({@link RunPosition})가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code run} 은 논리적 부모이나 FK 가 미설정이라(ERD §4.2) 별도 부모 행 없이 임의의
 * {@code Long} 으로 바로 저장된다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
}, excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = RunPosition.class)
class RunPositionEntitySchemaValidationTest extends MigratedPostgresTestBase {

    @DynamicPropertySource
    static void 데이터소스를_시드_없이_적용한_공유_컨테이너로_돌린다(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void 시드_없이_V1_스키마만_적용한다() {
        migrate(SCHEMA_LOCATION);
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    void runPosition_은_FK_가_없어_임의의_run_id_로_저장되고_좌표가_BigDecimal_정밀도로_왕복한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        RunPosition position = RunPosition.onReceive(1L,
                new BigDecimal("37.500000"), new BigDecimal("127.000000"), now, now);
        entityManager.persist(position);
        entityManager.flush();
        entityManager.clear();

        RunPosition found = entityManager.find(RunPosition.class, position.getId());

        assertThat(found.getRunId()).isEqualTo(1L);
        assertThat(found.getLat()).isEqualByComparingTo("37.500000");
        assertThat(found.getLng()).isEqualByComparingTo("127.000000");
        assertThat(found.getSpeed()).isNull();
    }
}

package src.backend.audit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
 * audit 모듈 엔티티 1개({@link AuditLog})가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code academy}·{@code account} 는 논리적 부모이나 FK 가 미설정이라(ERD §4.2) 별도 부모 행 없이
 * 임의의 {@code Long} 으로 바로 저장된다. {@code detail} 은 {@code jsonb} 컬럼이라
 * {@code Map<String, Object>} 왕복도 함께 확인한다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = AuditLog.class)
class AuditLogEntitySchemaValidationTest extends MigratedPostgresTestBase {

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
    void auditLog_는_FK_가_없어_최소_상태로_저장되고_category_action_enum_이_왕복한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        AuditLog log = AuditLog.forOccurrence(AuditCategory.LOGIN, AuditAction.LOGIN_SUCCESS, now);
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        AuditLog found = entityManager.find(AuditLog.class, log.getId());

        assertThat(found.getCategory()).isEqualTo(AuditCategory.LOGIN);
        assertThat(found.getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
        assertThat(found.isBlockEvent()).isFalse();
        assertThat(found.getAcademyId()).isNull();
        assertThat(found.getDetail()).isNull();
    }

    @Test
    void auditLog_의_detail_jsonb_컬럼이_Map_으로_왕복한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        AuditLog log = AuditLog.forOccurrence(AuditCategory.DATA_ACCESS, AuditAction.READ, now);
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        // detail 은 팩토리 대상이 아니라(부속 필드), jsonb 매핑 확인을 위해 네이티브 SQL 로 직접 채운다.
        entityManager.createNativeQuery(
                        "UPDATE audit_log SET detail = CAST(:detail AS jsonb) WHERE id = :id")
                .setParameter("detail", "{\"field\": \"phone\", \"before\": \"010-0000-0000\"}")
                .setParameter("id", log.getId())
                .executeUpdate();
        entityManager.clear();

        AuditLog found = entityManager.find(AuditLog.class, log.getId());

        assertThat(found.getDetail())
                .isEqualTo(Map.of("field", "phone", "before", "010-0000-0000"));
    }
}

package src.backend.academy.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManager;

import src.backend.BackendApplication;
import src.backend.global.config.ClockConfig;
import src.backend.global.config.JpaAuditingConfig;

import testsupport.db.MigratedPostgresTestBase;

/**
 * academy 모듈 엔티티 3개({@link Academy} · {@link AcademySetting} · {@link AcademyStaff})가
 * 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code academy.entity} 패키지만 매핑 대상에 넣는다 — 39개 중 이
 * 3개만 만들어진 시점이라 {@code BackendApplication} 기본 엔티티 스캔(전체 {@code src.backend})을
 * 그대로 쓰면 아직 없는 나머지 36개 테이블을 찾다 컨텍스트 로딩이 실패한다. 시드는 별도 태스크의
 * 산출물이라 함께 적재하지 않고, {@link MigratedPostgresTestBase} 로 V1 스키마만 적용한 컨테이너에
 * 데이터소스를 연결한다({@code spring.flyway.enabled=false} 로 Spring 자신의 재적용은 끈다).
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = Academy.class)
@Import({ClockConfig.class, JpaAuditingConfig.class})
class AcademyEntitySchemaValidationTest extends MigratedPostgresTestBase {

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
    void academy_와_1대1_확장_academySetting_이_기본값으로_저장되고_조회된다() {
        Academy academy = Academy.register("A001", "테스트학원", "서울", "테스트로 1", "010-0000-0000");
        entityManager.persist(academy);
        entityManager.flush();

        AcademySetting setting = AcademySetting.forAcademy(academy.getId());
        entityManager.persist(setting);
        entityManager.flush();
        entityManager.clear();

        Academy foundAcademy = entityManager.find(Academy.class, academy.getId());
        AcademySetting foundSetting = entityManager.find(AcademySetting.class, academy.getId());

        assertThat(foundAcademy.getStatus())
                .as("status 컬럼이 소문자 'active' 로 저장됐다가 대문자 상수로 복원돼야 한다")
                .isEqualTo(AcademyStatus.ACTIVE);
        assertThat(foundAcademy.getCreatedAt()).isNotNull();
        assertThat(foundSetting.getNoShowWaitMinutes())
                .isEqualTo(AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES);
    }

    @Test
    void academyStaff_는_academy_와_account_를_연결하는_행으로_저장되고_조회된다() {
        Academy academy = Academy.register("A002", "테스트학원2", "부산", null, null);
        entityManager.persist(academy);
        entityManager.flush();
        // account 는 이 태스크의 대상 밖이라 엔티티가 없다 — FK(fk_academy_staff_account)를
        // 만족시키기 위해 최소 컬럼만 채운 행을 네이티브 SQL로 직접 넣는다.
        Number accountId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                                + "VALUES (:academyId, 'staff_login', 'hash', '스태프', '010-1111-2222', 'staff', 'active') "
                                + "RETURNING id")
                .setParameter("academyId", academy.getId())
                .getSingleResult();

        AcademyStaff staff = AcademyStaff.uponApproval(academy.getId(), accountId.longValue());
        entityManager.persist(staff);
        entityManager.flush();
        entityManager.clear();

        AcademyStaff found = entityManager.find(AcademyStaff.class, staff.getId());

        assertThat(found.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(found.getAcademyId()).isEqualTo(academy.getId());
        assertThat(found.getAccountId()).isEqualTo(accountId.longValue());
    }
}

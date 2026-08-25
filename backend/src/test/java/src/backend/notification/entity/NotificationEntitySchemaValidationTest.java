package src.backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManager;

import src.backend.BackendApplication;
import src.backend.global.common.enums.Role;
import src.backend.global.config.ClockConfig;
import src.backend.global.config.JpaAuditingConfig;

import testsupport.db.MigratedPostgresTestBase;

/**
 * notification 모듈 엔티티 3개({@link NotificationLog} · {@link DeviceToken} ·
 * {@link NotificationSetting})가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code notification.entity} 패키지만 매핑 대상에 넣는다. {@code device_token}·
 * {@code notification_setting} 은 {@code account} 에 실제 FK 가 걸려 있어 네이티브 SQL로
 * academy→account 체인을 먼저 채운다. {@code notification_log} 는 FK 미설정 테이블이라 임의의
 * {@code Long} 으로 바로 저장된다. {@link DeviceToken} 이 {@code BaseTimeEntity} 를 상속하므로
 * {@code @DataJpaTest} 슬라이스가 스캔하지 않는 {@code JpaAuditingConfig}·{@code ClockConfig} 를
 * 명시적으로 끌어와야 {@code created_at}/{@code updated_at} 이 채워진다
 * ({@code AcademyEntitySchemaValidationTest} 와 같은 이유) — 누락 시 NOT NULL 위반으로 저장 자체가 실패한다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
}, excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = NotificationLog.class)
@Import({ClockConfig.class, JpaAuditingConfig.class})
class NotificationEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** academy → account 를 순서대로 넣고 account_id 를 반환한다. */
    private long 학부모_계정을_만든다() {
        Number academyId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO academy (code, name, region, status) "
                                + "VALUES ('A400', '테스트학원', '서울', 'active') RETURNING id")
                .getSingleResult();
        Number accountId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                                + "VALUES (:academyId, 'parent_login', 'hash', '학부모', '010-2222-3333', 'parent', 'active') "
                                + "RETURNING id")
                .setParameter("academyId", academyId)
                .getSingleResult();
        entityManager.flush();
        return accountId.longValue();
    }

    @Test
    void notificationLog_는_FK_가_없어_임의의_id_로_저장되고_recipientRole_이_Role_로_왕복한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        NotificationLog log = NotificationLog.forOutbox(1L, 1L, "학부모", Role.PARENT,
                NotificationType.BOARDING, "승차 알림", "학생이 승차했습니다.", "dedup-key-1", now);
        entityManager.persist(log);
        entityManager.flush();
        entityManager.clear();

        NotificationLog found = entityManager.find(NotificationLog.class, log.getId());

        assertThat(found.getRecipientRole()).isEqualTo(Role.PARENT);
        assertThat(found.getType()).isEqualTo(NotificationType.BOARDING);
        assertThat(found.getPushState()).isEqualTo(PushState.PENDING);
        assertThat(found.getPushAttempts()).isZero();
    }

    @Test
    void deviceToken_이_account_FK_를_만족하며_저장되고_platform_enum_이_왕복한다() {
        long accountId = 학부모_계정을_만든다();

        DeviceToken token = DeviceToken.register(accountId, "device-1", "fcm-token-1", DevicePlatform.ANDROID);
        entityManager.persist(token);
        entityManager.flush();
        entityManager.clear();

        DeviceToken found = entityManager.find(DeviceToken.class, token.getId());

        assertThat(found.getAccountId()).isEqualTo(accountId);
        assertThat(found.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void notificationSetting_은_account_id_를_그대로_PK_로_써서_저장되고_기본값이_전부_ON_이다() {
        long accountId = 학부모_계정을_만든다();
        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        NotificationSetting setting = NotificationSetting.forAccount(accountId, updatedAt);
        entityManager.persist(setting);
        entityManager.flush();
        entityManager.clear();

        NotificationSetting found = entityManager.find(NotificationSetting.class, accountId);

        assertThat(found.getAccountId()).isEqualTo(accountId);
        assertThat(found.isArrive()).isTrue();
        assertThat(found.isBoarding()).isTrue();
        assertThat(found.isNoShow()).isTrue();
    }
}

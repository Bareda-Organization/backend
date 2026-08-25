package src.backend.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
import src.backend.global.common.enums.Role;

import testsupport.db.MigratedPostgresTestBase;

/**
 * account 모듈 엔티티 5개({@link Account} · {@link SignupRequest} · {@link SystemAdmin} ·
 * {@link RefreshToken} · {@link VerificationCode})가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code account.entity} 패키지만 매핑 대상에 넣는다 — 다른
 * 에이전트가 각자 워크트리에서 만드는 나머지 34개 테이블용 엔티티는 이 워크트리에 없으므로
 * {@code BackendApplication} 기본 스캔을 그대로 쓰면 컨텍스트 로딩이 실패한다(Task 3 선례와 동일
 * 이유). FK 대상인 {@code academy} 는 이 태스크 범위 밖이라 엔티티가 없어, 최소 컬럼만 채운
 * 행을 네이티브 SQL 로 직접 넣는다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = Account.class)
@Import({ClockConfig.class, JpaAuditingConfig.class})
class AccountEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** {@code academy} 엔티티가 이 워크트리에 없어, FK 를 만족시키는 최소 행을 네이티브 SQL 로 만든다. */
    private Long insertAcademy(String code) {
        Number academyId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO academy (code, name, region, status) "
                                + "VALUES (:code, '테스트학원', '서울', 'active') RETURNING id")
                .setParameter("code", code)
                .getSingleResult();
        return academyId.longValue();
    }

    @Test
    void account_이_학원_소속_계정으로_저장되고_조회된다() {
        Long academyId = insertAcademy("ACC001");

        Account account = Account.forSignup(academyId, "parent_login", "hash", "학부모", "010-1111-0000",
                null, Role.PARENT);
        entityManager.persist(account);
        entityManager.flush();
        entityManager.clear();

        Account found = entityManager.find(Account.class, account.getId());

        assertThat(found.getRole()).isEqualTo(Role.PARENT);
        assertThat(found.getStatus())
                .as("status 컬럼이 소문자 'pending' 으로 저장됐다가 대문자 상수로 복원돼야 한다")
                .isEqualTo(AccountStatus.PENDING);
        assertThat(found.getAcademyId()).isEqualTo(academyId);
        assertThat(found.getFailedAttempts()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void signupRequest_가_계정의_가입_승인_요청으로_저장되고_조회된다() {
        Long academyId = insertAcademy("ACC002");
        Account account = Account.forSignup(academyId, "staff_login", "hash", "관계자", "010-2222-0000",
                "staff@example.com", Role.STAFF);
        entityManager.persist(account);
        entityManager.flush();

        OffsetDateTime requestedAt = OffsetDateTime.now(ZoneOffset.UTC);
        SignupRequest request = SignupRequest.uponSubmission(academyId, account.getId(), Role.STAFF,
                ApproverType.SYSTEM_ADMIN, requestedAt);
        entityManager.persist(request);
        entityManager.flush();
        entityManager.clear();

        SignupRequest found = entityManager.find(SignupRequest.class, request.getId());

        assertThat(found.getStatus()).isEqualTo(SignupRequestStatus.PENDING);
        assertThat(found.getRequestedRole()).isEqualTo(Role.STAFF);
        assertThat(found.getApproverType()).isEqualTo(ApproverType.SYSTEM_ADMIN);
        assertThat(found.getAccountId()).isEqualTo(account.getId());
    }

    @Test
    void systemAdmin_이_계정의_메인_관리자_등록부로_저장되고_조회된다() {
        Account account = Account.forSignup(null, "admin_login", "hash", "관리자", "010-3333-0000",
                null, Role.SYSTEM_ADMIN);
        entityManager.persist(account);
        entityManager.flush();

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        SystemAdmin systemAdmin = SystemAdmin.uponGrant(account.getId(), createdAt);
        entityManager.persist(systemAdmin);
        entityManager.flush();
        entityManager.clear();

        SystemAdmin found = entityManager.find(SystemAdmin.class, systemAdmin.getId());

        assertThat(found.getAccountId()).isEqualTo(account.getId());
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void refreshToken_이_계정의_장기_토큰으로_저장되고_조회된다() {
        Long academyId = insertAcademy("ACC003");
        Account account = Account.forSignup(academyId, "driver_login", "hash", "기사", "010-4444-0000",
                null, Role.DRIVER);
        entityManager.persist(account);
        entityManager.flush();

        OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshToken token = RefreshToken.issue(account.getId(), "token-hash-1", issuedAt,
                issuedAt.plusDays(14), "android-device-1");
        entityManager.persist(token);
        entityManager.flush();
        entityManager.clear();

        RefreshToken found = entityManager.find(RefreshToken.class, token.getId());

        assertThat(found.getAccountId()).isEqualTo(account.getId());
        assertThat(found.getTokenHash()).isEqualTo("token-hash-1");
        assertThat(found.getRevokedAt()).isNull();
    }

    @Test
    void verificationCode_가_전화번호별_인증_코드로_저장되고_조회된다() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        VerificationCode code = VerificationCode.issue("010-5555-0000", "123456",
                VerificationPurpose.LOGIN_ID, createdAt.plusMinutes(5), createdAt);
        entityManager.persist(code);
        entityManager.flush();
        entityManager.clear();

        VerificationCode found = entityManager.find(VerificationCode.class, code.getId());

        assertThat(found.getPurpose()).isEqualTo(VerificationPurpose.LOGIN_ID);
        assertThat(found.getAttemptCount()).isZero();
        assertThat(found.getConsumedAt()).isNull();
    }
}

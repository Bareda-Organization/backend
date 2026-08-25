package src.backend.student.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.config.ClockConfig;
import src.backend.global.config.JpaAuditingConfig;

import testsupport.db.MigratedPostgresTestBase;

/**
 * student 모듈 엔티티 7개({@link Student} · {@link Guardian} · {@link GuardianStudent} ·
 * {@link LinkRequest} · {@link LinkCode} · {@link WeeklyAddress} · {@link Stop})가 실제 V1
 * 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code student.entity} 패키지만 매핑 대상에 넣는다 — 다른
 * 에이전트가 각자 워크트리에서 만드는 나머지 32개 테이블용 엔티티는 이 워크트리에 없다.
 * FK 대상인 {@code academy}·{@code account} 는 이 태스크 범위 밖이라 엔티티가 없어, 최소
 * 컬럼만 채운 행을 네이티브 SQL 로 직접 넣는다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = Student.class)
@Import({ClockConfig.class, JpaAuditingConfig.class})
class StudentEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** {@code account} 엔티티가 이 워크트리에 없어, FK 를 만족시키는 최소 행을 네이티브 SQL 로 만든다. */
    private Long insertAccount(Long academyId, String loginId, String role) {
        Number accountId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                                + "VALUES (:academyId, :loginId, 'hash', '테스트계정', '010-0000-0000', "
                                + ":role, 'active') RETURNING id")
                .setParameter("academyId", academyId)
                .setParameter("loginId", loginId)
                .setParameter("role", role)
                .getSingleResult();
        return accountId.longValue();
    }

    @Test
    void student_이_학원_소속_학생으로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU001");

        Student student = Student.register(academyId, "홍길동", "010-1111-2222", null, Gender.MALE,
                LocalDate.of(2015, 3, 2), "초등 3학년", "3반", 5, "특이사항 없음", true);
        entityManager.persist(student);
        entityManager.flush();
        entityManager.clear();

        Student found = entityManager.find(Student.class, student.getId());

        assertThat(found.getGender()).isEqualTo(Gender.MALE);
        assertThat(found.getAccountId())
                .as("가입 승인 전이라 계정 미연결이 정상이다(AUTH-11)")
                .isNull();
        assertThat(found.isCanGoAlone()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void guardian_이_학원_소속_보호자로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU002");
        Long accountId = insertAccount(academyId, "guardian_login", "parent");

        Guardian guardian = Guardian.forSignup(academyId, accountId, "김보호", "010-3333-4444");
        entityManager.persist(guardian);
        entityManager.flush();
        entityManager.clear();

        Guardian found = entityManager.find(Guardian.class, guardian.getId());

        assertThat(found.getAccountId()).isEqualTo(accountId);
        assertThat(found.getPhone()).isEqualTo("010-3333-4444");
    }

    @Test
    void guardianStudent_가_보호자와_학생의_연결로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU003");
        Long accountId = insertAccount(academyId, "guardian_login2", "parent");
        Guardian guardian = Guardian.forSignup(academyId, accountId, "이보호", "010-5555-6666");
        entityManager.persist(guardian);
        Student student = Student.register(academyId, "이학생", null, null, Gender.FEMALE, null, null,
                null, null, null, false);
        entityManager.persist(student);
        entityManager.flush();

        OffsetDateTime linkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        GuardianStudent link = GuardianStudent.uponLink(guardian.getId(), student.getId(), linkedAt);
        entityManager.persist(link);
        entityManager.flush();
        entityManager.clear();

        GuardianStudent found = entityManager.find(GuardianStudent.class, link.getId());

        assertThat(found.getGuardianId()).isEqualTo(guardian.getId());
        assertThat(found.getStudentId()).isEqualTo(student.getId());
        assertThat(found.getUnlinkedAt()).isNull();
    }

    @Test
    void linkRequest_가_보호자의_자녀_연결_요청으로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU004");
        Long accountId = insertAccount(academyId, "guardian_login3", "parent");
        Guardian guardian = Guardian.forSignup(academyId, accountId, "박보호", "010-7777-8888");
        entityManager.persist(guardian);
        Student student = Student.register(academyId, "박학생", null, null, null, null, null, null,
                null, null, false);
        entityManager.persist(student);
        entityManager.flush();

        OffsetDateTime requestedAt = OffsetDateTime.now(ZoneOffset.UTC);
        LinkRequest request = LinkRequest.uponRequest(guardian.getId(), student.getId(), requestedAt,
                requestedAt.plusMinutes(10));
        entityManager.persist(request);
        entityManager.flush();
        entityManager.clear();

        LinkRequest found = entityManager.find(LinkRequest.class, request.getId());

        assertThat(found.getStatus()).isEqualTo(LinkRequestStatus.PENDING);
        assertThat(found.getGuardianId()).isEqualTo(guardian.getId());
    }

    @Test
    void linkCode_가_연결_요청의_인증_코드로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU005");
        Long accountId = insertAccount(academyId, "guardian_login4", "parent");
        Guardian guardian = Guardian.forSignup(academyId, accountId, "최보호", "010-9999-0000");
        entityManager.persist(guardian);
        Student student = Student.register(academyId, "최학생", null, null, null, null, null, null,
                null, null, false);
        entityManager.persist(student);
        OffsetDateTime requestedAt = OffsetDateTime.now(ZoneOffset.UTC);
        LinkRequest request = LinkRequest.uponRequest(guardian.getId(), student.getId(), requestedAt,
                requestedAt.plusMinutes(10));
        entityManager.persist(request);
        entityManager.flush();

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        LinkCode code = LinkCode.forRequest(request.getId(), "654321", createdAt.plusMinutes(5), createdAt);
        entityManager.persist(code);
        entityManager.flush();
        entityManager.clear();

        LinkCode found = entityManager.find(LinkCode.class, code.getId());

        assertThat(found.getLinkRequestId()).isEqualTo(request.getId());
        assertThat(found.getUsedAt()).isNull();
    }

    @Test
    void weeklyAddress_가_학생의_요일별_등하원_주소로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU006");
        Student student = Student.register(academyId, "정학생", null, null, null, null, null, null,
                null, null, false);
        entityManager.persist(student);
        entityManager.flush();

        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        WeeklyAddress address = WeeklyAddress.register(student.getId(), Weekday.MON, Direction.TO_ACADEMY,
                "서울시 강남구 테헤란로 1", "3층", updatedAt);
        entityManager.persist(address);
        entityManager.flush();
        entityManager.clear();

        WeeklyAddress found = entityManager.find(WeeklyAddress.class, address.getId());

        assertThat(found.getWeekday()).isEqualTo(Weekday.MON);
        assertThat(found.getDirection()).isEqualTo(Direction.TO_ACADEMY);
        assertThat(found.isVerified())
                .as("등록 시점은 아직 주소 검증 전이라 false 여야 한다")
                .isFalse();
        assertThat(found.getLat()).isNull();
        assertThat(found.getStopId()).isNull();
    }

    @Test
    void stop_이_학원의_승하차지_마스터로_저장되고_조회된다() {
        Long academyId = insertAcademy("STU007");

        Stop stop = Stop.forVerifiedAddress(academyId, "중앙로 스타빌딩 앞", "서울시 강남구 테헤란로 1",
                new BigDecimal("37.500000"), new BigDecimal("127.030000"));
        entityManager.persist(stop);
        entityManager.flush();
        entityManager.clear();

        Stop found = entityManager.find(Stop.class, stop.getId());

        assertThat(found.getLat()).isEqualByComparingTo("37.500000");
        assertThat(found.getLng()).isEqualByComparingTo("127.030000");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}

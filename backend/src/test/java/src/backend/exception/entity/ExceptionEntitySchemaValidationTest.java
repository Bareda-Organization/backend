package src.backend.exception.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
import src.backend.global.common.enums.ManagerRole;

import testsupport.db.MigratedPostgresTestBase;

/**
 * exception 모듈 엔티티 4개({@link NoShowCase} · {@link NoShowContact} · {@link EmergencyAlert} ·
 * {@link ExceptionReport})가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code exception.entity} 패키지만 매핑 대상에 넣는다(다른 모듈
 * 엔티티가 이 워크트리에 없다). {@code no_show_case}·{@code no_show_contact} 는 {@code run_rider}
 * 에 실제 FK 가 걸려 있어 네이티브 SQL로 academy→bus→run→student→stop→run_rider 체인을 먼저 채운다.
 * {@code emergency_alert}·{@code exception_report} 는 FK 미설정 테이블이라 임의의 {@code Long} 으로
 * 바로 저장된다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = NoShowCase.class)
class ExceptionEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** academy→bus→run→student→stop→run_rider 를 순서대로 넣고 run_rider_id 를 반환한다. */
    private long 미승차_대상_탑승자_행을_만든다() {
        Number academyId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO academy (code, name, region, status) "
                                + "VALUES ('A300', '테스트학원', '서울', 'active') RETURNING id")
                .getSingleResult();
        Number busId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO bus (academy_id, bus_no, plate_no, capacity, student_capacity) "
                                + "VALUES (:academyId, '1호차', '서울1가3333', 10, 8) RETURNING id")
                .setParameter("academyId", academyId)
                .getSingleResult();
        Number runId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, "
                                + "confirm_at, status, origin_name, destination_name) "
                                + "VALUES (:academyId, :busId, CURRENT_DATE, 'to_academy', "
                                + "'2026-08-25T08:00:00+09', '2026-08-25T07:30:00+09', 'idle', '집', '학원') "
                                + "RETURNING id")
                .setParameter("academyId", academyId)
                .setParameter("busId", busId)
                .getSingleResult();
        Number studentId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO student (academy_id, name) VALUES (:academyId, '테스트학생') RETURNING id")
                .setParameter("academyId", academyId)
                .getSingleResult();
        Number stopId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO stop (academy_id, name, address, lat, lng) "
                                + "VALUES (:academyId, '정류장1', '서울시 어딘가 1', 37.5, 127.0) RETURNING id")
                .setParameter("academyId", academyId)
                .getSingleResult();
        Number runRiderId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO run_rider (run_id, student_id, stop_id, status) "
                                + "VALUES (:runId, :studentId, :stopId, 'waiting') RETURNING id")
                .setParameter("runId", runId)
                .setParameter("studentId", studentId)
                .setParameter("stopId", stopId)
                .getSingleResult();
        entityManager.flush();
        return runRiderId.longValue();
    }

    @Test
    void noShowCase_와_noShowContact_가_체인으로_저장되고_decision_enum_이_왕복한다() {
        long runRiderId = 미승차_대상_탑승자_행을_만든다();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        NoShowCase noShowCase = NoShowCase.forRunRider(runRiderId, now, now.plusMinutes(3), now);
        entityManager.persist(noShowCase);
        entityManager.flush();

        NoShowContact contact = NoShowContact.forAttempt(noShowCase.getId(),
                ContactAttemptType.CALL, ContactResult.NO_ANSWER, 1L, now.plusMinutes(1));
        entityManager.persist(contact);
        entityManager.flush();
        entityManager.clear();

        NoShowCase foundCase = entityManager.find(NoShowCase.class, noShowCase.getId());
        NoShowContact foundContact = entityManager.find(NoShowContact.class, contact.getId());

        assertThat(foundCase.getRunRiderId()).isEqualTo(runRiderId);
        assertThat(foundCase.getCreatedAt()).isNotNull();
        assertThat(foundContact.getNoShowCaseId()).isEqualTo(noShowCase.getId());
        assertThat(foundContact.getAttemptType()).isEqualTo(ContactAttemptType.CALL);
        assertThat(foundContact.getResult()).isEqualTo(ContactResult.NO_ANSWER);
        assertThat(foundContact.getDecision()).isNull();
    }

    @Test
    void emergencyAlert_는_FK_가_없어_임의의_id_로_저장되고_raisedByRole_이_ManagerRole_로_왕복한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        EmergencyAlert alert = EmergencyAlert.onRaise(1L, 1L, "1호차", 1L, ManagerRole.DRIVER,
                EmergencyType.ACCIDENT, 5, now, now, UUID.randomUUID());
        entityManager.persist(alert);
        entityManager.flush();
        entityManager.clear();

        EmergencyAlert found = entityManager.find(EmergencyAlert.class, alert.getId());

        assertThat(found.getRaisedByRole()).isEqualTo(ManagerRole.DRIVER);
        assertThat(found.getType()).isEqualTo(EmergencyType.ACCIDENT);
        assertThat(found.getRiderCount()).isEqualTo(5);
    }

    @Test
    void exceptionReport_는_FK_가_없어_임의의_id_로_저장된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        ExceptionReport report = ExceptionReport.forReport(1L, 1L, ExceptionReportType.ROAD_BLOCK,
                "도로 통제로 우회", 1L, now);
        entityManager.persist(report);
        entityManager.flush();
        entityManager.clear();

        ExceptionReport found = entityManager.find(ExceptionReport.class, report.getId());

        assertThat(found.getType()).isEqualTo(ExceptionReportType.ROAD_BLOCK);
        assertThat(found.getRunRiderId()).isNull();
    }
}

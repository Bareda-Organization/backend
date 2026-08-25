package src.backend.boarding.entity;

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

import testsupport.db.MigratedPostgresTestBase;

/**
 * boarding 모듈 엔티티 2개({@link RunRider} · {@link RiderStatusHistory})가 실제 V1 스키마와
 * 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code boarding.entity} 패키지만 매핑 대상에 넣는다 — Phase 1 이
 * 진행 중인 워크트리라 다른 모듈 엔티티가 존재하지 않는다({@code AcademyEntitySchemaValidationTest} 와
 * 같은 이유). {@code run}·{@code student}·{@code stop} 은 엔티티가 없는 테이블이라 네이티브 SQL로
 * FK 를 만족시키는 최소 행을 직접 넣는다. {@link RunRider} 가 {@code BaseTimeEntity} 를 상속하므로
 * {@code @DataJpaTest} 슬라이스가 스캔하지 않는 {@code JpaAuditingConfig}·{@code ClockConfig} 를
 * 명시적으로 끌어와야 {@code created_at} 이 채워진다({@code AcademyEntitySchemaValidationTest} 와
 * 같은 이유) — 누락 시 NOT NULL 위반으로 저장 자체가 실패한다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = RunRider.class)
@Import({ClockConfig.class, JpaAuditingConfig.class})
class BoardingEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** academy → bus → run, academy → student, academy → stop 을 순서대로 넣고 run_id/student_id/stop_id 를 반환한다. */
    private long[] 회차_학생_승하차지_체인을_만든다() {
        Number academyId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO academy (code, name, region, status) "
                                + "VALUES ('A100', '테스트학원', '서울', 'active') RETURNING id")
                .getSingleResult();
        Number busId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO bus (academy_id, bus_no, plate_no, capacity, student_capacity) "
                                + "VALUES (:academyId, '1호차', '서울1가1111', 10, 8) RETURNING id")
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
        entityManager.flush();
        return new long[] {runId.longValue(), studentId.longValue(), stopId.longValue()};
    }

    @Test
    void runRider_가_저장되고_상태_enum_이_소문자로_왕복한다() {
        long[] chain = 회차_학생_승하차지_체인을_만든다();
        RunRider runRider = RunRider.uponConfirmation(chain[0], chain[1], chain[2]);
        entityManager.persist(runRider);
        entityManager.flush();
        entityManager.clear();

        RunRider found = entityManager.find(RunRider.class, runRider.getId());

        assertThat(found.getStatus()).isEqualTo(RiderStatus.WAITING);
        assertThat(found.getRunId()).isEqualTo(chain[0]);
        assertThat(found.getStudentId()).isEqualTo(chain[1]);
        assertThat(found.getStopId()).isEqualTo(chain[2]);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getChange()).isNull();
    }

    @Test
    void riderStatusHistory_는_FK_가_없어_임의의_run_rider_id_로도_저장된다() {
        OffsetDateTime changedAt = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        RiderStatusHistory history = RiderStatusHistory.forTransition(
                999_999L, RiderStatus.WAITING, RiderStatus.BOARDED, ActorType.ESCORT, changedAt);
        entityManager.persist(history);
        entityManager.flush();
        entityManager.clear();

        RiderStatusHistory found = entityManager.find(RiderStatusHistory.class, history.getId());

        assertThat(found.getRunRiderId()).isEqualTo(999_999L);
        assertThat(found.getFromStatus()).isEqualTo(RiderStatus.WAITING);
        assertThat(found.getToStatus()).isEqualTo(RiderStatus.BOARDED);
        assertThat(found.getActorType()).isEqualTo(ActorType.ESCORT);
        // client_key 는 저장하지 않은 채로도 UNIQUE(nullable) 컬럼이라 통과해야 한다.
        assertThat(found.getClientKey()).isNull();
    }
}

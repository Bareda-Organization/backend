package src.backend.request.entity;

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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.persistence.EntityManager;

import src.backend.BackendApplication;

import testsupport.db.MigratedPostgresTestBase;

/**
 * request 모듈 엔티티 2개({@link BoardingIntent} · {@link ChangeRequest})가 실제 V1 스키마와
 * 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 {@code request.entity} 패키지만 매핑 대상에 넣는다(다른 모듈 엔티티가
 * 이 워크트리에 없다). {@code academy}·{@code bus}·{@code run}·{@code student} 는 엔티티가 없는
 * 테이블이라 네이티브 SQL로 FK 를 만족시키는 최소 행을 직접 넣는다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
}, excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = BoardingIntent.class)
class RequestEntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    /** academy → bus → run, academy → student 를 순서대로 넣고 academy_id/run_id/student_id 를 반환한다. */
    private long[] 학원_회차_학생_체인을_만든다() {
        Number academyId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO academy (code, name, region, status) "
                                + "VALUES ('A200', '테스트학원', '서울', 'active') RETURNING id")
                .getSingleResult();
        Number busId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO bus (academy_id, bus_no, plate_no, capacity, student_capacity) "
                                + "VALUES (:academyId, '1호차', '서울1가2222', 10, 8) RETURNING id")
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
        entityManager.flush();
        return new long[] {academyId.longValue(), runId.longValue(), studentId.longValue()};
    }

    @Test
    void boardingIntent_가_기본값과_함께_저장되고_조회된다() {
        long[] chain = 학원_회차_학생_체인을_만든다();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        BoardingIntent intent = BoardingIntent.forRun(chain[1], chain[2], createdAt);
        entityManager.persist(intent);
        entityManager.flush();
        entityManager.clear();

        BoardingIntent found = entityManager.find(BoardingIntent.class, intent.getId());

        assertThat(found.isRiding()).isTrue();
        assertThat(found.getChangeUsedCount()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void changeRequest_가_대기_상태로_생성되고_source_type_status_enum_이_왕복한다() {
        long[] chain = 학원_회차_학생_체인을_만든다();
        OffsetDateTime requestedAt = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        // CANCEL 을 쓴다 — RELOCATE 는 ck_change_request_new_address CHECK 로 new_address 가
        // 필수인데, 그 컬럼은 최소 팩토리 대상이 아니라(도메인 Phase 담당) 여기서 채울 수단이 없다.
        ChangeRequest changeRequest = ChangeRequest.forRequest(chain[0], chain[1], chain[2],
                ChangeRequestSource.INTENT, ChangeRequestType.CANCEL, (short) 2,
                chain[2], requestedAt);
        entityManager.persist(changeRequest);
        entityManager.flush();
        entityManager.clear();

        ChangeRequest found = entityManager.find(ChangeRequest.class, changeRequest.getId());

        assertThat(found.getSource()).isEqualTo(ChangeRequestSource.INTENT);
        assertThat(found.getType()).isEqualTo(ChangeRequestType.CANCEL);
        assertThat(found.getStatus()).isEqualTo(ChangeRequestStatus.PENDING);
        assertThat(found.getWindowSegment()).isEqualTo((short) 2);
    }
}

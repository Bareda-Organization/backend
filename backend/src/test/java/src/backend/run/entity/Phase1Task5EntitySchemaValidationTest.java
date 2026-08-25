package src.backend.run.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;

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
import src.backend.academy.entity.Academy;
import src.backend.bus.entity.Bus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.global.config.ClockConfig;
import src.backend.global.config.JpaAuditingConfig;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.schedule.entity.Schedule;

import testsupport.db.MigratedPostgresTestBase;

/**
 * Phase 1 · Task 5 담당 11개({@code bus} 1 · {@code manager} 2 · {@code schedule} 1 ·
 * {@code routing} 6 · {@code run} 1)가 실제 V1 스키마와 정확히 맞는지 확인한다.
 *
 * <p>{@code @EntityScan} 으로 이 태스크가 만든 5개 패키지(각 패키지 대표 클래스 1개씩)만 매핑
 * 대상에 넣는다 — 같은 시각 다른 두 에이전트가 만드는 나머지 28개는 이 워크트리에 아직 없어,
 * {@code BackendApplication} 기본 스캔을 그대로 쓰면 컨텍스트 로딩이 실패한다. {@code academy}
 * 모듈은 Task 3 산출물로 이 브랜치 분기점에 이미 존재해 FK 상대 테이블(academy)을 채우는 데
 * 엔티티로 직접 쓴다 — 아직 소유 에이전트가 없는 {@code stop} 은 {@code AcademyStaffTest} 의
 * {@code account} 처리와 같은 방식(네이티브 SQL로 최소 컬럼만 채운 행 삽입)을 쓴다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
}, excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ContextConfiguration(classes = BackendApplication.class)
@EntityScan(basePackageClasses = {Bus.class, Manager.class, Schedule.class, Route.class, Run.class, Academy.class})
@Import({ClockConfig.class, JpaAuditingConfig.class})
class Phase1Task5EntitySchemaValidationTest extends MigratedPostgresTestBase {

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

    private Long insertAcademy() {
        Academy academy = Academy.register("P1T5", "테스트학원", "서울", null, null);
        entityManager.persist(academy);
        entityManager.flush();
        return academy.getId();
    }

    private Long insertBus(Long academyId) {
        Bus bus = Bus.register(academyId, "1호차", "12가1234", 45, 1, 1, 43);
        entityManager.persist(bus);
        entityManager.flush();
        return bus.getId();
    }

    /** {@code stop} 은 이 태스크 담당이 아니라 엔티티가 없다 — FK({@code fk_route_stop_stop} 등)를 만족시킬 최소 컬럼만 네이티브 SQL로 채운다. */
    private Long insertStop(Long academyId) {
        Number stopId = (Number) entityManager.createNativeQuery(
                        "INSERT INTO stop (academy_id, name, address, lat, lng) "
                                + "VALUES (:academyId, '테스트정류장', '테스트주소', 37.5, 127.0) RETURNING id")
                .setParameter("academyId", academyId)
                .getSingleResult();
        return stopId.longValue();
    }

    private Run insertRun(Long academyId, Long busId, Long scheduleId) {
        OffsetDateTime departTime = OffsetDateTime.parse("2026-09-01T07:30:00+09:00");
        Run run = Run.forSchedule(academyId, busId, scheduleId, LocalDate.of(2026, 9, 1), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30), "정문", "학원", 40);
        entityManager.persist(run);
        entityManager.flush();
        return run;
    }

    @Test
    void bus_가_저장되고_조회된다() {
        Long academyId = insertAcademy();

        Bus bus = Bus.register(academyId, "2호차", "34나5678", 45, 1, 1, 43);
        entityManager.persist(bus);
        entityManager.flush();
        entityManager.clear();

        Bus found = entityManager.find(Bus.class, bus.getId());

        assertThat(found.getAcademyId()).isEqualTo(academyId);
        assertThat(found.isOperable()).isTrue();
        assertThat(found.getStudentCapacity()).isEqualTo(43);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void manager_가_등록되고_계정_연결_전에는_account_id가_null이며_근무시간_jsonb가_보존된다() {
        Long academyId = insertAcademy();

        Manager manager = Manager.register(academyId, "김기사", "010-1234-5678", ManagerRole.DRIVER,
                Map.of("mon", Map.of("start", "07:00", "end", "09:00")));
        entityManager.persist(manager);
        entityManager.flush();
        entityManager.clear();

        Manager found = entityManager.find(Manager.class, manager.getId());

        assertThat(found.getRole()).isEqualTo(ManagerRole.DRIVER);
        assertThat(found.getAccountId()).as("가입 승인 전에는 계정 미연결(AUTH-11)").isNull();
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getWorkHours()).containsKey("mon");
    }

    @Test
    void schedule_이_저장되고_조회된다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);

        Schedule schedule = Schedule.register(academyId, busId, Weekday.MON, Direction.TO_ACADEMY,
                LocalTime.of(7, 30), "정문", "학원", 40);
        entityManager.persist(schedule);
        entityManager.flush();
        entityManager.clear();

        Schedule found = entityManager.find(Schedule.class, schedule.getId());

        assertThat(found.getWeekday()).isEqualTo(Weekday.MON);
        assertThat(found.getDirection()).isEqualTo(Direction.TO_ACADEMY);
        assertThat(found.getDepartTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(found.isActive()).isTrue();
    }

    @Test
    void route_와_route_stop_이_저장된다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Long stopId = insertStop(academyId);

        Route route = Route.register(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, "1노선");
        entityManager.persist(route);
        entityManager.flush();

        RouteStop routeStop = RouteStop.forRoute(route.getId(), stopId, 1);
        entityManager.persist(routeStop);
        entityManager.flush();
        entityManager.clear();

        RouteStop found = entityManager.find(RouteStop.class, routeStop.getId());

        assertThat(found.getRouteId()).isEqualTo(route.getId());
        assertThat(found.getStopId()).isEqualTo(stopId);
        assertThat(found.getSeq()).isEqualTo(1);
    }

    @Test
    void run_이_idle_상태로_생성되고_스케줄을_참조한다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Schedule schedule = Schedule.register(academyId, busId, Weekday.MON, Direction.TO_ACADEMY,
                LocalTime.of(7, 30), "정문", "학원", 40);
        entityManager.persist(schedule);
        entityManager.flush();

        Run run = insertRun(academyId, busId, schedule.getId());
        entityManager.clear();

        Run found = entityManager.find(Run.class, run.getId());

        assertThat(found.getStatus()).isEqualTo(RunStatus.IDLE);
        assertThat(found.getScheduleId()).isEqualTo(schedule.getId());
        assertThat(found.getConfirmAt()).isEqualTo(found.getDepartTime().minusMinutes(30));
        assertThat(found.isFinishPending()).isFalse();
    }

    @Test
    void run_은_스케줄_없이_임시_회차로도_생성될_수_있다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);

        Run run = insertRun(academyId, busId, null);
        entityManager.clear();

        Run found = entityManager.find(Run.class, run.getId());

        assertThat(found.getScheduleId()).as("SCH-03: 임시 추가 회차는 원본 스케줄이 없다").isNull();
    }

    @Test
    void confirmed_route_는_run_의_id를_그대로_공유하는_1대1_확장이다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Run run = insertRun(academyId, busId, null);

        ConfirmedRoute confirmedRoute = ConfirmedRoute.forRun(run.getId(), OffsetDateTime.now());
        entityManager.persist(confirmedRoute);
        entityManager.flush();
        entityManager.clear();

        ConfirmedRoute found = entityManager.find(ConfirmedRoute.class, run.getId());

        assertThat(found.getRunId()).isEqualTo(run.getId());
        assertThat(found.getCurrentVersionId())
                .as("순환 FK 상대편은 route_version 이 생기기 전이라 아직 비어 있다")
                .isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void route_version_이_confirmed_route_를_참조하고_policy_snapshot_jsonb가_보존된다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Run run = insertRun(academyId, busId, null);
        ConfirmedRoute confirmedRoute = ConfirmedRoute.forRun(run.getId(), OffsetDateTime.now());
        entityManager.persist(confirmedRoute);
        entityManager.flush();

        RouteVersion routeVersion = RouteVersion.forConfirmedRoute(confirmedRoute.getRunId(), 1,
                RouteVersionSource.CONFIRM_BATCH, 35, new BigDecimal("12.34"), OffsetDateTime.now(),
                "fingerprint-abc", "engine-v1", Map.of("noShowWaitMinutes", 3), false, null, OffsetDateTime.now());
        entityManager.persist(routeVersion);
        entityManager.flush();
        entityManager.clear();

        RouteVersion found = entityManager.find(RouteVersion.class, routeVersion.getId());

        assertThat(found.getConfirmedRouteId()).isEqualTo(run.getId());
        assertThat(found.getSource()).isEqualTo(RouteVersionSource.CONFIRM_BATCH);
        assertThat(found.getEstDistanceKm()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(found.getPolicySnapshot()).containsEntry("noShowWaitMinutes", 3);
        assertThat(found.isFallbackUsed()).isFalse();
    }

    @Test
    void run_stop_은_stop_또는_waypoint_중_정확히_하나만_가리킨다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Long stopId = insertStop(academyId);
        Run run = insertRun(academyId, busId, null);
        ConfirmedRoute confirmedRoute = ConfirmedRoute.forRun(run.getId(), OffsetDateTime.now());
        entityManager.persist(confirmedRoute);
        RouteVersion routeVersion = RouteVersion.forConfirmedRoute(confirmedRoute.getRunId(), 1,
                RouteVersionSource.CONFIRM_BATCH, null, null, null, "fingerprint-def", "engine-v1",
                Map.of(), false, null, OffsetDateTime.now());
        entityManager.persist(routeVersion);
        Waypoint waypoint = Waypoint.forRun(run.getId(), "임시집결지", null, new BigDecimal("37.500000"),
                new BigDecimal("127.000000"), null, 1L, OffsetDateTime.now());
        entityManager.persist(waypoint);
        entityManager.flush();

        RunStop viaStop = RunStop.forStop(routeVersion.getId(), stopId, 1);
        RunStop viaWaypoint = RunStop.forWaypoint(routeVersion.getId(), waypoint.getId(), 2);
        entityManager.persist(viaStop);
        entityManager.persist(viaWaypoint);
        entityManager.flush();
        entityManager.clear();

        RunStop foundStop = entityManager.find(RunStop.class, viaStop.getId());
        RunStop foundWaypoint = entityManager.find(RunStop.class, viaWaypoint.getId());

        assertThat(foundStop.getStopId()).isEqualTo(stopId);
        assertThat(foundStop.getWaypointId()).isNull();
        assertThat(foundWaypoint.getWaypointId()).isEqualTo(waypoint.getId());
        assertThat(foundWaypoint.getStopId()).isNull();
    }

    @Test
    void assignment_이_run_과_manager_를_역할별로_연결한다() {
        Long academyId = insertAcademy();
        Long busId = insertBus(academyId);
        Run run = insertRun(academyId, busId, null);
        Manager manager = Manager.register(academyId, "김기사", "010-1234-5678", ManagerRole.DRIVER, null);
        entityManager.persist(manager);
        entityManager.flush();

        Assignment assignment = Assignment.uponAssignment(run.getId(), manager.getId(), ManagerRole.DRIVER,
                OffsetDateTime.now(), null);
        entityManager.persist(assignment);
        entityManager.flush();
        entityManager.clear();

        Assignment found = entityManager.find(Assignment.class, assignment.getId());

        assertThat(found.getRunId()).isEqualTo(run.getId());
        assertThat(found.getManagerId()).isEqualTo(manager.getId());
        assertThat(found.getRole()).isEqualTo(ManagerRole.DRIVER);
        assertThat(found.getAckedAt()).as("확인 응답 전에는 비어 있다").isNull();
        assertThat(found.getAckedRouteVersionId()).isNull();
    }
}

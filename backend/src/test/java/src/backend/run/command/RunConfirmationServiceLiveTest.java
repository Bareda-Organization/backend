package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.live.DirectionsQuota;

/**
 * 확정 배치의 <b>진입점 그대로</b>(스케줄러가 부르는 {@code confirmOne(Long)}) 실 NCP API 를
 * 태운다(R18 A 목표 1·3·4). {@code NaverDirectionsClientLiveTest} 는 어댑터 한 겹만 보므로, 정작
 * "출발지가 그 노선 첫 정차지와 같은 좌표라 waypoint 로도 다시 들어가는" 상황(등원의 정상적인
 * 형태 — {@code RunConfirmationService.confirmOne} 이 origin 을 첫 정차지 좌표로 잡는다) 은
 * 여기서만 재현된다. {@code RunConfirmationServiceTest} 의 목표4 시험과 정차지 구성이 같지만,
 * 그 시험은 stub 제공자라 이 결함이 있어도 통과한다 — provider 만 naver 로 바꿔 같은 결함을 잡는다.
 *
 * <p>{@code @EnabledIf} 는 스프링 컨텍스트가 뜨기 전에 평가되어, 자격증명이 없으면 컨텍스트도
 * 만들지 않는다({@code NaverDirectionsClientLiveTest} 와 같은 방식).
 */
@SpringBootTest(properties = "app.routing.map.provider=naver")
@TestPropertySource(properties = "app.routing.map.provider=naver")
@EnabledIf("src.backend.routing.map.impl.NaverDirectionsClientLiveTest#자격증명이_있다")
@Transactional
@Tag("live")
class RunConfirmationServiceLiveTest {

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private Environment environment;

    /** 일일 한도가 바닥났으면 실패가 아니라 건너뛴다 — 다른 오류는 그대로 시험이 돌아 실패한다. */
    @BeforeEach
    void 한도가_남아_있어야_돈다() {
        DirectionsQuota.assumeQuotaLeft(environment);
    }

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 첫 정차지(시청)가 등원 origin 좌표와 같고, 그 자리에 타는 학생이 있어 정차지 목록에도
     * 다시 들어간다 — 실 NCP 가 이 형태를 400 으로 거절하던 자리다(2026-09-19 curl 실측).
     */
    @Test
    void 등원_회차를_실_지도_API로_확정해도_폴백_없이_road_path가_정차지_수보다_많다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);

        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        // 시청 → 여의도 → 강남역 — 등원(TO_ACADEMY) origin 은 confirmOne 안에서 firstStop(시청) 좌표로
        // 그대로 잡힌다(Ruling 190).
        long firstStop = fixtures.stop(academyId, "37.566500", "126.978000");
        long middleStop = fixtures.stop(academyId, "37.521900", "126.924500");
        long lastStop = fixtures.stop(academyId, "37.497900", "127.027600");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, middleStop, lastStop);

        long studentAtFirst = fixtures.student(academyId, "학생1");
        long studentAtMiddle = fixtures.student(academyId, "학생2");
        long studentAtLast = fixtures.student(academyId, "학생3");
        // 학생1 이 firstStop 에서 타 origin 과 같은 좌표가 정차지 목록에도 들어간다 — 재현의 핵심.
        fixtures.verifiedAddress(studentAtFirst, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.566500", "126.978000");
        fixtures.verifiedAddress(studentAtMiddle, middleStop, WEEKDAY, Direction.TO_ACADEMY, "37.521900",
                "126.924500");
        fixtures.verifiedAddress(studentAtLast, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.497900", "127.027600");

        OffsetDateTime departTime = OffsetDateTime.now().plusHours(3);
        long runId = fixtures.idleRun(academyId, busId, java.time.LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));

        confirmationService.confirmOne(runId);

        Long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        Boolean fallbackUsed = jdbcTemplate.queryForObject(
                "SELECT fallback_used FROM route_version WHERE id = ?", Boolean.class, versionId);
        assertThat(fallbackUsed)
                .as("origin==첫 정차지 중복을 걷어내지 않으면 NCP 가 400 으로 거절해 배치 전체가 폴백으로 떨어진다")
                .isFalse();

        Integer roadPathLength = jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(road_path) FROM route_version WHERE id = ?", Integer.class, versionId);
        assertThat(roadPathLength)
                .as("정차지 3개(origin 포함 지점 4개)보다 좌표가 많아야 도로를 따라 굽은 실측 경로다")
                .isGreaterThan(4);
    }
}

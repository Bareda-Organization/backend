package src.backend.bus.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 차량 목록의 R48 확장(API_SPEC §5.12, Ruling 816) — {@code route_count}(활성 편성 수) · {@code schedule_count}(활성 스케줄 수) ·
 * {@code today_runs[]}(오늘 미취소 회차, 출발 순). 등록·수정 응답에는 이 셋이 없다. 시계는 2030-04-01(월) 12:00 KST 로 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffBusListExtensionTest {

    private static final LocalDate TODAY = LocalDate.parse("2030-04-01");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

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

    @Test
    void 활성_편성_수와_활성_스케줄_수와_오늘_미취소_회차를_출발_순으로_싣는다() throws Exception {
        RunConfirmationFixtures fx = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long emptyBusId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long inactiveRoute = fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        스케줄을_심는다(academyId, busId, "mon", "08:00", true);
        스케줄을_심는다(academyId, busId, "tue", "08:00", true);
        스케줄을_심는다(academyId, busId, "wed", "08:00", true);
        스케줄을_심는다(academyId, busId, "thu", "08:00", false);
        fx.idleRun(academyId, busId, TODAY, Direction.FROM_ACADEMY, at(TODAY, 18), at(TODAY, 18).minusMinutes(30));
        long morning = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 8), at(TODAY, 8).minusMinutes(30));
        long canceled = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 9), at(TODAY, 9).minusMinutes(30));
        fx.idleRun(academyId, busId, TODAY.plusDays(1), Direction.TO_ACADEMY, at(TODAY.plusDays(1), 8),
                at(TODAY.plusDays(1), 8).minusMinutes(30));
        jdbcTemplate.update("UPDATE route SET active = false WHERE id = ?", inactiveRoute);
        jdbcTemplate.update("UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = ?", canceled);
        entityManager.flush();
        entityManager.clear();

        String body = mockMvc.perform(get("/api/v1/staff/buses?size=100").header("Authorization", 토큰(academyId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(항목(body, busId, "route_count")).isEqualTo(2);
        assertThat(항목(body, busId, "schedule_count")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todayRuns = (List<Map<String, Object>>) 항목(body, busId, "today_runs");
        assertThat(todayRuns).as("오늘 미취소 회차만 — 취소·내일 회차는 뺀다").hasSize(2);
        assertThat(todayRuns.get(0).get("run_id")).isEqualTo(String.valueOf(morning));
        assertThat(todayRuns.get(0).get("direction")).as("출발 순").isEqualTo("to_academy");
        assertThat(todayRuns.get(0).get("status")).isEqualTo("idle");
        assertThat(todayRuns.get(0).get("depart_time")).isNotNull();
        assertThat(todayRuns.get(1).get("direction")).isEqualTo("from_academy");
        assertThat(항목(body, emptyBusId, "route_count")).isEqualTo(0);
        assertThat(항목(body, emptyBusId, "schedule_count")).isEqualTo(0);
        assertThat(항목(body, emptyBusId, "today_runs")).isEqualTo(List.of());
    }

    private Object 항목(String body, long busId, String field) {
        List<Object> values = JsonPath.read(body, "$.data.items[?(@.id=='" + busId + "')]." + field);
        assertThat(values).as("차량 %d 의 %s", busId, field).hasSize(1);
        return values.get(0);
    }

    @Test
    void 등록과_수정_응답에는_세_필드가_없다() throws Exception {
        RunConfirmationFixtures fx = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/staff/buses").header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bus_no\":\"R48-등록\",\"plate_no\":\"12가3456\",\"capacity\":20,\"operable\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.route_count").doesNotExist())
                .andExpect(jsonPath("$.data.schedule_count").doesNotExist())
                .andExpect(jsonPath("$.data.today_runs").doesNotExist());
        mockMvc.perform(patch("/api/v1/staff/buses/" + busId).header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"operable\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.route_count").doesNotExist())
                .andExpect(jsonPath("$.data.schedule_count").doesNotExist())
                .andExpect(jsonPath("$.data.today_runs").doesNotExist());
    }

    private void 스케줄을_심는다(long academyId, long busId, String weekday, String departTime, boolean active) {
        jdbcTemplate.update("INSERT INTO schedule (academy_id, bus_id, weekday, direction, depart_time, origin_name, "
                + "destination_name, active) VALUES (?, ?, ?, 'to_academy', ?::time, '출발', '도착', ?)", academyId, busId,
                weekday, departTime, active);
    }

    private OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(ZoneOffset.ofHours(9));
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}

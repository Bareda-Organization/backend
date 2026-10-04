package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 퇴원 미리보기 {@code GET /staff/students/{id}/withdrawal-preview}(API_SPEC §5.11, Ruling 815) — 오늘·내일 미취소·미종료
 * 회차 중 그 학생이 탑승자인 회차만 싣고(확정 뒤는 {@code run_rider}, 확정 전은 §5.4 와 같은 예정 명단 계산), 아무것도 바꾸지
 * 않는다. 시계는 2030-04-01(월) 12:00 KST 로 고정돼 오늘은 월요일·내일은 화요일이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffStudentWithdrawalPreviewTest {

    private static final LocalDate TODAY = LocalDate.parse("2030-04-01");

    private static final LocalDate TOMORROW = LocalDate.parse("2030-04-02");

    private static final LocalDate AFTER_TOMORROW = LocalDate.parse("2030-04-03");

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

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    /** 확정 뒤 오늘 회차는 {@code run_rider}, 확정 전 내일 회차는 예정 명단으로 실리고 취소·종료·모레 회차와 남의 회차는 빠진다. */
    @Test
    void 오늘_내일_미취소_미종료_회차_중_탑승자인_회차만_싣는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        long otherStopId = fx.stop(academyId, "37.510000", "127.010000");
        for (Weekday weekday : List.of(Weekday.MON, Weekday.TUE, Weekday.WED)) {
            fx.route(academyId, busId, weekday, Direction.TO_ACADEMY, stopId);
        }
        long studentId = fx.student(academyId, "퇴원예정");
        long otherStudentId = fx.student(academyId, "다른학생");
        for (Weekday weekday : List.of(Weekday.MON, Weekday.TUE, Weekday.WED)) {
            fx.verifiedAddress(studentId, stopId, weekday, Direction.TO_ACADEMY, "37.500000", "127.000000");
        }
        fx.verifiedAddress(otherStudentId, otherStopId, Weekday.MON, Direction.TO_ACADEMY, "37.510000", "127.010000");

        long todayRun = fx.confirmedRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 8), at(TODAY, 8).minusMinutes(30));
        long canceledRun = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 9), at(TODAY, 9).minusMinutes(30));
        long finishedRun = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 10), at(TODAY, 10).minusMinutes(30));
        long tomorrowRun = fx.idleRun(academyId, busId, TOMORROW, Direction.TO_ACADEMY, at(TOMORROW, 8), at(TOMORROW, 8).minusMinutes(30));
        long afterTomorrowRun = fx.idleRun(academyId, busId, AFTER_TOMORROW, Direction.TO_ACADEMY, at(AFTER_TOMORROW, 8),
                at(AFTER_TOMORROW, 8).minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = ?", canceledRun);
        jdbcTemplate.update("UPDATE run SET status = 'finished' WHERE id = ?", finishedRun);
        // JDBC 로 바꾼 값이 같은 트랜잭션의 JPA 캐시에 가려지지 않게 비운다
        entityManager.flush();
        entityManager.clear();
        String token = 토큰(academyId);

        String body = 본문(mockMvc.perform(get("/api/v1/staff/students/" + studentId + "/withdrawal-preview")
                .header("Authorization", token)).andExpect(status().isOk()).andReturn());

        assertThat(JsonPath.<List<String>>read(body, "$.data.today_runs[*].run_id")).containsExactly(String.valueOf(todayRun));
        assertThat(JsonPath.<List<String>>read(body, "$.data.tomorrow_runs[*].run_id")).containsExactly(String.valueOf(tomorrowRun));
        assertThat((String) JsonPath.read(body, "$.data.today_runs[0].status")).isEqualTo("confirmed");
        assertThat((String) JsonPath.read(body, "$.data.today_runs[0].direction")).isEqualTo("to_academy");
        assertThat((String) JsonPath.read(body, "$.data.today_runs[0].stop_name")).isEqualTo("정차지37.500000");
        assertThat((String) JsonPath.read(body, "$.data.today_runs[0].bus_no")).isNotBlank();
        assertThat(OffsetDateTime.parse(JsonPath.read(body, "$.data.today_runs[0].depart_time")).toInstant())
                .isEqualTo(at(TODAY, 8).toInstant());
        assertThat((String) JsonPath.read(body, "$.data.tomorrow_runs[0].status")).as("확정 전 회차는 예정 명단").isEqualTo("idle");
        assertThat((String) JsonPath.read(body, "$.data.tomorrow_runs[0].stop_name")).as("예정 승하차지").isEqualTo("정차지37.500000");
        assertThat(afterTomorrowRun).isNotEqualTo(tomorrowRun);

        // 다른 승하차지에 서는 학생은 그 회차(같은 버스) 탑승자가 아니라 미리보기가 비어 있다.
        mockMvc.perform(get("/api/v1/staff/students/" + otherStudentId + "/withdrawal-preview")
                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tomorrow_runs.length()").value(0));
    }

    /** 미리보기는 아무것도 바꾸지 않는다 — 학생은 재원 그대로이고 두 번 불러도 같은 답이다. */
    @Test
    void 미리보기는_아무것도_바꾸지_않는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "그대로");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.confirmedRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 8), at(TODAY, 8).minusMinutes(30));
        int ridersBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM run_rider", Integer.class);
        String token = 토큰(academyId);

        String first = 본문(mockMvc.perform(get("/api/v1/staff/students/" + studentId + "/withdrawal-preview")
                .header("Authorization", token)).andExpect(status().isOk()).andReturn());
        String second = 본문(mockMvc.perform(get("/api/v1/staff/students/" + studentId + "/withdrawal-preview")
                .header("Authorization", token)).andExpect(status().isOk()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM student WHERE id = ?", Object.class, studentId))
                .as("퇴원 시각이 채워지면 안 된다").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_rider", Integer.class)).isEqualTo(ridersBefore);
    }

    /** 남의 학원 학생·퇴원생·없는 학생은 상세와 같이 {@code 404 STUDENT_NOT_FOUND} 다. */
    @Test
    void 남의_학원_학생과_퇴원생과_없는_학생은_404다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long otherAcademyId = fx.academyWithCoordinates();
        long mine = fx.student(academyId, "내학생");
        long others = fx.student(otherAcademyId, "남의학생");
        long withdrawn = fx.student(academyId, "퇴원생");
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", withdrawn);
        String token = 토큰(academyId);

        for (long studentId : new long[] {others, withdrawn, 999_999_999L}) {
            mockMvc.perform(get("/api/v1/staff/students/" + studentId + "/withdrawal-preview")
                    .header("Authorization", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
        }
        mockMvc.perform(get("/api/v1/staff/students/" + mine + "/withdrawal-preview").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today_runs.length()").value(0))
                .andExpect(jsonPath("$.data.tomorrow_runs.length()").value(0));
    }

    private OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(java.time.ZoneOffset.ofHours(9));
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }
}

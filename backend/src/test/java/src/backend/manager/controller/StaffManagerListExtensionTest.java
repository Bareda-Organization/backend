package src.backend.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
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
 * 매니저 목록의 R48 확장(API_SPEC §5.13, Ruling 817) — {@code assigned_run_count} 가 삭제 차단({@code 409 MANAGER_ASSIGNED})과
 * <b>같은 판정</b>(취소·종료되지 않고, 운행 중이거나 운행일이 오늘 이후인 회차의 배치)을 쓰는지, {@code assignments[]} 가 오늘·내일
 * 미취소 회차만 싣는지, {@code assigned_today} 쿼리와 최상위 {@code counts} 가 맞는지를 본다. 시계는 2030-04-01(월) 12:00 KST 로
 * 고정이고, 시험이 만든 새 학원 안에서 센다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffManagerListExtensionTest {

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

    /** 만들어진 시나리오 — 매니저 이름 → 매니저 id. */
    private record World(long academyId, Map<String, Long> managers) {
    }

    /**
     * 배치 판정 경계 7종 — 오늘 · 취소 · 지난 날 종료 · 먼 미래 · 지난 날 운행 중 · 내일 · 배치 없음. {@code assigned_run_count} 가
     * 0 보다 클 때만 삭제가 {@code 409} 인 것까지 같은 시험에서 확인한다(두 판정이 갈리지 않는다).
     */
    private World 세계를_만든다() {
        Phase9RosterFixtures fx = new Phase9RosterFixtures(
                new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                        stopRepository, studentRepository, weeklyAddressRepository, runRepository),
                managerRepository, accountRepository, assignmentRepository, guardianRepository,
                guardianStudentRepository, confirmationService);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long today = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 8), at(TODAY, 8).minusMinutes(30));
        long canceled = fx.idleRun(academyId, busId, TODAY, Direction.TO_ACADEMY, at(TODAY, 9), at(TODAY, 9).minusMinutes(30));
        long finishedPast = fx.idleRun(academyId, busId, TODAY.minusDays(2), Direction.TO_ACADEMY, at(TODAY.minusDays(2), 8),
                at(TODAY.minusDays(2), 8).minusMinutes(30));
        long future = fx.idleRun(academyId, busId, TODAY.plusDays(9), Direction.TO_ACADEMY, at(TODAY.plusDays(9), 8),
                at(TODAY.plusDays(9), 8).minusMinutes(30));
        long movingPast = fx.idleRun(academyId, busId, TODAY.minusDays(2), Direction.FROM_ACADEMY,
                at(TODAY.minusDays(2), 18), at(TODAY.minusDays(2), 18).minusMinutes(30));
        long tomorrow = fx.idleRun(academyId, busId, TODAY.plusDays(1), Direction.TO_ACADEMY, at(TODAY.plusDays(1), 8),
                at(TODAY.plusDays(1), 8).minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = ?", canceled);
        jdbcTemplate.update("UPDATE run SET status = 'finished' WHERE id = ?", finishedPast);
        jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", movingPast);

        Map<String, Long> managers = new java.util.LinkedHashMap<>();
        String[][] cases = {{"오늘배치", "today"}, {"취소배치", "canceled"}, {"종료배치", "finished"}, {"미래배치", "future"},
                {"운행중배치", "moving"}, {"내일배치", "tomorrow"}, {"배치없음", "none"}};
        long[] runs = {today, canceled, finishedPast, future, movingPast, tomorrow, 0};
        for (int i = 0; i < cases.length; i++) {
            long managerId = fx.manager(academyId, ManagerRole.DRIVER, cases[i][0]).managerId();
            managers.put(cases[i][0], managerId);
            if (runs[i] != 0) {
                fx.assign(runs[i], managerId, ManagerRole.DRIVER);
            }
        }
        entityManager.flush();
        entityManager.clear();
        return new World(academyId, managers);
    }

    @Test
    void assigned_run_count_는_삭제_차단과_같은_판정이고_0이_아니면_삭제가_409다() throws Exception {
        World world = 세계를_만든다();
        String body = 목록(world.academyId(), "size=100");

        Map<String, Integer> expected = Map.of("오늘배치", 1, "취소배치", 0, "종료배치", 0, "미래배치", 1, "운행중배치", 1, "내일배치", 1,
                "배치없음", 0);
        for (var entry : expected.entrySet()) {
            assertThat(항목(body, entry.getKey(), "assigned_run_count")).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        for (var entry : expected.entrySet()) {
            var request = delete("/api/v1/staff/managers/" + world.managers().get(entry.getKey()))
                    .header("Authorization", 토큰(world.academyId()));
            if (entry.getValue() > 0) {
                mockMvc.perform(request).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("MANAGER_ASSIGNED"));
            } else {
                mockMvc.perform(request).andExpect(status().isNoContent());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignments_는_오늘_내일_미취소_회차의_배치만_날짜_출발_순으로_싣는다() throws Exception {
        World world = 세계를_만든다();
        String body = 목록(world.academyId(), "size=100");

        List<Map<String, Object>> today = (List<Map<String, Object>>) 항목(body, "오늘배치", "assignments");
        assertThat(today).hasSize(1);
        assertThat(today.get(0).get("service_date")).isEqualTo("2030-04-01");
        assertThat(today.get(0).get("direction")).isEqualTo("to_academy");
        assertThat(today.get(0).get("status")).isEqualTo("idle");
        assertThat(today.get(0).get("bus_no")).isNotNull();
        assertThat(today.get(0).get("depart_time")).isNotNull();
        assertThat(today.get(0).get("run_id")).isNotNull();
        assertThat((List<Object>) 항목(body, "내일배치", "assignments")).hasSize(1);
        assertThat(((List<Map<String, Object>>) 항목(body, "내일배치", "assignments")).get(0).get("service_date"))
                .isEqualTo("2030-04-02");
        assertThat((List<Object>) 항목(body, "취소배치", "assignments")).as("취소 회차 제외").isEmpty();
        assertThat((List<Object>) 항목(body, "미래배치", "assignments")).as("모레 이후는 오늘·내일이 아니다").isEmpty();
        assertThat((List<Object>) 항목(body, "종료배치", "assignments")).isEmpty();
        assertThat((List<Object>) 항목(body, "배치없음", "assignments")).isEmpty();
    }

    @Test
    void assigned_today_쿼리는_오늘_미취소_회차_배치_유무로_좁히고_counts_는_쿼리와_쪽에_무관하다() throws Exception {
        World world = 세계를_만든다();

        assertThat(이름들(world.academyId(), "assigned_today=true&size=100")).containsExactly("오늘배치");
        assertThat(이름들(world.academyId(), "assigned_today=false&size=100"))
                .containsExactlyInAnyOrder("취소배치", "종료배치", "미래배치", "운행중배치", "내일배치", "배치없음");
        String filtered = 목록(world.academyId(), "assigned_today=true&q=오늘&page=0&size=1");
        assertThat((int) JsonPath.read(filtered, "$.data.total_count")).isEqualTo(1);
        assertThat((int) JsonPath.read(filtered, "$.data.counts.assigned_today")).as("재직 매니저 중 오늘 배치 유무별 수").isEqualTo(1);
        assertThat((int) JsonPath.read(filtered, "$.data.counts.unassigned_today")).isEqualTo(6);
        mockMvc.perform(get("/api/v1/staff/managers").param("assigned_today", "abc")
                .header("Authorization", 토큰(world.academyId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 목록(long academyId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/managers?" + query).header("Authorization", 토큰(academyId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> 이름들(long academyId, String query) throws Exception {
        return JsonPath.read(목록(academyId, query), "$.data.items[*].name");
    }

    private Object 항목(String body, String name, String field) {
        List<Object> values = JsonPath.read(body, "$.data.items[?(@.name=='" + name + "')]." + field);
        assertThat(values).as("%s 의 %s", name, field).hasSize(1);
        return values.get(0);
    }

    private OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(ZoneOffset.ofHours(9));
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}

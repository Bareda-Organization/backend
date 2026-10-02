package src.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.request.PageParams;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 운행일이 지난 이동 중 회차 — 메인 관리자의 목록(API_SPEC §6.16)과 강제 종료(§6.17), R47 {@code Ruling 724}.
 *
 * <p>고정 시계가 2030-04-01 이라 "어제" 는 03-31, 처리 대상에서 빠지는 가장 이른 운행일 경계는 03-31 이다 —
 * 대상은 운행일이 <b>03-30 이하</b>인 미취소 {@code moving} 회차다. 목록은 남이 만든 행이 섞여도 흔들리지 않게
 * 이 시험이 만든 회차 id 의 있고 없음으로 판정하고, 경보 지표와의 일치는 같은 상태에서 센 두 값을 맞대 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
@Import(FixedClock20300401Config.class)
class AdminStaleMovingRunControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 4, 1);

    private static final String LIST = "/api/v1/admin/runs/stale-moving";

    private static final String FORCE_FINISH = "/api/v1/admin/runs/%d/force-finish";

    private static final String REASON_BODY = "{\"reason\":\"운행일이 지난 채 끝나지 않아 콘솔에서 종료\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private MovingRunWindowPolicy movingRunWindowPolicy;

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
    private RunRiderRepository runRiderRepository;

    private RunConfirmationFixtures fixtures;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    /** 그 운행일 · 그 상태의 회차 — 차량을 회차마다 새로 만들어 {@code uk_run_bus_date_direction_depart} 와 겹치지 않는다. */
    private long run(long academyId, LocalDate serviceDate, String status) {
        long busId = fixtures().bus(academyId);
        OffsetDateTime departTime = serviceDate.atTime(18, 0).atOffset(OffsetDateTime.now(clock).getOffset());
        long runId = fixtures().idleRun(academyId, busId, serviceDate, Direction.FROM_ACADEMY, departTime,
                departTime.minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET status = ?, started_at = ?, finished_at = ? WHERE id = ?", status,
                "idle".equals(status) ? null : java.sql.Timestamp.from(departTime.toInstant()),
                "finished".equals(status) ? java.sql.Timestamp.from(departTime.plusHours(1).toInstant()) : null,
                runId);
        return runId;
    }

    private long staleRun(long academyId) {
        return run(academyId, TODAY.minusDays(2), "moving");
    }

    private void riders(long academyId, long runId, RiderStatus... statuses) {
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        for (RiderStatus riderStatus : statuses) {
            RunRider rider = RunRider.uponConfirmation(runId, fixtures().student(academyId, "학생"), stopId);
            switch (riderStatus) {
                case BOARDED -> rider.board(OffsetDateTime.now(clock));
                case ALIGHTED -> rider.alight(OffsetDateTime.now(clock));
                default -> { /* WAITING — 생성 직후 기본값 그대로 */ }
            }
            runRiderRepository.save(rider);
        }
        runRiderRepository.flush();
    }

    private String runStatus(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);
    }

    private List<Long> listedRunIds() throws Exception {
        String body = mockMvc.perform(get(LIST).header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
        List<Object> raw = json.read("$.data.items[*].run_id");
        return raw.stream().map(id -> Long.valueOf(id.toString())).toList();
    }

    @Test
    @DisplayName("목록 — 운행일 오늘−2 는 나오고 오늘−1·오늘은 빠지며, 취소·idle·finished 도 빠지고, 학원이 달라도 전부 나온다")
    void 목록은_경계_바깥의_회차만_싣는다() throws Exception {
        long academyA = fixtures().academyWithCoordinates();
        long academyB = fixtures().academyWithCoordinates();
        long oldest = run(academyA, TODAY.minusDays(5), "moving");
        long dayMinus2 = staleRun(academyB);
        long dayMinus1 = run(academyA, TODAY.minusDays(1), "moving");
        long today = run(academyA, TODAY, "moving");
        long canceled = staleRun(academyA);
        jdbcTemplate.update("UPDATE run SET canceled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(OffsetDateTime.now(clock).toInstant()), canceled);
        long idle = run(academyA, TODAY.minusDays(2), "idle");
        long finished = run(academyA, TODAY.minusDays(2), "finished");

        List<Long> listed = listedRunIds();

        assertThat(listed).contains(oldest, dayMinus2).doesNotContain(dayMinus1, today, canceled, idle, finished);
        assertThat(listed.indexOf(oldest)).as("운행일 오름차순 — 오래된 회차가 먼저").isLessThan(listed.indexOf(dayMinus2));
    }

    @Test
    @DisplayName("목록 — 페이징이 없어 상한(200건)에서 자르고, 운행일이 가장 오래된 회차부터 남긴다(BR-317)")
    void 목록은_상한_건만_돌려주고_오래된_회차부터_남긴다() throws Exception {
        long academy = fixtures().academyWithCoordinates();
        // 오래된 날짜(오늘−10)로 상한 건수를 채우고, 상한을 넘는 1건만 더 최근(오늘−3)으로 둔다 — 잘리는 쪽이 새 회차여야 한다.
        // 남이 만든 행이 섞여도 총 건수가 상한 이상이면 길이는 같고, 가장 최근 회차는 어느 경우에도 잘려 나간다.
        for (int i = 0; i < PageParams.UNPAGED_LIST_MAX; i++) {
            run(academy, TODAY.minusDays(10), "moving");
        }
        long newestOverLimit = run(academy, TODAY.minusDays(3), "moving");

        List<Long> listed = listedRunIds();

        assertThat(listed).as("상한 건만 싣는다").hasSize(PageParams.UNPAGED_LIST_MAX);
        assertThat(listed).as("상한을 넘는 가장 최근 회차는 잘린다 — 오래된 회차부터 남긴다").doesNotContain(newestOverLimit);
    }

    @Test
    @DisplayName("목록 — 항목 필드와 아직 boarded 인 탑승자 수(alighted·대기는 세지 않음)")
    void 목록_항목은_남은_탑승자_수를_정확히_싣는다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long runId = staleRun(academyId);
        jdbcTemplate.update("UPDATE run SET finish_pending = true WHERE id = ?", runId);
        riders(academyId, runId, RiderStatus.BOARDED, RiderStatus.BOARDED, RiderStatus.ALIGHTED, RiderStatus.WAITING);

        mockMvc.perform(get(LIST).header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].boarded_count".formatted(runId)).value(2))
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].academy_id".formatted(runId)).value(String.valueOf(academyId)))
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].academy_name".formatted(runId)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].service_date".formatted(runId))
                        .value("2030-03-30"))
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].direction".formatted(runId))
                        .value("from_academy"))
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].bus_no".formatted(runId)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].started_at".formatted(runId)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.run_id == %d)].finish_pending".formatted(runId)).value(true));
    }

    @Test
    @DisplayName("목록 건수는 StaleMovingRun 경보 지표(countStaleMoving)와 같은 회차 수다")
    void 목록_건수는_경보_지표와_같다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        staleRun(academyId);
        run(academyId, TODAY.minusDays(1), "moving");

        assertThat(listedRunIds()).hasSize((int) runRepository.countStaleMoving(
                movingRunWindowPolicy.earliestServiceDate()));
    }

    @Test
    @DisplayName("강제 종료 — finished·finished_at·finish_pending=false · 감사 1행 · 알림 0 · 탑승자 상태 그대로 · 종료 이벤트 없음")
    void 강제_종료하면_회차만_끝나고_알림과_탑승자는_그대로다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long runId = staleRun(academyId);
        jdbcTemplate.update("UPDATE run SET finish_pending = true WHERE id = ?", runId);
        riders(academyId, runId, RiderStatus.BOARDED, RiderStatus.WAITING);

        mockMvc.perform(post(FORCE_FINISH.formatted(runId))
                        .header("Authorization", 메인관리자_토큰()).header("X-Real-IP", "203.0.113.24")
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(runId))
                .andExpect(jsonPath("$.data.finished_at").isNotEmpty())
                .andExpect(jsonPath("$.data.boarded_count").value(1));

        assertThat(runStatus(runId)).isEqualTo("finished");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT finished_at, finish_pending FROM run WHERE id = ?", runId);
        assertThat(row.get("finished_at")).as("finished_at 은 종료한 시각으로 채워진다").isNotNull();
        assertThat(row.get("finish_pending")).isEqualTo(false);

        assertThat(jdbcTemplate.queryForList("SELECT status FROM run_rider WHERE run_id = ? ORDER BY id",
                String.class, runId)).as("탑승자 상태는 건드리지 않는다 — 하차 시각을 지어낼 수 없다")
                .containsExactly("boarded", "waiting");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_log WHERE run_id = ?",
                Integer.class, runId)).as("지난 운행이라 학부모·관계자 알림을 만들지 않는다").isZero();
        assertThat(applicationEvents.stream(RunEndedEvent.class)).as("종료 알림·방송의 재료인 이벤트도 내지 않는다")
                .isEmpty();

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT category, action, target_type, academy_id, actor_account_id, ip, "
                        + "detail->>'action' AS detail_action, detail->>'reason' AS reason, "
                        + "detail->>'boarded_count' AS boarded FROM audit_log "
                        + "WHERE target_type = 'run' AND target_id = ?", runId);
        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit.get("category")).isEqualTo("data_access");
        assertThat(audit.get("action")).isEqualTo("update");
        assertThat(audit.get("detail_action")).isEqualTo("run.force_finish");
        assertThat(audit.get("reason")).isEqualTo("운행일이 지난 채 끝나지 않아 콘솔에서 종료");
        assertThat(audit.get("boarded")).as("감사에 남은 탑승자 수").isEqualTo("1");
        assertThat(audit.get("academy_id")).isEqualTo(academyId);
        assertThat(audit.get("actor_account_id")).isEqualTo(1L);
        assertThat(audit.get("ip")).hasToString("203.0.113.24");
    }

    @Test
    @DisplayName("강제 종료 — 운행일이 오늘·어제인 회차는 409 RUN_NOT_STALE 이고 그대로 moving 이다")
    void 오늘과_어제_회차는_강제_종료할_수_없다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long yesterday = run(academyId, TODAY.minusDays(1), "moving");
        long today = run(academyId, TODAY, "moving");

        for (long runId : new long[] {yesterday, today}) {
            mockMvc.perform(post(FORCE_FINISH.formatted(runId))
                            .header("Authorization", 메인관리자_토큰())
                            .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RUN_NOT_STALE"));
            assertThat(runStatus(runId)).isEqualTo("moving");
        }
    }

    @Test
    @DisplayName("강제 종료 — 이동 중이 아니면 409 RUN_NOT_MOVING · 취소된 회차는 409 RUN_CANCELED · 없는 회차는 404")
    void 전제를_어긴_강제_종료는_거절된다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long idle = run(academyId, TODAY.minusDays(2), "idle");
        long finished = run(academyId, TODAY.minusDays(2), "finished");
        long canceled = staleRun(academyId);
        jdbcTemplate.update("UPDATE run SET canceled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(OffsetDateTime.now(clock).toInstant()), canceled);

        for (long runId : new long[] {idle, finished}) {
            mockMvc.perform(post(FORCE_FINISH.formatted(runId))
                            .header("Authorization", 메인관리자_토큰())
                            .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));
        }
        mockMvc.perform(post(FORCE_FINISH.formatted(canceled))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CANCELED"));
        assertThat(runStatus(canceled)).isEqualTo("moving");
        mockMvc.perform(post(FORCE_FINISH.formatted(Long.MAX_VALUE))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'run' AND detail->>'action' = 'run.force_finish'",
                Integer.class)).as("거절된 요청은 감사 행을 남기지 않는다").isZero();
    }

    @Test
    @DisplayName("강제 종료 — 사유가 공백이면 422 이고 회차는 그대로다")
    void 사유가_공백이면_422_다() throws Exception {
        long runId = staleRun(fixtures().academyWithCoordinates());

        mockMvc.perform(post(FORCE_FINISH.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(runStatus(runId)).isEqualTo("moving");
    }

    @Test
    @DisplayName("메인 관리자 외 역할은 목록·강제 종료 둘 다 403 이고 회차는 그대로다")
    void 메인_관리자_외에는_403_이다() throws Exception {
        long runId = staleRun(fixtures().academyWithCoordinates());

        mockMvc.perform(get(LIST).header("Authorization", 관계자_토큰())).andExpect(status().isForbidden());
        mockMvc.perform(post(FORCE_FINISH.formatted(runId))
                        .header("Authorization", 관계자_토큰())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON_BODY))
                .andExpect(status().isForbidden());
        assertThat(runStatus(runId)).isEqualTo("moving");
    }
}

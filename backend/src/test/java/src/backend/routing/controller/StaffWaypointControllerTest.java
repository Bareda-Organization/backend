package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
import src.backend.notification.command.RunRouteConfirmedNotificationListener;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.15 {@code POST/DELETE /staff/runs/{runId}/waypoints}(RTE-10).
 *
 * <p><b>이 클래스의 최우선 단언은 {@code apply} 가 실제로 미리보기와 배포를 가르는가</b>다 —
 * {@code apply=false} 는 {@code confirmed_route}·{@code route_version} 을 건드리지 않아야 하고,
 * {@code apply=true} 라야 새 버전이 배포되고 {@code route_changed} 알림이 나간다(목표 11). 두 번째
 * 축은 이미 배포된 경유 지점이 재최적화를 다시 돌려도 <b>자리를 지키는가</b>다(목표 7) — 아니면 매번
 * 추가·삭제할 때마다 앞서 고정한 지점이 흔들려 "고정" 의 의미가 없다.
 *
 * <p>{@code SERVICE_DATE} 를 먼 미래(2030년 월요일)로 두어 실제 시계로도 항상 ①·②구간(허용)이
 * 되게 한다 — 운행 시작 후(③구간) 403 만 별도로 상태를 직접 바꿔 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffWaypointControllerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RunConfirmationService confirmationService;

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

    @MockitoSpyBean
    private RouteComputationPipeline pipeline;

    @MockitoSpyBean
    private RunRouteConfirmedNotificationListener routeChangedListener;

    private RunConfirmationFixtures fixtures;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    // ── 목표 11 — apply 가 미리보기·배포를 가른다 ─────────────────────────

    /** {@code apply=false} 는 미리보기만 계산하고 확정 노선(현재 버전·버전 개수)은 그대로다. */
    @Test
    void 미리보기만_요청하면_확정_노선이_그대로다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        long beforeVersionId = 현재_버전_id(s.runId);
        int beforeVersionCount = 버전_개수(s.runId);

        경유_추가한다(s.runId, 경유_본문("새경유로 10", "임시 정류장", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waypoint_id").isNumber())
                .andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.route_preview.stops_after", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.data.route_preview.stops_after[3].stop_name")
                        .value("임시 정류장"));

        assertThat(현재_버전_id(s.runId)).as("미리보기는 현재 버전 포인터를 옮기면 안 된다").isEqualTo(beforeVersionId);
        assertThat(버전_개수(s.runId)).as("미리보기는 새 버전 행을 만들면 안 된다").isEqualTo(beforeVersionCount);
        verify(routeChangedListener, times(0)).appendRouteChanged(any());
    }

    /** {@code apply=true} 는 새 노선 버전을 배포하고(+1), {@code route_changed} 를 한 번 발행한다. */
    @Test
    void 배포하면_노선_버전이_올라가고_route_changed_가_발행된다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        int beforeVersionNo = 현재_버전_번호(s.runId);

        MvcResult result = 경유_추가한다(s.runId, 경유_본문("새경유로 20", "긴급 정류장", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andReturn();
        long waypointId = waypointId아이디_읽는다(result);
        entityManager.flush();

        assertThat(현재_버전_번호(s.runId)).as("배포는 버전 번호를 1 올려야 한다").isEqualTo(beforeVersionNo + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT applied FROM waypoint WHERE id = ?", Boolean.class, waypointId))
                .isTrue();
        assertThat(현재_버전에_경유_지점이_포함됐나(s.runId, waypointId))
                .as("버전 번호만 오르고 그 지점이 실제 노선(run_stop)에 안 들어가면 안 된다")
                .isTrue();
        verify(routeChangedListener, times(1)).appendRouteChanged(any());
    }

    /**
     * 배포하지 않은(미리보기 단계) 경유 지점 행은 호출마다 새로 쌓이면 안 된다 — 관계자가 라벨·주소를
     * 바꿔 가며 미리보기를 여러 번 눌러 보는 것이 정상 사용 흐름이라, 매번 새 행을 남기면 배포되지
     * 않는 고아 행이 무한히 누적된다.
     */
    @Test
    void 같은_회차에_미리보기를_두_번_호출해도_미배포_행이_누적되지_않는다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        경유_추가한다(s.runId, 경유_본문("새경유로 60", "첫 미리보기", false))
                .andExpect(status().isOk());
        경유_추가한다(s.runId, 경유_본문("새경유로 61", "둘째 미리보기", false))
                .andExpect(status().isOk());

        assertThat(미배포_행_개수(s.runId))
                .as("이전 미리보기 행은 다음 미리보기 호출이 대체해야 한다 — 그대로 두면 고아 행이 계속 쌓인다")
                .isEqualTo(1);
    }

    // ── 목표 9 — 운행 시작 후는 403 ───────────────────────────────────────

    /** 운행 중(③구간)인 회차는 관계자 요청이라도 403 이다. */
    @Test
    void 운행_중인_회차는_403_이다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", s.runId);

        경유_추가한다(s.runId, 경유_본문("새경유로 30", "정류장", false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── DELETE 도 같은 미리보기→배포 절차 ────────────────────────────────

    /** DELETE 도 POST 와 같은 절차다 — {@code apply=false} 는 배포하지 않고, {@code apply=true} 라야 반영된다. */
    @Test
    void 삭제도_미리보기_후_배포_절차를_따른다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        MvcResult added = 경유_추가한다(s.runId, 경유_본문("새경유로 40", "임시 정류장", true))
                .andExpect(status().isOk())
                .andReturn();
        long waypointId = waypointId아이디_읽는다(added);
        clearInvocations(routeChangedListener);
        int versionNoAfterAdd = 현재_버전_번호(s.runId);

        경유_삭제한다(s.runId, waypointId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));
        assertThat(현재_버전_번호(s.runId)).as("삭제 미리보기는 버전을 올리면 안 된다").isEqualTo(versionNoAfterAdd);
        verify(routeChangedListener, times(0)).appendRouteChanged(any());

        경유_삭제한다(s.runId, waypointId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true));
        entityManager.flush();
        assertThat(현재_버전_번호(s.runId)).as("삭제 배포는 버전을 1 올려야 한다").isEqualTo(versionNoAfterAdd + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM waypoint WHERE id = ? AND removed_at IS NOT NULL", Integer.class, waypointId))
                .as("배포된 삭제는 removed_at 을 채워야 한다")
                .isEqualTo(1);
        assertThat(현재_버전에_경유_지점이_포함됐나(s.runId, waypointId))
                .as("버전 번호만 오르고 그 지점이 실제 노선(run_stop)에서 안 빠지면 안 된다")
                .isFalse();
        verify(routeChangedListener, times(1)).appendRouteChanged(any());
    }

    /** 아직 배포되지 않은(미리보기 단계) 경유 지점은 삭제 대상이 아니다 — 404 다. */
    @Test
    void 미배포_경유_지점은_삭제_대상이_아니다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        MvcResult previewOnly = 경유_추가한다(s.runId, 경유_본문("새경유로 45", "미리보기용", false))
                .andExpect(status().isOk())
                .andReturn();
        long waypointId = waypointId아이디_읽는다(previewOnly);

        경유_삭제한다(s.runId, waypointId, false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WAYPOINT_NOT_FOUND"));
    }

    /** 존재하지 않는 경유 지점을 지목하면 404 다. */
    @Test
    void 존재하지_않는_경유_지점을_삭제하면_404_이다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        경유_삭제한다(s.runId, 999_999_999L, false)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WAYPOINT_NOT_FOUND"));
    }

    // ── 검증 ─────────────────────────────────────────────────────────────

    /** 주소와 좌표가 둘 다 없으면 422 다. */
    @Test
    void 주소와_좌표가_모두_없으면_422_이다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        경유_추가한다(s.runId, 경유_본문(null, "라벨", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 목표 7 — 이미 배포된 경유 지점은 재최적화에서도 자리를 지킨다 ──────

    /**
     * 경유 지점 A 를 배포한 뒤 경유 지점 B 를 추가로 배포하면(A 입장에선 재최적화가 다시 도는 것) A 의
     * {@code run_stop.seq} 가 그대로 유지돼야 한다 — 아니면 새 지점을 배포할 때마다 이미 고정해 둔
     * 지점이 흔들린다({@code WaypointCommandService.existingFixedStopsOf}).
     */
    @Test
    void 이미_배포된_경유_지점은_재최적화에서도_순번이_유지된다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        MvcResult first = 경유_추가한다(s.runId, 경유_본문("첫경유로 10", "첫 경유지", true))
                .andExpect(status().isOk())
                .andReturn();
        long firstWaypointId = waypointId아이디_읽는다(first);
        int firstSeqAfterDeploy = 배포된_순번(s.runId, firstWaypointId);

        경유_추가한다(s.runId, 경유_본문("둘째경유로 20", "둘째 경유지", true))
                .andExpect(status().isOk());

        assertThat(배포된_순번(s.runId, firstWaypointId))
                .as("이미 배포된 경유 지점은 다음 재최적화에서도 자리를 지켜야 한다")
                .isEqualTo(firstSeqAfterDeploy);
    }

    // ── 학원 격리 ─────────────────────────────────────────────────────────

    /** 다른 학원의 회차를 지목하면 404 다(존재 여부를 드러내지 않는 관례). */
    @Test
    void 다른_학원의_회차는_404_이다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        long otherAcademyId = fixtures().academyWithCoordinates();

        mockMvc.perform(post("/api/v1/staff/runs/" + s.runId + "/waypoints")
                .header("Authorization", 토큰(otherAcademyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(경유_본문("새경유로 50", "라벨", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    /**
     * 확정 노선(정차지 3개 · 학생 3명)을 만든다 — {@code confirmOne} 은 그 자체로도
     * {@code RunRouteConfirmedEvent} 를 한 번 발행하므로, 이후 단언은 이 시나리오 준비를 지운
     * 기준선(0)에서부터 센다.
     */
    private 시나리오 확정된_회차를_만든다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures().stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures().stop(academyId, "37.564000", "126.974000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        long 학생1 = fixtures().student(academyId, "학생1");
        long 학생2 = fixtures().student(academyId, "학생2");
        long 학생3 = fixtures().student(academyId, "학생3");
        fixtures().verifiedAddress(학생1, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures().verifiedAddress(학생2, midStop, WEEKDAY, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures().verifiedAddress(학생3, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.564000", "126.974000");

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(ZoneOffset.of("+09:00"));
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);
        clearInvocations(pipeline);
        clearInvocations(routeChangedListener);

        return new 시나리오(academyId, runId);
    }

    private ResultActions 경유_추가한다(long runId, String body) throws Exception {
        long academyId = jdbcTemplate.queryForObject("SELECT academy_id FROM run WHERE id = ?", Long.class, runId);
        return mockMvc.perform(post("/api/v1/staff/runs/" + runId + "/waypoints")
                .header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 경유_삭제한다(long runId, long waypointId, boolean apply) throws Exception {
        long academyId = jdbcTemplate.queryForObject("SELECT academy_id FROM run WHERE id = ?", Long.class, runId);
        return mockMvc.perform(delete("/api/v1/staff/runs/" + runId + "/waypoints/" + waypointId + "?apply=" + apply)
                .header("Authorization", 토큰(academyId)));
    }

    private String 경유_본문(String address, String label, boolean apply) {
        StringBuilder body = new StringBuilder("{");
        if (address != null) {
            body.append("\"address\":\"").append(address).append("\",");
        }
        body.append("\"label\":\"").append(label).append("\",");
        body.append("\"apply\":").append(apply).append("}");
        return body.toString();
    }

    private long waypointId아이디_읽는다(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.waypoint_id")).longValue();
    }

    private long 현재_버전_id(long runId) {
        return jdbcTemplate.queryForObject("SELECT current_version_id FROM confirmed_route WHERE run_id = ?",
                Long.class, runId);
    }

    private int 현재_버전_번호(long runId) {
        long versionId = 현재_버전_id(runId);
        return jdbcTemplate.queryForObject("SELECT version_no FROM route_version WHERE id = ?", Integer.class,
                versionId);
    }

    private int 버전_개수(long runId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM route_version WHERE confirmed_route_id = ?",
                Integer.class, runId);
    }

    private int 배포된_순번(long runId, long waypointId) {
        long versionId = 현재_버전_id(runId);
        return jdbcTemplate.queryForObject(
                "SELECT seq FROM run_stop WHERE route_version_id = ? AND waypoint_id = ?", Integer.class, versionId,
                waypointId);
    }

    /** 버전 번호만이 아니라 <b>현재 버전의 run_stop 에 그 경유 지점이 실제로 들어갔는지</b> 확인한다. */
    private boolean 현재_버전에_경유_지점이_포함됐나(long runId, long waypointId) {
        long versionId = 현재_버전_id(runId);
        int count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run_stop WHERE route_version_id = ? AND waypoint_id = ?", Integer.class,
                versionId, waypointId);
        return count > 0;
    }

    private int 미배포_행_개수(long runId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM waypoint WHERE run_id = ? AND applied = false",
                Integer.class, runId);
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    /** 시나리오 픽스처 값 묶음. */
    private record 시나리오(long academyId, long runId) {
    }
}

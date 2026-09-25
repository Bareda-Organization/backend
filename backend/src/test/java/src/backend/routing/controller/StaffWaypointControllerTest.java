package src.backend.routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import src.backend.global.security.authz.Permissions;
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

    /**
     * `R18-C2` 목표 1·3 — §5.5 상세와 같은 이유로 §5.15 도 전/후 도로 좌표(지도용)와 노선 전체
     * 소요(분)를 실제 값으로 낸다. 경유 지점을 추가하면 정차지가 3→4 로 늘어(위
     * {@link #미리보기만_요청하면_확정_노선이_그대로다} 와 같은 시나리오) 도로 좌표열도 길어진다 —
     * before·after 를 바꿔치기해도 "둘 다 비어 있지 않다"는 통과하므로, 길이 대조가 그 함정을 잡는다
     * (`StaffApprovalControllerTest` 에서 §5.5 를 고치며 이미 한 번 밟은 함정).
     */
    @Test
    void 미리보기_응답에도_전후_도로_좌표와_소요시간이_실제로_담긴다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        MvcResult result = 경유_추가한다(s.runId, 경유_본문("새경유로 11", "임시 정류장2", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.route_preview.road_path_before",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.data.route_preview.road_path_after",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())))
                .andExpect(jsonPath("$.data.est_duration_before", org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.est_duration_after", org.hamcrest.Matchers.greaterThan(0)))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int beforeSize = JsonPath.read(body, "$.data.route_preview.road_path_before.length()");
        int afterSize = JsonPath.read(body, "$.data.route_preview.road_path_after.length()");
        assertThat(afterSize).as("정차지가 늘었으니 후 구간 도로 좌표가 더 많아야 한다").isGreaterThan(beforeSize);

        // 스텁 지도는 같은 입력에 언제나 같은 값을 낸다(StubMapRouteClientTest 확인) — 이 시나리오의
        // 좌표·주소가 고정돼 있어 아래 두 값도 고정이다. 개수·부호만 보면 값이 서로 바뀌어도(swap)
        // "둘 다 0보다 크다"는 통과하므로, 구체값으로 어느 쪽이 어느 값인지까지 고정해야 바뀌치기를 잡는다.
        // 스텁 지도는 같은 입력에 언제나 같은 값을 낸다(StubMapRouteClientTest 확인) — 이 시나리오의
        // 좌표·주소가 고정돼 있어 아래 두 값도 고정이다. "둘 다 0보다 크다"만 보면 두 값이 서로
        // 바뀌어도(swap) 통과하므로, 구체값으로 어느 쪽이 어느 값인지까지 고정해야 바뀌치기를 잡는다.
        // ⚠ 후(994분)가 전(21분)보다 훨씬 큰 것은 좌표 직접 지정이 아니라 주소 지오코딩(경유_본문의
        // "새경유로 11")을 거쳐서다 — 스텁 지오코더가 실제 좌표와 멀리 떨어진 근사값을 준 것으로
        // 보이며, 이 시나리오의 실제 지리적 타당성은 이 시험의 범위 밖이다(보고서 §2 참고).
        int beforeDuration = JsonPath.read(body, "$.data.est_duration_before");
        int afterDuration = JsonPath.read(body, "$.data.est_duration_after");
        assertThat(beforeDuration).as("확정 배치가 저장한 값(전) — route_version.est_duration_min").isEqualTo(21);
        assertThat(afterDuration).as("이 요청이 방금 계산한 값(후)").isEqualTo(994);
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

    // ── 목표 4 — 확정 노선이 아직 산출되지 않은 회차는 409 ────────────────

    /**
     * idle 회차(확정 배치가 아직 안 돈 상태)로 경유 지점을 지정하면 날것 {@code 500} 이 아니라
     * {@code 409 RUN_NOT_CONFIRMED} 다({@code WaypointCommandService.routeContextOf}). 고정 노선
     * 자체는 있으므로(픽스처가 만듦) {@code 422 ROUTE_NOT_CONFIGURED_FOR_RUN} 과는 다른 원인이다 —
     * 그 코드와 합치지 않았다는 것을 이 단언이 고정한다.
     */
    @Test
    void 확정_노선이_없는_회차는_경유_지점_지정이_409_다() throws Exception {
        시나리오 s = 미확정_회차를_만든다();

        경유_추가한다(s.runId, 경유_본문("새경유로 70", "미확정 회차 경유", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    /**
     * 이미 배포된 경유 지점이라도, 확정 노선이 사라진 뒤라면(운영상 있을 수 없지만 방어적 판정 대상)
     * 삭제(재최적화)도 같은 409 다 — {@code add}·{@code remove} 가 {@code routeContextOf} 를 공유한다.
     */
    @Test
    void 확정_노선이_사라진_회차는_경유_지점_삭제도_409_다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        MvcResult added = 경유_추가한다(s.runId, 경유_본문("새경유로 71", "배포된 경유", true))
                .andExpect(status().isOk())
                .andReturn();
        long waypointId = waypointId아이디_읽는다(added);
        jdbcTemplate.update("DELETE FROM confirmed_route WHERE run_id = ?", s.runId);

        경유_삭제한다(s.runId, waypointId, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    /** {@code 확정된_회차를_만든다} 와 같으나 {@code confirmOne} 을 부르지 않는다 — {@code confirmed_route} 자체가 없다. */
    private 시나리오 미확정_회차를_만든다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures().stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures().stop(academyId, "37.564000", "126.974000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(ZoneOffset.of("+09:00"));
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        return new 시나리오(academyId, runId);
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

    /**
     * 순번을 지정하면 그 자리에 선다(2026-09-22 사용자 지시 — "경유지 추가·삭제에 순서도 정할 수
     * 있게"). {@code FixedStop.seq} 는 원래부터 <b>최종 순번</b>이었고(엔진이 그 자리를 비워 둔다),
     * 지금까지는 호출부가 늘 "맨 뒤" 를 박아 넣어 그 자리가 닫혀 있었다.
     */
    @Test
    void 경유_지점_순번을_지정하면_그_자리에_선다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        MvcResult result = 경유_추가한다(s.runId, 순번_본문("서울시 새길로 7", "1번 뒤", 2, false))
                .andExpect(status().isOk()).andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.List<String> 변경후 = JsonPath.read(body, "$.data.route_preview.stops_after[*].stop_name");
        assertThat(변경후)
                .as("2번 자리를 요구했으면 두 번째에 서야 한다 — 맨 뒤로 밀면 지정의 뜻이 사라진다")
                .element(1).isEqualTo("1번 뒤");
    }

    /** 범위 밖 순번은 422 다 — 조용히 맨 뒤로 보내면 관계자가 지정한 자리와 다른 결과를 못 알아챈다. */
    @Test
    void 정차지_수보다_큰_순번은_거부된다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();

        경유_추가한다(s.runId, 순번_본문("서울시 새길로 7", "너무 뒤", 99, false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── BR-020 — 추가·제거 뒤에도 다른 경유 지점이 "어느 승하차지 사이" 를 지킨다 ─────────

    /** 앞 경유 지점을 지워도 맨 뒤 경유 지점의 순번이 자리 수 안으로 당겨진다 — 옛 순번 그대로면 500 이었다. */
    @Test
    void 앞_경유_지점을_지워도_맨_뒤_경유_지점은_자리를_지킨다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        long front = waypointId아이디_읽는다(경유_추가한다(s.runId, 순번_본문("앞경유로 1", "앞", 2, true))
                .andExpect(status().isOk()).andReturn());
        long back = waypointId아이디_읽는다(경유_추가한다(s.runId, 경유_본문("뒤경유로 2", "뒤", true))
                .andExpect(status().isOk()).andReturn());
        assertThat(배포된_순번(s.runId, back)).as("승하차지 3 + 경유 2 의 맨 뒤").isEqualTo(5);

        경유_삭제한다(s.runId, front, true).andExpect(status().isOk());

        assertThat(배포된_순번(s.runId, back)).isEqualTo(4);
    }

    /** 이미 경유 지점이 선 자리에 새로 지정하면 새 지점이 그 자리에 서고 기존 지점은 한 칸 뒤로 밀린다. */
    @Test
    void 경유_지점이_선_자리에_지정하면_기존_지점이_한_칸_밀린다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        경유_추가한다(s.runId, 순번_본문("기존경유로 1", "기존", 2, true)).andExpect(status().isOk());

        MvcResult result = 경유_추가한다(s.runId, 순번_본문("새경유로 2", "새", 2, false))
                .andExpect(status().isOk()).andReturn();

        java.util.List<String> 변경후 = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.route_preview.stops_after[*].stop_name");
        assertThat(변경후.subList(1, 3)).containsExactly("새", "기존");
    }

    /** 두 승하차지 사이에 둔 경유 지점은 앞 경유 지점을 지워도 그 사이에 남는다 — 옛 순번 그대로면 뒤로 밀렸다. */
    @Test
    void 앞_경유_지점을_지워도_승하차지_사이_자리가_유지된다() throws Exception {
        시나리오 s = 확정된_회차를_만든다();
        long front = waypointId아이디_읽는다(경유_추가한다(s.runId, 순번_본문("앞경유로 1", "앞", 2, true))
                .andExpect(status().isOk()).andReturn());
        long middle = waypointId아이디_읽는다(경유_추가한다(s.runId, 순번_본문("사이경유로 2", "사이", 4, true))
                .andExpect(status().isOk()).andReturn());

        경유_삭제한다(s.runId, front, true).andExpect(status().isOk());

        assertThat(배포된_순번(s.runId, middle)).as("[s1, s2, 사이, s3] — 승하차지 둘 뒤").isEqualTo(3);
    }

    // ── BR-120 — 경유 지점은 노선 편성 권한 ────────────────────────────────

    /** 경유 지점은 {@code ROUTE_MANAGE}(FEATURE_SPEC §6.2) — 스케줄 권한만 가진 주체에게는 닫혀 있어야 한다. */
    @Test
    void 스케줄_권한만으로는_경유_지점을_다룰_수_없다() throws Exception {
        var scheduleOnly = user("schedule-only").authorities(new SimpleGrantedAuthority(Permissions.SCHEDULE_MANAGE));

        mockMvc.perform(post("/api/v1/staff/runs/1/waypoints").with(scheduleOnly)
                        .contentType(MediaType.APPLICATION_JSON).content(경유_본문("권한경유로 1", "권한", false)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/staff/runs/1/waypoints/1").with(scheduleOnly))
                .andExpect(status().isForbidden());
    }

    private String 순번_본문(String address, String label, int seq, boolean apply) {
        return "{\"address\":\"%s\",\"label\":\"%s\",\"seq\":%d,\"apply\":%s}"
                .formatted(address, label, seq, apply);
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

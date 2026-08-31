package src.backend.run.navigation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * {@code GET /runs/{runId}/navigation}(API_SPEC §4.16, RUN-08 · M-09) — 목표 18~21.
 *
 * <p>상한 격리(목표 22)는 소스 수준 검사라 별도 {@code NavigationCapConventionTest} 가 맡는다.
 *
 * <p>이 클래스의 최우선 단언은 <b>제외 두 규칙(도착 완료·미경유)이 실제로 다음 지점을 가리는가</b>다
 * (목표 18) — 둘 중 하나만 빼먹어도 기사가 이미 지난 곳이나 안 가는 곳으로 안내받는다.
 * {@code total_remaining_stops} 가 자르기 <b>전</b> 값인지(목표 19)와 {@code origin} 이 {@code moving}
 * 에서 비는지(목표 20)는 둘 다 반대 방향까지 함께 건다 — 한쪽만 검사하면 계산값이 갈리는 지점을
 * 놓친다(공통 규칙 §4).
 *
 * <p>{@code run_stop.status='skipped'} 판정은 다른 좌석(T1)의 몫이라 그 코드를 부르지 않는다 — 이
 * 클래스는 그 컬럼 값을 시험 데이터로 직접 심어 저장 컬럼 값만으로 계약한다({@code p9-t4.md} 경계).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NavigationControllerTest {

    /** 시드 학원 A — 좌표(37.497942, 127.027621)가 등록돼 있어 {@code confirmed} 의 {@code origin} 기준점으로 쓴다. */
    private static final long ACADEMY_A_ID = 1L;

    /** 시드 학원 A 의 2호차 — 고정 노선이 없어 어떤 회차를 새로 만들어도 겹칠 것이 없다. */
    private static final long BUS_A_ID = 2L;

    /** 시드 매니저 1 — 계정 13(driverA1), 학원 A 소속 기사. */
    private static final long DRIVER_MANAGER_ID = 1L;

    private static final long DRIVER_ACCOUNT_ID = 13L;

    /** 시드 매니저 2 — 계정 14, 같은 학원 A 소속이지만 {@code 회차_생성} 이 배치하는 대상이 아니다. */
    private static final long UNASSIGNED_DRIVER_ACCOUNT_ID = 14L;

    /** 시드 회차가 전부 {@code CURRENT_DATE} 라 겹치지 않는 먼 미래 날짜를 쓴다. */
    private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 6, 2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── 목표 18 — scope=next 는 도착 완료·미경유를 뺀 다음 지점만 ────────────────

    /**
     * 1~3번 지점은 도착 완료, 5번 지점은 미경유(skipped) — 남는 것은 4번 하나뿐이라 그것이 목적지가
     * 되고 경유지는 빈 배열이어야 한다. 둘 중 하나라도 빼먹으면(예: 미경유만 걸러내고 도착 완료는
     * 안 거르면) 4번이 아니라 1번이 목적지로 잡힌다.
     */
    @Test
    void scope_next는_도착완료와_미경유를_제외한_다음_지점만_반환한다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        long s1 = 정차지_생성(1);
        long s2 = 정차지_생성(2);
        long s3 = 정차지_생성(3);
        long s4 = 정차지_생성(4);
        long s5 = 정차지_생성(5);
        도착_처리(정차_추가(versionId, 1, s1));
        도착_처리(정차_추가(versionId, 2, s2));
        도착_처리(정차_추가(versionId, 3, s3));
        정차_추가(versionId, 4, s4);
        미경유_처리(정차_추가(versionId, 5, s5));

        MvcResult result = 조회한다(기사_토큰(), runId, "next")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("kakao"))
                .andExpect(jsonPath("$.data.waypoints", hasSize(0)))
                .andReturn();

        long destinationStopId = ((Number) JsonPath.read(본문(result), "$.data.destination.stop_id")).longValue();
        assertThat(destinationStopId).as("도착·미경유를 뺀 다음 지점은 4번이어야 한다 — 하나라도 안 빠지면"
                + " 지난 곳이나 안 가는 곳으로 안내한다").isEqualTo(s4);
    }

    // ── 목표 19 — total_remaining_stops 는 자르기 전 값 ───────────────────

    /** 남은 지점 6곳 → 4개(경유 3 + 목적지 1)로 잘리고 {@code truncated=true}, 총량은 자르기 전 값 6. */
    @Test
    void scope_remaining은_상한_4를_넘는_남은_지점을_자르고_총량은_자르기_전_값이다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        for (int i = 1; i <= 6; i++) {
            정차_추가(versionId, i, 정차지_생성(i));
        }

        조회한다(기사_토큰(), runId, "remaining")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waypoints", hasSize(3)))
                .andExpect(jsonPath("$.data.destination").exists())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.total_remaining_stops").value(6));
    }

    /**
     * 상한 이하일 때의 반대 방향 — 남은 지점 3곳은 자르지 않는다. 여기서 {@code truncated=true} 가
     * 나오거나 총량이 자르기 전·후로 뒤섞이면 목표 19 가 실제로는 상수만 넣고 통과하는 빈 시험이었다는
     * 뜻이다.
     */
    @Test
    void scope_remaining이_상한_이하이면_자르지_않고_총량도_실제_개수와_같다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        for (int i = 1; i <= 3; i++) {
            정차_추가(versionId, i, 정차지_생성(i));
        }

        조회한다(기사_토큰(), runId, "remaining")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waypoints", hasSize(2)))
                .andExpect(jsonPath("$.data.destination").exists())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.total_remaining_stops").value(3));
    }

    // ── 목표 20 — confirmed 는 origin 이 담기고 moving 은 비어야 한다 ─────────────

    /** 출발 30분 전 확정 시점부터 기사가 노선을 미리 볼 수 있어야 한다(X-01) — 그 수단이 origin. */
    @Test
    void confirmed_회차는_200이고_origin에_출발지_좌표가_담긴다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        정차_추가(versionId, 1, 정차지_생성(1));

        조회한다(기사_토큰(), runId, "next")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin").exists())
                .andExpect(jsonPath("$.data.origin.lat").value(37.497942))
                .andExpect(jsonPath("$.data.origin.lng").value(127.027621));
    }

    /**
     * 반대 방향 — 운행 중(moving)에는 origin 이 비어야 한다. 이걸 검사하지 않으면 주행 중에도
     * 출발지가 담겨 앱이 기사를 출발지로 되돌려 보낼 수 있다.
     */
    @Test
    void moving_회차는_200이지만_origin이_비어있다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        정차_추가(versionId, 1, 정차지_생성(1));
        jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", runId);

        조회한다(기사_토큰(), runId, "next")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin").doesNotExist());
    }

    /** idle 은 유일하게 막히는 상태다 — 확정 전에는 안내할 노선 자체가 없다. */
    @Test
    void idle_회차는_409_RUN_NOT_CONFIRMED이다() throws Exception {
        long runId = 회차_생성("idle");

        조회한다(기사_토큰(), runId, "next")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    // ── 목표 21 — 남은 지점 0개는 빈 배열 200 이 아니라 409 ────────────────────

    /**
     * 전 지점 도착 완료 → 409. {@code $.data} 가 없는 것까지 함께 본다 — 200 + 빈 배열이면 앱이
     * 목적지 없는 내비를 띄운다.
     */
    @Test
    void 남은_지점이_모두_소진되면_409이고_빈_배열_200이_아니다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        도착_처리(정차_추가(versionId, 1, 정차지_생성(1)));
        도착_처리(정차_추가(versionId, 2, 정차지_생성(2)));

        조회한다(기사_토큰(), runId, "next")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NAV_NO_REMAINING_STOP"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * (IMPLEMENTATION_PLAN Phase 9 완료 조건 · 목표 21 복원분) 배치되지 않은 회차는 403 이다 —
     * 없으면 배치 안 된 기사·동승자에게 남의 회차 승하차지 좌표(학생 집 근처)가 그대로 나간다.
     * 같은 학원 소속(계정 14)이라 학원 경계는 통과하되, 이 회차의 {@code assignment} 에는 없는
     * 계정으로 호출해 {@code assertAssigned} 경로만 단독으로 걸었다.
     */
    @Test
    void 배치되지_않은_기사는_403_FORBIDDEN이다() throws Exception {
        long runId = 회차_생성("confirmed");
        long versionId = 노선버전_생성(runId);
        정차_추가(versionId, 1, 정차지_생성(1));

        조회한다(배치되지_않은_기사_토큰(), runId, "next")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────

    /**
     * 회차 1건 — 날짜·시간 리터럴은 바인드 파라미터가 아니라 SQL 문자열에 직접 심는다({@code timestamptz}
     * 로의 암묵 캐스팅은 리터럴 텍스트에서만 안정적이다, 기존 {@code *SchemaValidationTest} 관례와 동일).
     */
    private long 회차_생성(String status) {
        String depart = SERVICE_DATE + "T09:00:00+09";
        String confirmAt = SERVICE_DATE + "T08:30:00+09";
        Long runId = jdbcTemplate.queryForObject(
                "INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, confirm_at, "
                        + "status, origin_name, destination_name) VALUES (?, ?, '" + SERVICE_DATE
                        + "', 'to_academy', '" + depart + "', '" + confirmAt + "', ?, '출발지', '도착지') "
                        + "RETURNING id",
                Long.class, ACADEMY_A_ID, BUS_A_ID, status);
        jdbcTemplate.update(
                "INSERT INTO assignment (run_id, manager_id, role, assigned_at) VALUES (?, ?, 'driver', now())",
                runId, DRIVER_MANAGER_ID);
        return runId;
    }

    private long 노선버전_생성(long runId) {
        jdbcTemplate.update("INSERT INTO confirmed_route (run_id, confirmed_at) VALUES (?, now())", runId);
        Long versionId = jdbcTemplate.queryForObject(
                "INSERT INTO route_version (confirmed_route_id, version_no, source, input_fingerprint, "
                        + "engine_name, policy_snapshot) VALUES (?, 1, 'confirm_batch', 'fp', 'heuristic', "
                        + "'{}'::jsonb) RETURNING id",
                Long.class, runId);
        jdbcTemplate.update("UPDATE confirmed_route SET current_version_id = ? WHERE run_id = ?", versionId, runId);
        return versionId;
    }

    private long 정차지_생성(int i) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO stop (academy_id, name, address, lat, lng) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, ACADEMY_A_ID, "정차지" + i, "주소" + i, BigDecimal.valueOf(37.400000 + i * 0.001),
                BigDecimal.valueOf(127.000000 + i * 0.001));
    }

    private long 정차_추가(long routeVersionId, int seq, long stopId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO run_stop (route_version_id, stop_id, seq) VALUES (?, ?, ?) RETURNING id", Long.class,
                routeVersionId, stopId, seq);
    }

    private void 도착_처리(long runStopId) {
        jdbcTemplate.update("UPDATE run_stop SET arrived_at = now() WHERE id = ?", runStopId);
    }

    private void 미경유_처리(long runStopId) {
        jdbcTemplate.update("UPDATE run_stop SET change = 'skipped' WHERE id = ?", runStopId);
    }

    private ResultActions 조회한다(String token, long runId, String scope) throws Exception {
        return mockMvc.perform(
                get("/api/v1/runs/" + runId + "/navigation?scope=" + scope).header("Authorization", token));
    }

    private String 기사_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(DRIVER_ACCOUNT_ID, ACADEMY_A_ID, Role.DRIVER,
                AccountStatus.ACTIVE);
    }

    private String 배치되지_않은_기사_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(UNASSIGNED_DRIVER_ACCOUNT_ID, ACADEMY_A_ID, Role.DRIVER,
                AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

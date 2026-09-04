package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.config.ApiPathPrefixConfig;
import src.backend.global.security.JwtTokenProvider;
import testsupport.gate.AccountStatusGateEndpoints;

/**
 * 학원 격리 HTTP 전수 검사(X-08 후속, F3 목표 10) — 경로 변수를 받는 프로덕션 핸들러 전부를 A학원
 * 계정이 B학원 자원 id 로 두드렸을 때의 응답을 표로 고정한다.
 *
 * <p>검사 대상 개수는 손으로 세지 않는다 — {@link #검사_대상이_런타임_추출_전체_경로변수_핸들러와_정확히_일치한다}
 * 가 {@link AccountStatusGateEndpoints#productionEndpoints} 의 런타임 실측과 {@link #buildCases()} 의
 * 집합을 대조해, 다음 Phase 가 경로 변수 핸들러를 늘렸는데 이 표를 안 늘리면 그 자리에서 실패한다.
 *
 * <p>기대 응답은 세 갈래다 — 자원 자신의 테이블에 학원 조건이 걸린 조회는 {@code 404 <자원>_NOT_FOUND}
 * ({@link AcademyScope} 를 거치지 않는 {@code findByIdAndAcademyId} 계열), 학원 조건 없이 조회한 뒤
 * {@link AcademyScope#assertAccessible} 로 되짚는 지점은 {@code 403 ACADEMY_SCOPE_VIOLATION}, 배치 조회가
 * 먼저인 기사·동승자·학부모 단말 기능은 {@code 403 FORBIDDEN}({@code RunAssignmentAccess}·
 * {@code LinkedChildLookup}) 이다 — 판단 근거는 보고서 항목 ①.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AcademyScopeHttpExhaustiveTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);
    private static final Long ACADEMY_B_RUN_ID = Long.valueOf(SeedFixtures.RUN_CONFIRMED_ACADEMY_B_ID);
    private static final Long OWN_STUDENT_ID = Long.valueOf(SeedFixtures.STUDENT_SIBLING_1_ID);

    /**
     * 상위 게이트가 이 값을 읽기 전에 막아, 실재하지 않아도 안전한 자리에만 쓴다(판단 근거 — 보고서
     * 항목①) — {@code waypointId}·{@code stopId}·{@code riderId} 는 회차를 먼저 academy_id 로 찾은
     * 뒤에야 읽히므로, 회차가 남의 학원이면 이 값에 도달하지 않는다.
     */
    private static final Long PLACEHOLDER_ID = 999_999L;

    private static final String EMPTY_BODY = "{}";
    private static final String WEEKLY_ADDRESS_BODY =
            "{\"entries\":[{\"weekday\":\"mon\",\"direction\":\"to_academy\",\"address\":\"테스트 주소\"}]}";
    private static final String CHANGE_REQUEST_CREATE_BODY = "{\"type\":\"cancel\",\"run_id\":999999}";
    private static final String BOARDING_INTENT_BODY = "{\"riding\":true}";
    private static final String ASSIGNMENT_BODY = "{\"driver_manager_id\":1}";
    private static final String FORCED_ADD_BODY = "{\"address\":\"테스트 주소\"}";
    private static final String WAYPOINT_CREATE_BODY =
            "{\"label\":\"테스트\",\"apply\":false,\"address\":\"테스트 주소\"}";
    private static final String POSITION_BODY =
            "{\"lat\":37.5,\"lng\":127.0,\"recorded_at\":\"2026-09-04T10:00:00+09:00\"}";
    private static final String REPORT_CREATE_BODY = "{\"type\":\"vehicle_issue\",\"memo\":\"테스트\"}";
    private static final String NO_SHOW_BODY = "{\"attempt_type\":\"call\",\"result\":\"no_answer\"}";
    private static final String RIDER_REVERT_BODY = "{}";
    private static final String DECIDE_APPROVE_BODY = "{\"approve\":false}";
    private static final String DECIDE_SIGNUP_BODY = "{\"accept\":false}";
    private static final String OPTIMIZE_BODY =
            "{\"origin\":{\"lat\":37.5,\"lng\":127.0},\"destination\":{\"lat\":37.6,\"lng\":127.1}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private Long academyBStudentId;
    private Long academyBBusId;
    private Long academyBManagerId;
    private Long academyBScheduleId;
    private Long academyBNotificationId;
    private Long academyBAccountId;
    private Long academyBRouteId;
    private Long academyBChangeRequestId;
    private Long academyBSignupRequestId;
    private Long academyBEmergencyAlertId;
    private Long academyBExceptionReportId;

    @BeforeEach
    void B학원_자원_id를_읽고_5개_픽스처를_심는다() {
        academyBStudentId = queryOne("SELECT id FROM student WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);
        academyBBusId = queryOne("SELECT id FROM bus WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);
        academyBManagerId = queryOne("SELECT id FROM manager WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);
        academyBScheduleId = queryOne("SELECT id FROM schedule WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);
        academyBNotificationId =
                queryOne("SELECT id FROM notification_log WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);
        academyBAccountId = queryOne("SELECT id FROM account WHERE academy_id = ? ORDER BY id LIMIT 1", ACADEMY_B);

        assertThat(academyBStudentId).as("B학원 학생 시드가 없으면 대조 재료가 없다").isNotNull();
        assertThat(academyBBusId).as("B학원 차량 시드가 없으면 대조 재료가 없다").isNotNull();
        assertThat(academyBManagerId).as("B학원 매니저 시드가 없으면 대조 재료가 없다").isNotNull();
        assertThat(academyBScheduleId).as("B학원 스케줄 시드가 없으면 대조 재료가 없다").isNotNull();
        assertThat(academyBNotificationId).as("B학원 알림 시드가 없으면 대조 재료가 없다").isNotNull();
        assertThat(academyBAccountId).as("B학원 계정 시드가 없으면 픽스처를 못 심는다").isNotNull();

        academyBRouteId = jdbcTemplate.queryForObject(
                "INSERT INTO route (academy_id, bus_id, weekday, direction, active) "
                        + "VALUES (?, ?, 'mon', 'to_academy', true) RETURNING id",
                Long.class, ACADEMY_B, academyBBusId);

        academyBChangeRequestId = jdbcTemplate.queryForObject(
                "INSERT INTO change_request "
                        + "(academy_id, run_id, student_id, source, type, status, window_segment, requested_by, "
                        + "requested_at) VALUES (?, ?, ?, 'change_request', 'cancel', 'pending', 1, ?, now()) "
                        + "RETURNING id",
                Long.class, ACADEMY_B, ACADEMY_B_RUN_ID, academyBStudentId, academyBAccountId);

        academyBSignupRequestId = jdbcTemplate.queryForObject(
                "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, "
                        + "requested_at) VALUES (?, ?, 'staff', 'staff', 'pending', now()) RETURNING id",
                Long.class, academyBAccountId, ACADEMY_B);

        academyBEmergencyAlertId = jdbcTemplate.queryForObject(
                "INSERT INTO emergency_alert (academy_id, run_id, bus_no, raised_by, raised_by_role, type, "
                        + "rider_count, occurred_at, client_key) "
                        + "VALUES (?, ?, 'B-TEST', ?, 'driver', 'accident', 0, now(), ?) RETURNING id",
                Long.class, ACADEMY_B, ACADEMY_B_RUN_ID, academyBAccountId, UUID.randomUUID());

        academyBExceptionReportId = jdbcTemplate.queryForObject(
                "INSERT INTO exception_report (academy_id, run_id, type, memo, reported_by, reported_at) "
                        + "VALUES (?, ?, 'vehicle_issue', 'f3-s4 fixture', ?, now()) RETURNING id",
                Long.class, ACADEMY_B, ACADEMY_B_RUN_ID, academyBAccountId);
    }

    /**
     * 심은 5개 픽스처를 되돌린다 — {@code route}·{@code change_request}·{@code signup_request} 는
     * FK 로 다른 테이블을 참조하지만 이 행 자체를 삭제해도 참조 대상(bus·student·account)에는 영향이
     * 없다({@code ON DELETE RESTRICT} 는 이 행이 부모일 때만 걸린다).
     */
    @org.junit.jupiter.api.AfterEach
    void 심은_픽스처를_되돌린다() {
        jdbcTemplate.update("DELETE FROM route WHERE id = ?", academyBRouteId);
        jdbcTemplate.update("DELETE FROM change_request WHERE id = ?", academyBChangeRequestId);
        jdbcTemplate.update("DELETE FROM signup_request WHERE id = ?", academyBSignupRequestId);
        jdbcTemplate.update("DELETE FROM emergency_alert WHERE id = ?", academyBEmergencyAlertId);
        jdbcTemplate.update("DELETE FROM exception_report WHERE id = ?", academyBExceptionReportId);
    }

    @TestFactory
    Stream<DynamicTest> A학원_계정이_B학원_자원_id로_두드리면_정본이_정한_코드를_받는다() {
        return buildCases().stream().map(this::toDynamicTest);
    }

    /**
     * 검사 대상 개수를 정본이 아니라 런타임에서 잰다(목표 10 의 핵심 요구) — {@code /admin/*} 는
     * 메인관리자 콘솔이라 학원 격리 대조 자체가 성립하지 않아 뺀다({@code hasPlatformScope()} 가 학원
     * 조건을 우회하는 것이 설계다). {@code DELETE /me/devices/{token}} 은 학원 자원이 아니라 호출자
     * 자신의 기기 토큰이라 "B학원 자원 id" 를 구성할 수 없어 뺀다(정본 침묵 — 보고서 항목②).
     */
    @Test
    void 검사_대상이_런타임_추출_전체_경로변수_핸들러와_정확히_일치한다() {
        Set<String> expected = AccountStatusGateEndpoints.productionEndpoints(handlerMapping, hm -> true).stream()
                .filter(e -> e.contains("{"))
                .filter(e -> !bareEndpointPath(e).startsWith("/admin/"))
                .filter(e -> !e.equals("DELETE /me/devices/{token}"))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> actual = buildCases().stream()
                .map(c -> c.method().name() + " " + c.barePath())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String bareEndpointPath(String endpoint) {
        return endpoint.substring(endpoint.indexOf(' ') + 1);
    }

    private DynamicTest toDynamicTest(ScopeCase c) {
        return DynamicTest.dynamicTest(c.name(), () -> {
            if (c.multipart()) {
                // StaffStudentController 의 등록·수정은 사진 파트를 함께 받는 multipart/form-data 라
                // (Ruling 159) JSON PATCH 로 보내면 "data" 파트 자체가 없어 바인딩 단계에서 422 가 난다.
                mockMvc.perform(multipart(c.method(), ApiPathPrefixConfig.API_PREFIX + c.barePath(), c.uriVars())
                                .file(new MockMultipartFile("data", "", "application/json",
                                        c.body().getBytes(StandardCharsets.UTF_8)))
                                .header("Authorization", c.bearer()))
                        .andExpect(status().is(c.status()))
                        .andExpect(jsonPath("$.error.code").value(c.errorCode()));
                return;
            }
            var builder = MockMvcRequestBuilders
                    .request(c.method(), ApiPathPrefixConfig.API_PREFIX + c.barePath(), c.uriVars())
                    .header("Authorization", c.bearer());
            if (c.body() != null) {
                builder = builder.contentType(MediaType.APPLICATION_JSON).content(c.body());
            }
            mockMvc.perform(builder)
                    .andExpect(status().is(c.status()))
                    .andExpect(jsonPath("$.error.code").value(c.errorCode()));
        });
    }

    /**
     * 45개 경로 변수 핸들러 전부(46건 — 탑승 의사 토글이 studentId·runId 두 축을 따로 검사해 하나
     * 늘어난다) — 패턴별 근거는 클래스 javadoc, 개별 근거는 각 케이스 옆 주석(보고서 항목①).
     */
    private List<ScopeCase> buildCases() {
        String emergencyRaiseBody = "{\"type\":\"accident\",\"client_key\":\"" + UUID.randomUUID() + "\"}";
        String riderStatusBody = "{\"status\":\"boarded\",\"verify_method\":\"manual\",\"client_key\":\""
                + UUID.randomUUID() + "\"}";

        List<ScopeCase> cases = new ArrayList<>();

        // 관계자 웹 CRUD — repository 단 findByIdAndAcademyId, AcademyScope 를 거치지 않는다.
        cases.add(c("GET /staff/students/{id} → B학원 학생 404", HttpMethod.GET, "/staff/students/{id}",
                new Object[] {academyBStudentId}, staffA(), null, 404, "STUDENT_NOT_FOUND"));
        // 등록·수정은 multipart/form-data 다(Ruling 159) — "data" 파트로 보낸다(판단 근거①).
        cases.add(cMultipart("PATCH /staff/students/{id} → B학원 학생 404", HttpMethod.PATCH, "/staff/students/{id}",
                new Object[] {academyBStudentId}, staffA(), EMPTY_BODY, 404, "STUDENT_NOT_FOUND"));
        cases.add(c("DELETE /staff/students/{id} → B학원 학생 404", HttpMethod.DELETE, "/staff/students/{id}",
                new Object[] {academyBStudentId}, staffA(), null, 404, "STUDENT_NOT_FOUND"));
        cases.add(c("PATCH /staff/buses/{id} → B학원 차량 404", HttpMethod.PATCH, "/staff/buses/{id}",
                new Object[] {academyBBusId}, staffA(), EMPTY_BODY, 404, "BUS_NOT_FOUND"));
        cases.add(c("PATCH /staff/managers/{id} → B학원 매니저 404", HttpMethod.PATCH, "/staff/managers/{id}",
                new Object[] {academyBManagerId}, staffA(), EMPTY_BODY, 404, "MANAGER_NOT_FOUND"));
        cases.add(c("DELETE /staff/managers/{id} → B학원 매니저 404", HttpMethod.DELETE, "/staff/managers/{id}",
                new Object[] {academyBManagerId}, staffA(), null, 404, "MANAGER_NOT_FOUND"));
        cases.add(c("PATCH /staff/schedules/{id} → B학원 스케줄 404", HttpMethod.PATCH, "/staff/schedules/{id}",
                new Object[] {academyBScheduleId}, staffA(), EMPTY_BODY, 404, "SCHEDULE_NOT_FOUND"));
        cases.add(c("DELETE /staff/schedules/{id} → B학원 스케줄 404", HttpMethod.DELETE, "/staff/schedules/{id}",
                new Object[] {academyBScheduleId}, staffA(), null, 404, "SCHEDULE_NOT_FOUND"));
        cases.add(c("DELETE /staff/runs/{id} → B학원 회차 404", HttpMethod.DELETE, "/staff/runs/{id}",
                new Object[] {ACADEMY_B_RUN_ID}, staffA(), null, 404, "RUN_NOT_FOUND"));
        cases.add(c("PATCH /staff/routes/{id} → B학원 노선 404", HttpMethod.PATCH, "/staff/routes/{id}",
                new Object[] {academyBRouteId}, staffA(), EMPTY_BODY, 404, "ROUTE_NOT_FOUND"));
        cases.add(c("DELETE /staff/routes/{id} → B학원 노선 404", HttpMethod.DELETE, "/staff/routes/{id}",
                new Object[] {academyBRouteId}, staffA(), null, 404, "ROUTE_NOT_FOUND"));
        cases.add(c("GET /staff/routes/{id} → B학원 노선 404", HttpMethod.GET, "/staff/routes/{id}",
                new Object[] {academyBRouteId}, staffA(), null, 404, "ROUTE_NOT_FOUND"));
        cases.add(c("POST /staff/routes/{id}/optimize → B학원 노선 404", HttpMethod.POST,
                "/staff/routes/{id}/optimize", new Object[] {academyBRouteId}, staffA(), OPTIMIZE_BODY, 404,
                "ROUTE_NOT_FOUND"));
        cases.add(c("POST /staff/runs/{runId}/forced-add → B학원 회차 404", HttpMethod.POST,
                "/staff/runs/{runId}/forced-add", new Object[] {ACADEMY_B_RUN_ID}, staffA(), FORCED_ADD_BODY, 404,
                "RUN_NOT_FOUND"));
        cases.add(c("POST /staff/runs/{runId}/waypoints → B학원 회차 404", HttpMethod.POST,
                "/staff/runs/{runId}/waypoints", new Object[] {ACADEMY_B_RUN_ID}, staffA(), WAYPOINT_CREATE_BODY,
                404, "RUN_NOT_FOUND"));
        // waypointId 는 회차를 academy_id 로 먼저 찾은 뒤에야 읽혀 실재하지 않아도 안전(판단 근거①).
        cases.add(c("DELETE /staff/runs/{runId}/waypoints/{waypointId} → B학원 회차 404", HttpMethod.DELETE,
                "/staff/runs/{runId}/waypoints/{waypointId}", new Object[] {ACADEMY_B_RUN_ID, PLACEHOLDER_ID},
                staffA(), null, 404, "RUN_NOT_FOUND"));
        cases.add(c("PATCH /staff/runs/{runId}/assignment → B학원 회차 404", HttpMethod.PATCH,
                "/staff/runs/{runId}/assignment", new Object[] {ACADEMY_B_RUN_ID}, staffA(), ASSIGNMENT_BODY, 404,
                "RUN_NOT_FOUND"));

        // 관계자 웹 조회 — AcademyScope.assertAccessible, 학원 조건 없는 findById 뒤에 되짚는다.
        cases.add(c("GET /staff/runs/{runId}/roster → B학원 회차 403 ACADEMY_SCOPE_VIOLATION", HttpMethod.GET,
                "/staff/runs/{runId}/roster", new Object[] {ACADEMY_B_RUN_ID}, staffA(), null, 403,
                "ACADEMY_SCOPE_VIOLATION"));
        cases.add(c("GET /staff/runs/{runId}/route → B학원 회차 403 ACADEMY_SCOPE_VIOLATION", HttpMethod.GET,
                "/staff/runs/{runId}/route", new Object[] {ACADEMY_B_RUN_ID}, staffA(), null, 403,
                "ACADEMY_SCOPE_VIOLATION"));
        cases.add(c("GET /staff/approvals/{id} → B학원 change_request 403 ACADEMY_SCOPE_VIOLATION", HttpMethod.GET,
                "/staff/approvals/{id}", new Object[] {academyBChangeRequestId}, staffA(), null, 403,
                "ACADEMY_SCOPE_VIOLATION"));
        cases.add(c("POST /staff/approvals/{id}/decide → B학원 change_request 403 ACADEMY_SCOPE_VIOLATION",
                HttpMethod.POST, "/staff/approvals/{id}/decide", new Object[] {academyBChangeRequestId}, staffA(),
                DECIDE_APPROVE_BODY, 403, "ACADEMY_SCOPE_VIOLATION"));
        cases.add(c("POST /staff/signup-requests/{id}/decide → B학원 신청 403 ACADEMY_SCOPE_VIOLATION",
                HttpMethod.POST, "/staff/signup-requests/{id}/decide", new Object[] {academyBSignupRequestId},
                staffA(), DECIDE_SIGNUP_BODY, 403, "ACADEMY_SCOPE_VIOLATION"));
        cases.add(c("PATCH /notifications/{id}/read → B학원 알림 403 ACADEMY_SCOPE_VIOLATION", HttpMethod.PATCH,
                "/notifications/{id}/read", new Object[] {academyBNotificationId}, staffA(), null, 403,
                "ACADEMY_SCOPE_VIOLATION"));

        // 학부모 앱 — LinkedChildLookup, 자기 자녀가 아니면 academy 필터에 닿기 전에 403.
        cases.add(c("GET /students/{id}/weekly-address → B학원 학생 403 FORBIDDEN", HttpMethod.GET,
                "/students/{id}/weekly-address", new Object[] {academyBStudentId}, parentA1(), null, 403,
                "FORBIDDEN"));
        cases.add(c("PATCH /students/{id}/weekly-address → B학원 학생 403 FORBIDDEN", HttpMethod.PATCH,
                "/students/{id}/weekly-address", new Object[] {academyBStudentId}, parentA1(), WEEKLY_ADDRESS_BODY,
                403, "FORBIDDEN"));
        // studentId 축 — runId 는 학생 확인보다 먼저 읽히지 않아 실재하지 않아도 안전(판단 근거①).
        cases.add(c("PATCH /students/{id}/runs/{runId}/intent → B학원 학생(studentId 축) 403 FORBIDDEN",
                HttpMethod.PATCH, "/students/{id}/runs/{runId}/intent",
                new Object[] {academyBStudentId, PLACEHOLDER_ID}, parentA1(), BOARDING_INTENT_BODY, 403,
                "FORBIDDEN"));
        // runId 축 — 자기 자녀(내 학원)로 고정하고 회차만 B학원으로 바꿔 두 번째 findByIdAndAcademyId 를 문다.
        cases.add(c("PATCH /students/{id}/runs/{runId}/intent → B학원 회차(runId 축) 404 RUN_NOT_FOUND",
                HttpMethod.PATCH, "/students/{id}/runs/{runId}/intent", new Object[] {OWN_STUDENT_ID,
                ACADEMY_B_RUN_ID}, parentA1(), BOARDING_INTENT_BODY, 404, "RUN_NOT_FOUND"));
        cases.add(c("POST /students/{id}/change-requests → B학원 학생 403 FORBIDDEN", HttpMethod.POST,
                "/students/{id}/change-requests", new Object[] {academyBStudentId}, parentA1(),
                CHANGE_REQUEST_CREATE_BODY, 403, "FORBIDDEN"));
        cases.add(c("GET /students/{id}/change-requests → B학원 학생 403 FORBIDDEN", HttpMethod.GET,
                "/students/{id}/change-requests", new Object[] {academyBStudentId}, parentA1(), null, 403,
                "FORBIDDEN"));
        cases.add(c("GET /students/{id}/bus-position → B학원 학생 403 FORBIDDEN", HttpMethod.GET,
                "/students/{id}/bus-position", new Object[] {academyBStudentId}, parentA1(), null, 403,
                "FORBIDDEN"));
        cases.add(c("GET /students/{id}/route → B학원 학생 403 FORBIDDEN", HttpMethod.GET, "/students/{id}/route",
                new Object[] {academyBStudentId}, parentA1(), null, 403, "FORBIDDEN"));

        // 기사·동승자 단말 — RunAssignmentAccess, 배치 조회가 academy 조건보다 먼저다.
        cases.add(c("POST /runs/{runId}/start → B학원 회차 403 FORBIDDEN", HttpMethod.POST, "/runs/{runId}/start",
                new Object[] {ACADEMY_B_RUN_ID}, driverA1(), null, 403, "FORBIDDEN"));
        // stopId 는 회차 배치 확인보다 먼저 읽히지 않아 실재하지 않아도 안전(판단 근거①).
        cases.add(c("POST /runs/{runId}/stops/{stopId}/arrive → B학원 회차 403 FORBIDDEN", HttpMethod.POST,
                "/runs/{runId}/stops/{stopId}/arrive", new Object[] {ACADEMY_B_RUN_ID, PLACEHOLDER_ID}, driverA1(),
                null, 403, "FORBIDDEN"));
        cases.add(c("POST /runs/{runId}/ack-changes → B학원 회차 403 FORBIDDEN", HttpMethod.POST,
                "/runs/{runId}/ack-changes", new Object[] {ACADEMY_B_RUN_ID}, driverA1(), null, 403, "FORBIDDEN"));
        cases.add(c("POST /runs/{runId}/position → B학원 회차 403 FORBIDDEN", HttpMethod.POST,
                "/runs/{runId}/position", new Object[] {ACADEMY_B_RUN_ID}, driverA1(), POSITION_BODY, 403,
                "FORBIDDEN"));
        cases.add(c("POST /runs/{runId}/emergency → B학원 회차 403 FORBIDDEN", HttpMethod.POST,
                "/runs/{runId}/emergency", new Object[] {ACADEMY_B_RUN_ID}, driverA1(), emergencyRaiseBody, 403,
                "FORBIDDEN"));
        cases.add(c("POST /runs/{runId}/reports → B학원 회차 403 FORBIDDEN", HttpMethod.POST, "/runs/{runId}/reports",
                new Object[] {ACADEMY_B_RUN_ID}, driverA1(), REPORT_CREATE_BODY, 403, "FORBIDDEN"));

        // 매니저 앱 — ManagerRunAccess.requireAssignedRun, academy 로 좁힌 회차 조회가 배치 확인보다 먼저다.
        cases.add(c("GET /runs/{runId}/roster → B학원 회차 404 RUN_NOT_FOUND", HttpMethod.GET, "/runs/{runId}/roster",
                new Object[] {ACADEMY_B_RUN_ID}, driverA1(), null, 404, "RUN_NOT_FOUND"));
        cases.add(c("GET /runs/{runId}/route → B학원 회차 404 RUN_NOT_FOUND", HttpMethod.GET, "/runs/{runId}/route",
                new Object[] {ACADEMY_B_RUN_ID}, driverA1(), null, 404, "RUN_NOT_FOUND"));
        cases.add(c("GET /runs/{runId}/navigation → B학원 회차 404 RUN_NOT_FOUND", HttpMethod.GET,
                "/runs/{runId}/navigation", new Object[] {ACADEMY_B_RUN_ID}, driverA1(), null, 404, "RUN_NOT_FOUND"));

        // 동승자 승하차 처리 — BoardingCommandService/NoShowContactCommandService, 회차를 academy 로 먼저 찾는다.
        // riderId 는 회차 확인보다 먼저 읽히지 않아 실재하지 않아도 안전(판단 근거①).
        cases.add(c("PATCH /runs/{runId}/riders/{riderId} → B학원 회차 404 RUN_NOT_FOUND", HttpMethod.PATCH,
                "/runs/{runId}/riders/{riderId}", new Object[] {ACADEMY_B_RUN_ID, PLACEHOLDER_ID}, escortA1(),
                riderStatusBody, 404, "RUN_NOT_FOUND"));
        cases.add(c("POST /runs/{runId}/riders/{riderId}/revert → B학원 회차 404 RUN_NOT_FOUND", HttpMethod.POST,
                "/runs/{runId}/riders/{riderId}/revert", new Object[] {ACADEMY_B_RUN_ID, PLACEHOLDER_ID}, escortA1(),
                RIDER_REVERT_BODY, 404, "RUN_NOT_FOUND"));
        cases.add(c("POST /runs/{runId}/riders/{riderId}/no-show-contacts → B학원 회차 404 RUN_NOT_FOUND",
                HttpMethod.POST, "/runs/{runId}/riders/{riderId}/no-show-contacts",
                new Object[] {ACADEMY_B_RUN_ID, PLACEHOLDER_ID}, escortA1(), NO_SHOW_BODY, 404, "RUN_NOT_FOUND"));

        // 직접 복합키 조회 — 상류 게이트 없이 자기 테이블만 academy 로 좁힌다(판단 근거①).
        cases.add(c("DELETE /runs/{runId}/emergency/{id} → B학원 신고 404 EMERGENCY_NOT_FOUND", HttpMethod.DELETE,
                "/runs/{runId}/emergency/{id}", new Object[] {ACADEMY_B_RUN_ID, academyBEmergencyAlertId},
                driverA1(), null, 404, "EMERGENCY_NOT_FOUND"));
        cases.add(c("POST /staff/emergencies/{id}/ack → B학원 신고 404 EMERGENCY_NOT_FOUND", HttpMethod.POST,
                "/staff/emergencies/{id}/ack", new Object[] {academyBEmergencyAlertId}, staffA(), null, 404,
                "EMERGENCY_NOT_FOUND"));
        cases.add(c("GET /staff/reports/{id} → B학원 보고 404 REPORT_NOT_FOUND", HttpMethod.GET,
                "/staff/reports/{id}", new Object[] {academyBExceptionReportId}, staffA(), null, 404,
                "REPORT_NOT_FOUND"));

        return List.copyOf(cases);
    }

    private static ScopeCase c(String name, HttpMethod method, String barePath, Object[] uriVars, String bearer,
            String body, int status, String errorCode) {
        return new ScopeCase(name, method, barePath, uriVars, bearer, body, status, errorCode, false);
    }

    private static ScopeCase cMultipart(String name, HttpMethod method, String barePath, Object[] uriVars,
            String bearer, String body, int status, String errorCode) {
        return new ScopeCase(name, method, barePath, uriVars, bearer, body, status, errorCode, true);
    }

    private Long queryOne(String sql, Object... args) {
        List<Long> found = jdbcTemplate.queryForList(sql, Long.class, args);
        return found.isEmpty() ? null : found.get(0);
    }

    private String staffA() {
        return bearerFor(SeedFixtures.STAFF_A_LOGIN_ID, Role.STAFF);
    }

    private String parentA1() {
        return bearerFor(SeedFixtures.PARENT_A1_LOGIN_ID, Role.PARENT);
    }

    private String driverA1() {
        return bearerFor(SeedFixtures.DRIVER_A1_LOGIN_ID, Role.DRIVER);
    }

    private String escortA1() {
        return bearerFor(SeedFixtures.ESCORT_A1_LOGIN_ID, Role.ESCORT);
    }

    private String bearerFor(String loginId, Role role) {
        Long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class,
                loginId);
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, role, AccountStatus.ACTIVE);
    }

    /**
     * 검사 1건의 전체 입력·기대값 — {@code uriVars} 는 {@link MockMvcRequestBuilders#request} 의 위치
     * 치환값. {@code multipart} 가 참이면 {@code body} 를 JSON 그대로가 아니라 "data" 파트로 실어 보낸다.
     */
    private record ScopeCase(String name, HttpMethod method, String barePath, Object[] uriVars, String bearer,
            String body, int status, String errorCode, boolean multipart) {
    }
}

package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학생 노선 조회의 {@code road_path[]} · {@code fallback_used}(API_SPEC §3.10, Ruling 831) — 확정 노선 도로 좌표를
 * <b>표시 범위(P-08)의 처음 승하차지부터 마지막 승하차지까지만</b> 잘라 싣는다.
 *
 * <p>시드 회차 3(이동 중 · 등원)의 정차 순서는 stop2 → stop3 → stop1 → stop4 → (경유지) → 학원이다. 이 시험은 그 버전의
 * {@code road_path} 를 <b>직접 심은 9점</b>으로 바꿔, 기대값이 시드의 실제 좌표 우연이 아니라 심은 좌표가 되게 한다.
 * 좌표 0·2·4·6·8 번이 각각 stop2 · stop3 · stop1 · stop4 · 학원이고 나머지는 그 사이 도로 위의 점이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentRouteRoadPathTest {

    private static final long ACADEMY_A = 1L;

    /** 학생 1·2 의 보호자(형제). */
    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    /** 학생 5 의 보호자 — 학생 1 과는 연결이 없다. */
    private static final long STUDENT_5_GUARDIAN_ACCOUNT = 7L;

    private static final long MOVING_RUN = 3L;

    private static final long IDLE_RUN = 1L;

    private static final long MOVING_ROUTE_VERSION = 3L;

    /** 0=stop2 · 1=사이 · 2=stop3 · 3=사이 · 4=stop1 · 5=사이 · 6=stop4 · 7=사이 · 8=학원(37.497942, 127.027621). */
    private static final String PLANTED_PATH = """
            [{"lat":37.5675,"lng":126.9790},{"lat":37.5680,"lng":126.9795},{"lat":37.5685,"lng":126.9800},
             {"lat":37.5670,"lng":126.9785},{"lat":37.5665,"lng":126.9780},{"lat":37.5690,"lng":126.9805},
             {"lat":37.5695,"lng":126.9810},{"lat":37.5500,"lng":126.9900},{"lat":37.497942,"lng":127.027621}]
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 학생 5 는 stop4 가 자기 승하차지라 표시 범위가 stop3 · stop1 · stop4 · 학원이다(stop2 는 범위 밖). 선은 stop3(2번 점)에서
     * 학원(8번 점)까지 7점이고, 범위 밖 stop2 쪽 0·1번 점은 들어가지 않는다 — 앞 구간을 자르지 않고 전체를 싣는 구현을 가르는 시험.
     */
    @Test
    void 도로_좌표는_표시_범위의_처음_승하차지부터_끝_승하차지까지만_싣는다() throws Exception {
        심는다(PLANTED_PATH, false);

        String body = 읽는다(5L, STUDENT_5_GUARDIAN_ACCOUNT);

        List<Map<String, Object>> path = JsonPath.read(body, "$.data.road_path");
        assertThat(path).hasSize(7);
        assertThat(path.get(0)).containsEntry("lat", 37.5685).containsEntry("lng", 126.98);
        assertThat(path.get(path.size() - 1)).containsEntry("lat", 37.497942).containsEntry("lng", 127.027621);
        assertThat(path).as("표시 범위보다 앞인 stop2 쪽 좌표").doesNotContain(Map.of("lat", 37.5675, "lng", 126.979),
                Map.of("lat", 37.568, "lng", 126.9795));
    }

    /** 학생 2 는 자기 승하차지가 맨 앞(stop2)이라 범위가 stop2 · 학원이다 — 선은 0번 점부터 끝까지 전부(9점). */
    @Test
    void 범위가_노선_맨_앞에서_시작하면_첫_점부터_싣는다() throws Exception {
        심는다(PLANTED_PATH, false);

        String body = 읽는다(2L, SIBLINGS_GUARDIAN_ACCOUNT);

        List<Map<String, Object>> path = JsonPath.read(body, "$.data.road_path");
        assertThat(path).hasSize(9);
        assertThat(path.get(0)).containsEntry("lat", 37.5675).containsEntry("lng", 126.979);
    }

    /** 확정 전(idle) 회차는 도로 경로가 아직 없어 빈 배열이고 {@code fallback_used} 는 {@code false} 다. */
    @Test
    void 확정_전_회차는_도로_좌표가_빈_배열이고_fallback_used_는_false_이다() throws Exception {
        String body = mockMvc.perform(get("/api/v1/students/1/route?run_id=" + IDLE_RUN)
                .header("Authorization", 보호자_토큰(SIBLINGS_GUARDIAN_ACCOUNT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.<Boolean>read(body, "$.data.confirmed")).isFalse();
        assertThat(JsonPath.<List<Object>>read(body, "$.data.road_path")).isEmpty();
        assertThat(JsonPath.<Boolean>read(body, "$.data.fallback_used")).isFalse();
    }

    /** 도로 좌표 컬럼이 비어 있는 옛 버전은 빈 배열이고, {@code fallback_used} 는 그 버전의 값 그대로다. */
    @Test
    void 도로_좌표가_빈_옛_버전은_빈_배열이고_fallback_used_는_버전의_값이다() throws Exception {
        jdbc.update("UPDATE route_version SET road_path = NULL, fallback_used = true WHERE id = ?",
                MOVING_ROUTE_VERSION);

        String body = 읽는다(5L, STUDENT_5_GUARDIAN_ACCOUNT);

        assertThat(JsonPath.<List<Object>>read(body, "$.data.road_path")).isEmpty();
        assertThat(JsonPath.<Boolean>read(body, "$.data.fallback_used")).isTrue();
    }

    /** 운행이 끝난 회차도 {@code run_id} 로 같은 모양 — 종료 화면이 지나온 구간을 그린다. */
    @Test
    void 운행이_끝난_회차도_같은_모양으로_도로_좌표를_싣는다() throws Exception {
        심는다(PLANTED_PATH, true);
        jdbc.update("UPDATE run SET status = 'finished', finished_at = now() WHERE id = ?", MOVING_RUN);

        String body = 읽는다(5L, STUDENT_5_GUARDIAN_ACCOUNT);

        assertThat(JsonPath.<List<Object>>read(body, "$.data.road_path")).isNotEmpty();
        assertThat(JsonPath.<Boolean>read(body, "$.data.fallback_used")).isTrue();
    }

    /** 연결 부재 자녀는 도로 좌표를 받기 전에 {@code 403} 이다 — 기존 인가가 그대로 앞선다. */
    @Test
    void 연결_부재_자녀의_노선은_도로_좌표를_싣지_않고_403_이다() throws Exception {
        심는다(PLANTED_PATH, false);

        mockMvc.perform(get("/api/v1/students/1/route?run_id=" + MOVING_RUN)
                .header("Authorization", 보호자_토큰(STUDENT_5_GUARDIAN_ACCOUNT)))
                .andExpect(status().isForbidden());
    }

    private void 심는다(String roadPathJson, boolean fallbackUsed) {
        jdbc.update("UPDATE route_version SET road_path = ?::jsonb, fallback_used = ? WHERE id = ?", roadPathJson,
                fallbackUsed, MOVING_ROUTE_VERSION);
    }

    private String 읽는다(long studentId, long guardianAccountId) throws Exception {
        return mockMvc.perform(get("/api/v1/students/%d/route?run_id=%d".formatted(studentId, MOVING_RUN))
                .header("Authorization", 보호자_토큰(guardianAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 보호자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }
}

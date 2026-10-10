package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

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
 * R48 이 학부모 앱 응답에 더한 필드(API_SPEC §3.1 · §3.9 · §3.10, Ruling 824) — 자녀 {@code grade} · 변경 신청 이력의
 * {@code service_date}·{@code direction} · 학생 노선 {@code stops[].arrived_at}.
 *
 * <p>시드 보호자 5 는 형제 학생 1·2 의 보호자이고, 회차 3(이동 중 · 등원 · 오늘)에 학생 1 이 탄다. 기대값은 응답 모양이 아니라
 * <b>이 시험이 SQL 로 심은 값</b>이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GuardianAppRedesignFieldsTest {

    private static final long ACADEMY_A = 1L;

    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    private static final long STUDENT_1 = 1L;

    private static final long STUDENT_2 = 2L;

    private static final long MOVING_RUN = 3L;

    private static final long RETURN_RUN = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    // ── 항목 7: 자녀 목록 grade ─────────────────────────────────────────────

    /** 자녀마다 학년을 싣고, 학년을 입력하지 않은 자녀는 키는 있되 {@code null} 이다(§3.1 ○). */
    @Test
    void 자녀_목록은_grade_를_싣고_미입력이면_null_이다() throws Exception {
        jdbc.update("UPDATE student SET grade = '초5' WHERE id = ?", STUDENT_1);
        jdbc.update("UPDATE student SET grade = NULL WHERE id = ?", STUDENT_2);

        String body = 읽는다("/api/v1/me/students");

        List<String> first = JsonPath.read(body, "$.data.items[?(@.student_id == '1')].grade");
        List<String> second = JsonPath.read(body, "$.data.items[?(@.student_id == '2')].grade");
        assertThat(first).containsExactly("초5");
        assertThat(second).hasSize(1).allMatch(Objects::isNull);
    }

    // ── 항목 8: 변경 신청 이력 service_date · direction ───────────────────────

    /**
     * 신청 이력의 {@code service_date}·{@code direction} 은 <b>대상 회차의 값</b>이다 — 두 신청을 서로 다른 날짜·방향의 회차에
     * 걸어 한쪽 값을 상수로 돌려주는 구현을 가른다.
     */
    @Test
    void 변경_신청_이력은_대상_회차의_service_date_와_direction_을_싣는다() throws Exception {
        jdbc.update("UPDATE run SET service_date = service_date - 1 WHERE id = ?", RETURN_RUN);
        신청(MOVING_RUN, "approved");
        신청(RETURN_RUN, "pending");

        String body = 읽는다("/api/v1/students/%d/change-requests".formatted(STUDENT_1));

        // 시드에도 같은 회차를 대상으로 한 신청이 있어 회차마다 항목이 여럿일 수 있다 — 그 회차 항목 전부가 회차의 값과 같아야 한다
        for (long runId : new long[] {MOVING_RUN, RETURN_RUN}) {
            List<String> dates = JsonPath.read(body, "$.data.items[?(@.run_id == '" + runId + "')].service_date");
            List<String> directions = JsonPath.read(body, "$.data.items[?(@.run_id == '" + runId + "')].direction");
            assertThat(dates).as("run %d service_date", runId).isNotEmpty().containsOnly(
                    jdbc.queryForObject("SELECT service_date::text FROM run WHERE id = ?", String.class, runId));
            assertThat(directions).as("run %d direction", runId).isNotEmpty().containsOnly(
                    jdbc.queryForObject("SELECT direction FROM run WHERE id = ?", String.class, runId));
        }
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.run_id == '3')].direction"))
                .as("두 회차의 방향이 달라야 시험이 값의 출처를 가른다").containsOnly("to_academy");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.run_id == '2')].direction"))
                .containsOnly("from_academy");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.run_id == '3')].service_date"))
                .as("두 회차의 운행일이 달라야 한다")
                .doesNotContainAnyElementsOf(JsonPath.<List<String>>read(body,
                        "$.data.items[?(@.run_id == '2')].service_date"));
    }

    /**
     * 신청 이력은 저장된 {@code deadline_at}(②구간 승인 마감 — Ruling 870)을 그대로 싣고, 마감이 없는 건(①구간 즉시 반영)은 키는 있되
     * {@code null} 이다 — 학부모 앱 홈 "마감까지 N분" 이 이 값을 쓴다. 두 건을 값이 다르게 심어 한쪽 값을 상수로 돌려주는 구현을 가른다.
     */
    @Test
    void 변경_신청_이력은_저장된_deadline_at_을_싣고_마감_없는_건은_null_이다() throws Exception {
        long withDeadline = 신청(MOVING_RUN, "pending", "2030-04-01 09:10:00+09");
        long withoutDeadline = 신청(RETURN_RUN, "approved", null);

        String body = 읽는다("/api/v1/students/%d/change-requests".formatted(STUDENT_1));

        List<String> deadlines = JsonPath.read(body,
                "$.data.items[?(@.change_request_id == '" + withDeadline + "')].deadline_at");
        assertThat(deadlines).hasSize(1);
        assertThat(OffsetDateTime.parse(deadlines.get(0)).toInstant())
                .isEqualTo(OffsetDateTime.parse("2030-04-01T09:10:00+09:00").toInstant());
        List<String> none = JsonPath.read(body,
                "$.data.items[?(@.change_request_id == '" + withoutDeadline + "')].deadline_at");
        assertThat(none).hasSize(1).allMatch(Objects::isNull);
    }

    // ── 항목 9: 학생 노선 stops[].arrived_at ─────────────────────────────────

    /**
     * 도착 처리된 승하차지만 그 시각을 싣고, 아직이거나 학원(합성 항목)은 {@code null} 이다. 시드 회차 3 의 현재 노선 판본에서 정차지 2
     * 한 곳만 도착으로 심고 나머지는 비운다.
     */
    @Test
    void 학생_노선의_stops_는_도착_처리된_곳만_arrived_at_을_싣는다() throws Exception {
        String arrived = "2026-10-04 15:00:00+09";
        jdbc.update("UPDATE run_stop SET arrived_at = NULL WHERE route_version_id = "
                + "(SELECT current_version_id FROM confirmed_route WHERE run_id = ?)", MOVING_RUN);
        jdbc.update("UPDATE run_stop SET arrived_at = ?::timestamptz WHERE stop_id = 2 AND route_version_id = "
                + "(SELECT current_version_id FROM confirmed_route WHERE run_id = ?)", arrived, MOVING_RUN);

        String body = 읽는다("/api/v1/students/%d/route?run_id=%d".formatted(STUDENT_1, MOVING_RUN));

        List<String> done = JsonPath.read(body, "$.data.stops[?(@.stop_id == '2')].arrived_at");
        assertThat(done).hasSize(1);
        assertThat(OffsetDateTime.parse(done.get(0)).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-04T15:00:00+09:00").toInstant());
        List<String> notYet = JsonPath.read(body, "$.data.stops[?(@.stop_id == '3')].arrived_at");
        assertThat(notYet).hasSize(1).allMatch(Objects::isNull);
        List<Object> all = JsonPath.read(body, "$.data.stops[*].arrived_at");
        assertThat(all).as("학원 합성 항목까지 모든 stops 가 arrived_at 키를 가진다").hasSize(4);
        assertThat(all.stream().filter(Objects::nonNull)).as("도착 처리된 곳은 정차지 2 하나").hasSize(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 읽는다(String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + tokenProvider.createAccessToken(
                        SIBLINGS_GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void 신청(long runId, String status) {
        신청(runId, status, null);
    }

    /** 신청 한 건을 심고 id 를 돌려준다 — {@code deadlineAt} 이 {@code null} 이면 마감 없는 건이다. */
    private long 신청(long runId, String status, String deadlineAt) {
        return jdbc.queryForObject("INSERT INTO change_request (academy_id, run_id, student_id, source, type, status, "
                + "window_segment, requested_by, requested_at, deadline_at) VALUES (?, ?, ?, 'change_request', 'cancel', "
                + "?, 1, ?, now(), ?::timestamptz) RETURNING id", Long.class, ACADEMY_A, runId, STUDENT_1, status,
                SIBLINGS_GUARDIAN_ACCOUNT, deadlineAt);
    }
}

package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * R48 리디자인이 학원 목록·상세에 더한 필드(API_SPEC §6.1·§6.3, Ruling 806) — {@code has_address} ·
 * {@code pending_signup_count} · 최상위 {@code summary} · {@code staff_accounts[].last_login_at} ·
 * {@code stats.moving_bus_nos[]}.
 *
 * <p>기대값은 서비스가 쓰는 저장소 메서드가 아니라 <b>SQL 로 따로 다시 센다</b> — 같은 메서드를 불러 비교하면
 * 구현이 틀려도 시험이 같이 틀려 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAcademyConsoleFieldsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    // ── 항목 1: 목록 ──────────────────────────────────────────────────────

    /** 주소가 없거나 공백뿐인 학원은 {@code false} 다 — 회차 확정이 전부 실패하는 학원을 목록에서 바로 가려내려는 값이다. */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, address, status) VALUES "
                    + "('R48ADDR01', 'R48주소있음', '서울', '서울 강남구 테헤란로 152', 'active')",
            "INSERT INTO academy (code, name, region, status) VALUES ('R48ADDR02', 'R48주소없음', '서울', 'active')",
            "INSERT INTO academy (code, name, region, address, status) VALUES "
                    + "('R48ADDR03', 'R48주소공백', '서울', '   ', 'active')"
    })
    void 목록의_has_address_는_주소가_있을_때만_true_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies").header("Authorization", 메인관리자_토큰()).param("q", "R48ADDR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[?(@.code == 'R48ADDR01')].has_address").value(true))
                .andExpect(jsonPath("$.data.items[?(@.code == 'R48ADDR02')].has_address").value(false))
                .andExpect(jsonPath("$.data.items[?(@.code == 'R48ADDR03')].has_address").value(false));
    }

    /**
     * {@code pending_signup_count} 는 <b>그 학원의</b> 대기 중 <b>관계자</b> 가입 요청만 센다(§6.4).
     *
     * <p>세 방향을 한 번에 고정한다 — 처리 끝난 요청(accepted)을 세면 3, 학부모 가입 요청(승인 주체가 관계자)을
     * 세면 4, 학원 조건이 없으면 첫 학원이 3 이상이 된다. 기대값 2 · 1 이 이 셋을 모두 가른다.
     */
    @Test
    void 목록의_pending_signup_count_는_그_학원의_대기_중_관계자_가입_요청만_센다() throws Exception {
        학원("R48PEND01", "R48대기학원");
        학원("R48PEND02", "R48대기다른학원");
        계정("r48pq1", "staff", "pending", "R48PEND01");
        계정("r48pq2", "staff", "pending", "R48PEND01");
        계정("r48pq3", "staff", "active", "R48PEND01");
        계정("r48pqp", "parent", "pending", "R48PEND01");
        계정("r48pqo", "staff", "pending", "R48PEND02");
        가입요청("r48pq1", "R48PEND01", "staff", "system_admin", "pending");
        가입요청("r48pq2", "R48PEND01", "staff", "system_admin", "pending");
        가입요청("r48pq3", "R48PEND01", "staff", "system_admin", "accepted");
        가입요청("r48pqp", "R48PEND01", "parent", "staff", "pending");
        가입요청("r48pqo", "R48PEND02", "staff", "system_admin", "pending");

        mockMvc.perform(get("/api/v1/admin/academies").header("Authorization", 메인관리자_토큰()).param("q", "R48PEND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.code == 'R48PEND01')].pending_signup_count").value(2))
                .andExpect(jsonPath("$.data.items[?(@.code == 'R48PEND02')].pending_signup_count").value(1));
    }

    /** 상세는 "§6.1 항목 + α" 라(§6.3) 목록과 같은 {@code has_address} · {@code pending_signup_count} 를 싣는다. */
    @Test
    void 학원_상세도_has_address_와_pending_signup_count_를_싣는다() throws Exception {
        학원("R48DTL001", "R48상세학원");
        계정("r48dtlq", "staff", "pending", "R48DTL001");
        가입요청("r48dtlq", "R48DTL001", "staff", "system_admin", "pending");

        mockMvc.perform(get("/api/v1/admin/academies/" + 학원_식별자("R48DTL001"))
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_address").value(false))
                .andExpect(jsonPath("$.data.pending_signup_count").value(1));
    }

    /**
     * {@code summary} 는 {@code q}·{@code status}·쪽과 무관한 <b>전체</b> 값이다(§6.1) — 상태 탭 건수와 지표 칸이
     * 필터를 걸어도 바뀌지 않아야 한다. 필터를 건 호출(1건만 남음)과 안 건 호출의 summary 가 같고, 둘 다 SQL 로 센
     * 전체 값과 같아야 한다.
     */
    @Test
    void summary_는_q_status_쪽을_걸어도_전체_값이다() throws Exception {
        학원("R48SUM01", "R48요약비활성");
        jdbc.update("UPDATE academy SET status = 'inactive' WHERE code = 'R48SUM01'");
        학원("R48SUM02", "R48요약활성");
        계정("r48sumparent", "parent", "active", "R48SUM02");
        계정("r48sumblocked", "student", "blocked", "R48SUM02");
        계정("r48sumpending", "parent", "pending", "R48SUM02");

        long total = 센다("SELECT count(*) FROM academy");
        long active = 센다("SELECT count(*) FROM academy WHERE status = 'active'");
        long inactive = 센다("SELECT count(*) FROM academy WHERE status = 'inactive'");
        long users = 센다("SELECT count(*) FROM account WHERE academy_id IS NOT NULL "
                + "AND role IN ('parent', 'student', 'driver', 'escort') AND status IN ('active', 'blocked')");

        MvcResult filtered = mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("q", "R48SUM01").param("status", "inactive").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        MvcResult unfiltered = mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn();

        for (MvcResult result : List.of(filtered, unfiltered)) {
            String body = 본문(result);
            assertThat(JsonPath.<Number>read(body, "$.data.summary.total").longValue()).isEqualTo(total);
            assertThat(JsonPath.<Number>read(body, "$.data.summary.active").longValue()).isEqualTo(active);
            assertThat(JsonPath.<Number>read(body, "$.data.summary.inactive").longValue()).isEqualTo(inactive);
            assertThat(JsonPath.<Number>read(body, "$.data.summary.user_count").longValue()).isEqualTo(users);
        }
    }

    // ── 항목 2: 상세 ──────────────────────────────────────────────────────

    /** 관계자 행마다 {@code last_login_at} 이 실리고, 로그인한 적 없는 계정은 키는 있되 {@code null} 이다. */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('R48LL0001', 'R48로그인학원', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status, last_login_at) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'R48LL0001'), 'r48llon', 'x', '로그인관계자', "
                    + "'010-0000-4801', 'staff', 'active', '2026-10-03 09:00:00+09')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'R48LL0001'), 'r48lloff', 'x', '미로그인관계자', "
                    + "'010-0000-4802', 'staff', 'active')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'R48LL0001'), "
                    + "(SELECT id FROM account WHERE login_id = 'r48llon'), 'inactive')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'R48LL0001'), "
                    + "(SELECT id FROM account WHERE login_id = 'r48lloff'), 'active')"
    })
    void 학원_상세의_staff_accounts_는_last_login_at_을_싣고_로그인한_적_없으면_null_이다() throws Exception {
        String body = 본문(mockMvc.perform(get("/api/v1/admin/academies/" + 학원_식별자("R48LL0001"))
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn());

        List<String> logged = JsonPath.read(body, "$.data.staff_accounts[?(@.login_id == 'r48llon')].last_login_at");
        assertThat(logged).hasSize(1);
        assertThat(OffsetDateTime.parse(logged.get(0)).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-03T09:00:00+09:00").toInstant());

        List<String> never = JsonPath.read(body, "$.data.staff_accounts[?(@.login_id == 'r48lloff')].last_login_at");
        assertThat(never).hasSize(1).allMatch(Objects::isNull);
    }

    /**
     * {@code moving_bus_nos[]} 는 {@code moving_bus_count} 와 <b>같은 범위</b>의 차량 이름이다 — 운행일이 어제 이후인
     * 미취소 {@code moving} 회차. 시드 학원 1 은 2호차의 회차 3 이 {@code moving} 이고, 같은 차량의 회차 4 를 더
     * {@code moving} 으로 해도 이름은 한 번만 나온다(차량 수이지 회차 수가 아니다). 3호차(차량 10)의 회차 100 을 더해
     * 두 대가 나오게 한다.
     */
    @Test
    @Sql(statements = {
            "UPDATE run SET status = 'moving' WHERE id = 4 AND bus_id = 2",
            "UPDATE run SET status = 'moving', confirmed_at = now(), started_at = now() WHERE id = 100 AND bus_id = 10"
    })
    void 학원_상세의_moving_bus_nos_는_운행_중_차량의_호차_이름을_중복_없이_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies/1").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.moving_bus_count").value(2))
                .andExpect(jsonPath("$.data.stats.moving_bus_nos.length()").value(2))
                .andExpect(jsonPath("$.data.stats.moving_bus_nos[0]").value("2호차"))
                .andExpect(jsonPath("$.data.stats.moving_bus_nos[1]").value("3호차"));
    }

    /** 운행일이 이틀 이상 지난 {@code moving} 회차는 개수에서 빠지듯 이름에서도 빠진다 — 두 값이 갈리면 화면이 서로 다른 말을 한다. */
    @Test
    @Sql(statements = "UPDATE run SET service_date = service_date - 3 WHERE id = 3 AND status = 'moving'")
    void 학원_상세의_moving_bus_nos_는_끝나지_않은_옛_회차의_차량을_싣지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies/1").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.moving_bus_count").value(0))
                .andExpect(jsonPath("$.data.stats.moving_bus_nos.length()").value(0));
    }

    /** 임시 취소된 회차의 차량도 개수와 이름 양쪽에서 빠진다. */
    @Test
    @Sql(statements = "UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = 3 AND status = 'moving'")
    void 학원_상세의_moving_bus_nos_는_취소된_회차의_차량을_싣지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies/1").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.moving_bus_count").value(0))
                .andExpect(jsonPath("$.data.stats.moving_bus_nos.length()").value(0));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private void 학원(String code, String name) {
        jdbc.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, '서울', 'active')", code, name);
    }

    private void 계정(String loginId, String role, String status, String academyCode) {
        // status_before_block 은 차단 계정에만 채운다(account 의 CHECK) — 그 밖의 상태는 비워 둔다
        jdbc.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status, "
                + "status_before_block) VALUES ((SELECT id FROM academy WHERE code = ?), ?, 'x', ?, ?, ?, ?, ?)",
                academyCode, loginId, loginId, "010-4800-%04d".formatted(Math.abs(loginId.hashCode()) % 10000),
                role, status, "blocked".equals(status) ? "active" : null);
    }

    private void 가입요청(String loginId, String academyCode, String role, String approver, String status) {
        jdbc.update("INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, "
                + "requested_at) VALUES ((SELECT id FROM account WHERE login_id = ?), "
                + "(SELECT id FROM academy WHERE code = ?), ?, ?, ?, now())",
                loginId, academyCode, role, approver, status);
    }

    private long 센다(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0L : count;
    }

    private long 학원_식별자(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
        return Objects.requireNonNull(id);
    }

    /** 응답 본문을 UTF-8 로 읽는다 — 기본 인코딩으로 읽으면 한글 필드가 깨져 대조가 어긋난다. */
    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

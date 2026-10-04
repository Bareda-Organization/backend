package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 관계자 학생 목록의 R48 확장(API_SPEC §5.11, Ruling 815) — 항목의 {@code grade}·{@code can_go_alone}·
 * {@code weekly_address_status}(3값 경계), 쿼리 {@code class_name}·{@code filter}, 최상위 {@code summary}(쿼리·쪽과 무관한
 * 학원 전체 값), 그리고 쿼리 수가 학생 수에 비례하지 않는지를 본다. 시드 학생이 섞이지 않게 시험이 만든 학생은 이름 머리글로
 * 찾고, 학원 전체 값은 심기 전후의 차이로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffStudentListExtensionTest {

    private static final long ACADEMY_A = 1L;

    private static final String BASE = "/api/v1/staff/students";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void weekly_address_status_는_등록_0건_none_한쪽_방향만_있는_요일이_있으면_partial_모두_두_방향이면_complete_다() throws Exception {
        심는다("R48WS없음", "초3", true, null);
        long 등원만 = 심는다("R48WS등원만", null, false, null);
        long 완전 = 심는다("R48WS완전", null, false, null);
        long 한요일빠짐 = 심는다("R48WS한요일빠짐", null, false, null);
        주소를_심는다(등원만, "mon", "to_academy");
        주소를_심는다(완전, "mon", "to_academy");
        주소를_심는다(완전, "mon", "from_academy");
        주소를_심는다(완전, "tue", "to_academy");
        주소를_심는다(완전, "tue", "from_academy");
        주소를_심는다(한요일빠짐, "mon", "to_academy");
        주소를_심는다(한요일빠짐, "mon", "from_academy");
        주소를_심는다(한요일빠짐, "tue", "to_academy");

        String body = 목록("q=R48WS&size=100");

        assertThat(항목("R48WS없음", body, "weekly_address_status")).isEqualTo("none");
        assertThat(항목("R48WS등원만", body, "weekly_address_status")).as("등원만 있는 요일 1개 → partial").isEqualTo("partial");
        assertThat(항목("R48WS완전", body, "weekly_address_status")).isEqualTo("complete");
        assertThat(항목("R48WS한요일빠짐", body, "weekly_address_status")).as("한 요일이라도 한 방향뿐이면 partial").isEqualTo("partial");
        assertThat(항목("R48WS없음", body, "grade")).isEqualTo("초3");
        assertThat(항목("R48WS없음", body, "can_go_alone")).isEqualTo(true);
        assertThat(항목("R48WS등원만", body, "can_go_alone")).isEqualTo(false);
    }

    @Test
    void class_name_과_filter_는_목록을_좁히고_total_count_도_좁혀진_수다() throws Exception {
        long 반A_주소만 = 심는다("R48F반A주소", null, false, "R48반A");
        long 반A_보호자만 = 심는다("R48F반A보호자", null, false, "R48반A");
        심는다("R48F반B없음", null, false, "R48반B");
        주소를_심는다(반A_주소만, "mon", "to_academy");
        보호자를_연결한다(반A_보호자만);

        assertThat(이름들("q=R48F&class_name=R48반A&size=100")).containsExactlyInAnyOrder("R48F반A주소", "R48F반A보호자");
        assertThat(이름들("q=R48F&filter=guardian_unlinked&size=100")).as("보호자 연결 0건")
                .containsExactlyInAnyOrder("R48F반A주소", "R48F반B없음");
        assertThat(이름들("q=R48F&filter=address_missing&size=100")).as("요일별 주소 등록 0건")
                .containsExactlyInAnyOrder("R48F반A보호자", "R48F반B없음");
        assertThat(이름들("q=R48F&class_name=R48반A&filter=address_missing&size=100")).containsExactly("R48F반A보호자");

        String 쪽 = 목록("q=R48F&filter=address_missing&size=1");
        assertThat((int) JsonPath.read(쪽, "$.data.total_count")).as("쪽이 아니라 필터에 걸린 전체 수").isEqualTo(2);
        assertThat((boolean) JsonPath.read(쪽, "$.data.has_next")).isTrue();
    }

    @Test
    void filter_가_허용_값_밖이면_422_다() throws Exception {
        mockMvc.perform(get(BASE).param("filter", "unknown").header("Authorization", 토큰()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void summary_는_쿼리와_쪽에_무관한_학원_전체_값이다() throws Exception {
        Map<String, Integer> 전 = summary(목록("size=1"));

        long 주소있음 = 심는다("R48SM주소", null, true, "R48요약새반");
        심는다("R48SM없음", null, false, null);
        주소를_심는다(주소있음, "mon", "to_academy");
        보호자를_연결한다(주소있음);

        Map<String, Integer> 후 = summary(목록("size=1"));
        assertThat(후.get("total") - 전.get("total")).as("재원 학생 2명 추가").isEqualTo(2);
        assertThat(후.get("class_count") - 전.get("class_count")).as("새 반 1종").isEqualTo(1);
        assertThat(후.get("can_go_alone") - 전.get("can_go_alone")).isEqualTo(1);
        assertThat(후.get("guardian_unlinked") - 전.get("guardian_unlinked")).as("보호자 없는 학생 1명").isEqualTo(1);
        assertThat(후.get("address_missing") - 전.get("address_missing")).as("주소 없는 학생 1명").isEqualTo(1);

        // 쿼리·필터·쪽을 걸어도 같은 값이다
        assertThat(summary(목록("q=R48SM&filter=address_missing&class_name=없는반&page=0&size=1"))).isEqualTo(후);
    }

    @Test
    void 목록의_쿼리_수는_학생_수에_비례하지_않는다() throws Exception {
        long 하나 = 심는다("R48Q학생0", "초1", false, "R48Q반");
        주소를_심는다(하나, "mon", "to_academy");
        long 한명 = 쿼리_수("q=R48Q&size=100");

        for (int i = 1; i <= 5; i++) {
            long 학생 = 심는다("R48Q학생" + i, "초1", false, "R48Q반");
            주소를_심는다(학생, "mon", "to_academy");
            주소를_심는다(학생, "mon", "from_academy");
        }

        assertThat(쿼리_수("q=R48Q&size=100")).isEqualTo(한명);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long 심는다(String name, String grade, boolean canGoAlone, String className) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO student (academy_id, name, grade, can_go_alone, class_name) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, ACADEMY_A, name, grade, canGoAlone, className);
    }

    private void 주소를_심는다(long studentId, String weekday, String direction) {
        jdbcTemplate.update("INSERT INTO weekly_address (student_id, weekday, direction, address) VALUES (?, ?, ?, '테스트 주소')",
                studentId, weekday, direction);
    }

    private void 보호자를_연결한다(long studentId) {
        long guardianId = jdbcTemplate.queryForObject("SELECT min(id) FROM guardian WHERE academy_id = ?", Long.class,
                ACADEMY_A);
        jdbcTemplate.update("INSERT INTO guardian_student (guardian_id, student_id, linked_at) VALUES (?, ?, now())",
                guardianId, studentId);
    }

    private String 목록(String query) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "?" + query).header("Authorization", 토큰()))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> 이름들(String query) throws Exception {
        return JsonPath.read(목록(query), "$.data.items[*].name");
    }

    private Object 항목(String name, String body, String field) {
        List<Object> values = JsonPath.read(body, "$.data.items[?(@.name=='" + name + "')]." + field);
        assertThat(values).as("%s 의 %s", name, field).hasSize(1);
        return values.get(0);
    }

    private Map<String, Integer> summary(String body) {
        return JsonPath.read(body, "$.data.summary");
    }

    private long 쿼리_수(String query) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get(BASE + "?" + query).header("Authorization", 토큰())).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private String 토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }
}

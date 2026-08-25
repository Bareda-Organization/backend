package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학원 격리를 HTTP 왕복으로 고정한다(완료 조건 6) — 단건 조회의 {@code 403} 과 목록 조회의
 * <b>0건</b> 을 함께 단언한다.
 *
 * <p>둘을 한 클래스에 둔 이유는 이 시스템의 사고 형태 때문이다 — 목록 쿼리에서 조건 하나가 빠져도
 * 단건 조회는 여전히 403 이라, 단건만 검사하면 새는 목록이 초록으로 통과한다(ARCHITECTURE §6.1).
 *
 * <p>대조군인 학원 B 행은 {@code JdbcTemplate} 으로 직접 읽는다 — 검사 대상인
 * {@code findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc} 로 대조군을 만들면 그 메서드가 잘못돼도
 * 대조군이 함께 틀려 아무것도 못 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AcademyScopeIsolationTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    private static final String LIST_PATH = "/api/v1/academy-scope-test/students";
    private static final String SINGLE_PATH = LIST_PATH + "/{studentId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<Long> academyAStudentIds;
    private List<Long> academyBStudentIds;

    @BeforeEach
    void 시드의_학원별_학생을_검사_대상과_독립적으로_읽는다() {
        academyAStudentIds = studentIdsOf(ACADEMY_A);
        academyBStudentIds = studentIdsOf(ACADEMY_B);

        assertThat(academyAStudentIds).as("학원 A 학생이 없으면 아래 단언이 공허하게 통과한다").isNotEmpty();
        assertThat(academyBStudentIds).as("학원 B 학생이 없으면 격리를 대조할 재료가 없다").isNotEmpty();
    }

    @Test
    void A학원_staff_가_B학원_학생을_단건_조회하면_403_ACADEMY_SCOPE_VIOLATION_이다() throws Exception {
        mockMvc.perform(get(SINGLE_PATH, academyBStudentIds.get(0)).header("Authorization", staffA()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    @Test
    void A학원_staff_는_자기_학원_학생을_단건_조회한다() throws Exception {
        Long studentId = academyAStudentIds.get(0);

        mockMvc.perform(get(SINGLE_PATH, studentId).header("Authorization", staffA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(studentId.intValue()));
    }

    /** 완료 조건 6 ② — 목록에서 조건이 빠지면 단건 403 이 그대로여도 여기서 걸린다. */
    @Test
    void A학원_staff_의_학생_목록에_B학원_학생이_0건이다() throws Exception {
        List<Long> returned = listAs(staffA(), null);

        assertThat(returned).containsExactlyInAnyOrderElementsOf(academyAStudentIds);
        assertThat(returned).doesNotContainAnyElementsOf(academyBStudentIds);
    }

    /** §1.5 — 요청이 실어 보낸 학원 식별자는 신뢰 대상 밖이라, 토큰의 범위가 이긴다. */
    @Test
    void A학원_staff_가_쿼리로_B학원을_지정하면_403_이고_B학원_학생은_응답되지_않는다() throws Exception {
        String body = mockMvc.perform(get(LIST_PATH).param("academy_id", String.valueOf(ACADEMY_B))
                        .header("Authorization", staffA()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(String.valueOf(academyBStudentIds.get(0)));
    }

    @Test
    void A학원_staff_가_쿼리로_자기_학원을_지정하면_자기_학원_목록을_받는다() throws Exception {
        assertThat(listAs(staffA(), ACADEMY_A)).containsExactlyInAnyOrderElementsOf(academyAStudentIds);
    }

    /** ARCHITECTURE §6.2 격리 예외 — 이 단언이 없으면 "전부 막는" 구현도 위 단언들을 통과한다. */
    @Test
    void system_admin_은_학원을_지정하지_않으면_전_학원_학생을_조회한다() throws Exception {
        List<Long> returned = listAs(systemAdmin(), null);

        assertThat(returned).containsAll(academyAStudentIds);
        assertThat(returned).containsAll(academyBStudentIds);
    }

    @Test
    void system_admin_은_학원을_지정하면_그_학원_학생만_조회한다() throws Exception {
        assertThat(listAs(systemAdmin(), ACADEMY_B))
                .containsExactlyInAnyOrderElementsOf(academyBStudentIds);
    }

    private List<Long> listAs(String bearer, Long requestedAcademyId) throws Exception {
        var request = get(LIST_PATH).header("Authorization", bearer);
        if (requestedAcademyId != null) {
            request = request.param("academy_id", String.valueOf(requestedAcademyId));
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseIds(body);
    }

    private static List<Long> parseIds(String jsonArray) {
        String trimmed = jsonArray.replace("[", "").replace("]", "").trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(trimmed.split(",")).map(String::trim).map(Long::valueOf).toList();
    }

    private List<Long> studentIdsOf(Long academyId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM student WHERE academy_id = ? AND deleted_at IS NULL", Long.class, academyId);
    }

    private String staffA() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String systemAdmin() {
        return "Bearer " + tokenProvider.createAccessToken(2L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}

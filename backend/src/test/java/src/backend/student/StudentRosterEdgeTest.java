package src.backend.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.security.AuthUser;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.command.StudentCommandService;
import src.backend.student.dto.StudentRegisterRequest;

/**
 * 학생 명단의 가장자리 값 — 퇴원 표식만 남은 자녀가 학부모 자녀 목록에 없고, 빈 문자열 반 이름이 반 개수에 잡히지 않으며,
 * 강제 추가용 최소 등록이 이름 길이를 막는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentRosterEdgeTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final long SIBLINGS_GUARDIAN_ACCOUNT = 5L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudentCommandService commandService;

    private final AuthUser staff = new AuthUser(2L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE, false);

    @Test
    void 퇴원_표식만_있고_연결이_남은_자녀는_학부모_자녀_목록에_없다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", SIBLING_1_ID);

        mockMvc.perform(get("/api/v1/me/students")
                        .header("Authorization", 토큰(tokenProvider, SIBLINGS_GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_1_ID)).isEmpty());
    }

    @Test
    void 반_이름을_빈_문자열로_등록하면_NULL_로_저장된다() {
        long id = commandService.register(staff,
                new StudentRegisterRequest("빈반학생", null, null, null, null, "", null, true), null);

        assertThat(jdbcTemplate.queryForObject("SELECT class_name FROM student WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    void 반_이름을_공백만으로_등록해도_NULL_로_저장된다() {
        long id = commandService.register(staff,
                new StudentRegisterRequest("공백반학생", null, null, null, null, "   ", null, true), null);

        assertThat(jdbcTemplate.queryForObject("SELECT class_name FROM student WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    void 빈_반_이름_학생을_등록해도_반_개수와_반_이름_목록_길이가_같다() throws Exception {
        commandService.register(staff, new StudentRegisterRequest("빈반학생", null, null, null, null, "", null, true), null);

        MvcResult result = mockMvc.perform(get("/api/v1/staff/students")
                        .header("Authorization", 토큰(tokenProvider, 2L, ACADEMY_A, Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Integer classCount = JsonPath.read(body, "$.data.summary.class_count");
        Integer classNames = JsonPath.read(body, "$.data.summary.class_names.length()");
        assertThat(classCount).isEqualTo(classNames);
    }

    @Test
    void 최소_등록은_빈_이름을_거부한다() {
        assertThatThrownBy(() -> commandService.registerMinimal(ACADEMY_A, "  "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 최소_등록은_50자를_넘는_이름을_거부한다() {
        assertThatThrownBy(() -> commandService.registerMinimal(ACADEMY_A, "가".repeat(51)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 최소_등록은_50자_이름을_받는다() {
        long id = commandService.registerMinimal(ACADEMY_A, "가".repeat(50));

        assertThat(jdbcTemplate.queryForObject("SELECT length(name) FROM student WHERE id = ?", Integer.class, id))
                .isEqualTo(50);
    }
}

package src.backend.student.photo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학생 사진의 개인정보 경계 — 목표 12 ⑤(`API_SPEC §1.12` · `ERD student`).
 *
 * <p>정본 문면은 <b>"{@code photo_url} 은 매니저 앱 · 관계자 웹 · 메인 관리자 콘솔에만 반환 —
 * 학부모·학생 앱 응답에 부재"</b> 다. 업로드가 되는 것만 검사하면 사진이 전 역할에 열린 채로 통과한다.
 *
 * <p><b>두 방향을 함께 단언한다</b> — 같은 학생에 대해 관계자 상세에는 있고 학부모·학생 앱에는 없다.
 * 한쪽만 보면 반대편이 검사되지 않는다. "없다" 만 보면 사진을 아무 데도 안 싣는 구현이 통과하고,
 * "있다" 만 보면 전 역할에 여는 구현이 통과한다.
 *
 * <p>재료로 <b>실제로 사진이 있는 학생</b>을 쓴다. 사진이 없는 학생으로 "부재" 를 단언하면 값이
 * 애초에 없어서 통과하는 것이라 아무것도 검사하지 않는다.
 *
 * <p>표현을 갈라 둔 것이 이 조항을 지키는 수단이다(§1.12 · 횡단 규칙 8·14) — 학부모·학생 앱은
 * {@code ChildListResponse}·{@code ChildLinkedResponse}·{@code MeResponse} 를 보고, 관계자 웹만
 * {@code StudentDetailResponse} 를 본다. {@code StudentDetailResponse} 에 역할 분기를 두는 방식을
 * 고르지 않은 이유는, 그러면 다음에 필드를 더하는 사람이 그 분기를 못 보고 새기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentPhotoPrivacyTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final String CHILDREN = "/api/v1/me/students";
    private static final String LINK_REQUESTS = "/api/v1/me/students/link-requests";
    private static final String LINK_CODE = "/api/v1/me/link-code";
    private static final String LINK = "/api/v1/me/students/link";
    private static final String ME = "/api/v1/me";
    private static final String STAFF_STUDENT = "/api/v1/staff/students/";

    /** 형제 S1·S2 의 보호자({@code parentA1}) — {@code studentA4} 와는 아직 연결돼 있지 않다. */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    /** 계정이 붙은 학원 A 학생({@code studentA4}) — 본인 프로필 조회의 주체다. */
    private static final long STUDENT_A4_ACCOUNT = 10L;

    private static final long STUDENT_A4_ID = 4L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    private static final String PHOTO_URL = "/files/photos/p5t6-privacy.png";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 두 학생 모두에 사진을 심는다 — 값이 있는 상태에서만 "부재" 단언이 무언가를 검사한다. */
    @BeforeEach
    void 사진을_심는다() {
        jdbcTemplate.update("UPDATE student SET photo_url = ? WHERE id IN (?, ?)",
                PHOTO_URL, SIBLING_1_ID, STUDENT_A4_ID);
    }

    /** 대조군 — 같은 사진이 관계자 웹에는 그대로 나온다(§1.12). */
    @Test
    void 관계자_상세_응답에는_photo_url_이_있다() throws Exception {
        mockMvc.perform(get(STAFF_STUDENT + SIBLING_1_ID)
                        .header("Authorization", 토큰(1L, ACADEMY_A, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photo_url").value(PHOTO_URL));
    }

    @Test
    void 학부모_자녀_목록_응답에는_photo_url_이_부재한다() throws Exception {
        mockMvc.perform(get(CHILDREN)
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_1_ID))
                        .isNotEmpty())
                .andExpect(jsonPath("$.data.items[*].photo_url").doesNotExist());
    }

    /**
     * 연결 완료 응답도 자녀 표현이다(§3.4) — 목록만 막으면 <b>연결 직후 한 번</b> 사진이 새는 경로가
     * 남는다.
     */
    @Test
    void 학부모_연결_완료_응답에는_photo_url_이_부재한다() throws Exception {
        String code = 연결_코드를_받는다();

        mockMvc.perform(post(LINK)
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(STUDENT_A4_ID)))
                .andExpect(jsonPath("$.data.photo_url").doesNotExist());
    }

    /** 학생 본인 앱의 프로필(§2.10) — 자기 사진도 앱 응답 대상이 아니다. */
    @Test
    void 학생_본인_프로필_응답에는_photo_url_이_부재한다() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", 토큰(STUDENT_A4_ACCOUNT, ACADEMY_A, Role.STUDENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(STUDENT_A4_ID)))
                .andExpect(jsonPath("$.data.photo_url").doesNotExist());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /** 연결 3단계 중 앞 둘을 밟아 코드를 얻는다 — 이 클래스가 검사하는 것은 셋째 단계의 응답뿐이다. */
    private String 연결_코드를_받는다() throws Exception {
        mockMvc.perform(post(LINK_REQUESTS)
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"student_login_id\":\"%s\"}".formatted(SeedFixtures.STUDENT_A4_LOGIN_ID)))
                .andExpect(status().isCreated());

        MvcResult issued = mockMvc.perform(post(LINK_CODE)
                        .header("Authorization", 토큰(STUDENT_A4_ACCOUNT, ACADEMY_A, Role.STUDENT)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(issued.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.code");
    }

    private String 토큰(long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}

package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.request.PageParams;
import src.backend.global.security.JwtTokenProvider;

/**
 * §6.1~§6.3 {@code /admin/academies} — ACAD-01~04 · O-01.
 *
 * <p>메인 관리자는 학원 소속이 부재해 토큰의 {@code academyId} 가 {@code null} 이다 — 이 컨트롤러가
 * 학원 격리의 예외 구역이라는 사실이 토큰 형태에서부터 드러난다(§1.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAcademyControllerTest {

    /** 학원 코드가 쓰는 글자(Ruling 140) — 혼동 문자 {@code I}·{@code O}·{@code 0}·{@code 1} 이 빠져 있다. */
    private static final String CODE_PATTERN = "^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 생성된 코드는 혼동 문자를 빼고 8자여야 한다(Ruling 140).
     *
     * <p>학원 코드는 가입 화면에서 사람이 구두로 전하고 손으로 입력하는 값이라, {@code O}/{@code 0} 이
     * 섞이면 오입력이 곧 "학원을 못 찾음" 으로 나타난다. 시드의 {@code BARAEDA-A} 같은 고정값은 이
     * 형식을 따르지 않으므로 <b>생성기 산출물에만</b> 단언을 건다.
     */
    @Test
    void 학원_등록_응답의_code_는_혼동_문자_IO01_을_포함하지_않는_대문자_영숫자_8자다() throws Exception {
        String code = 등록하고_코드를_받는다("P3T1코드형식학원", "서울");

        assertThat(code)
                .as("혼동 문자 I·O·0·1 이 빠진 32자 알파벳에서 8자를 뽑아야 한다")
                .matches(CODE_PATTERN);
    }

    /**
     * 같은 이름·지역으로 두 번 등록해도 코드는 달라야 한다.
     *
     * <p>이름에서 코드를 유도하는 구현이면 두 코드가 같아지고, 그러면 가입 화면에서 학원을 고르는
     * 수단 자체가 무너진다 — {@code academy.code} 는 UNIQUE 라 애초에 두 번째 저장이 실패한다.
     */
    @Test
    void 같은_이름과_지역으로_두_번_등록해도_code_가_서로_다르다() throws Exception {
        String first = 등록하고_코드를_받는다("P3T1중복이름학원", "부산");
        String second = 등록하고_코드를_받는다("P3T1중복이름학원", "부산");

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * 학원명 + 지역 중복은 <b>경고</b>지 에러가 아니다(§6.2) — 분원이 실제로 존재할 수 있다.
     *
     * <p>{@code 201} 을 함께 단언하는 이유는, 중복을 에러로 만든 구현이 {@code warnings} 단언만으로는
     * 잡히지 않기 때문이다 — 4xx 응답에는 애초에 {@code warnings} 를 볼 자리가 없다.
     */
    @Test
    void 학원명과_지역이_같은_학원을_또_등록하면_201_로_저장되고_warnings_에_DUPLICATE_NAME_REGION_이_담긴다()
            throws Exception {
        등록하고_코드를_받는다("P3T1경고학원", "대구");

        mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(등록_본문("P3T1경고학원", "대구")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warnings[0]").value("DUPLICATE_NAME_REGION"))
                .andExpect(jsonPath("$.data.academy_id").isNumber());
    }

    /** 처음 등록에는 경고가 붙지 않는다 — 붙는다면 위 단언은 중복 판정이 아니라 상수를 검사한 것이 된다. */
    @Test
    void 이름과_지역이_겹치지_않는_학원_등록에는_경고가_붙지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(등록_본문("P3T1무경고학원", "광주")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warnings").isEmpty());
    }

    /**
     * {@code code} 는 수정 대상 밖이다(§6.3) — 본문에 실어 보내도 저장된 값이 변하지 않아야 한다.
     *
     * <p>수정 응답만 보지 않고 <b>다시 조회해</b> 확인한다. 응답 조립이 요청 본문을 그대로 되돌려
     * 주는 구현이면 응답만으로는 "저장되지 않았다" 를 판정할 수단이 부재하다.
     */
    @Test
    void PATCH_로_code_를_바꾸려_해도_저장된_code_가_변하지_않는다() throws Exception {
        long academyId = 등록하고_식별자를_받는다("P3T1코드고정학원", "인천");
        String before = 상세의_코드(academyId);

        mockMvc.perform(patch("/api/v1/admin/academies/" + academyId)
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HACKED99\",\"name\":\"P3T1코드고정학원수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("P3T1코드고정학원수정"))
                .andExpect(jsonPath("$.data.code").value(before));

        assertThat(상세의_코드(academyId))
                .as("code 는 서버 생성값이라 수정 경로 자체가 부재해야 한다")
                .isEqualTo(before);
    }

    /** 비활성화는 상태만 바꾸고 물리 삭제하지 않는다(§6.3) — 상세 조회가 계속 200 이어야 한다. */
    @Test
    void 학원을_비활성화해도_상세_조회는_200_이고_status_만_inactive_가_된다() throws Exception {
        long academyId = 등록하고_식별자를_받는다("P3T1비활성학원", "세종");

        비활성화한다(academyId);

        mockMvc.perform(get("/api/v1/admin/academies/" + academyId).header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("inactive"));
    }

    /**
     * 페이지 크기 상한(§1.8 최대 100)은 <b>양쪽 경계를 다</b> 본다 — 100 은 통과, 101 은 거부다.
     *
     * <p>거부 쪽만 보면 상한을 1 로 낮춘 구현도 통과하고, 통과 쪽만 보면 상한이 없는 구현도 통과한다.
     * 상한값은 {@link PageParams#MAX_SIZE} 를 그대로 읽어, 값이 바뀌면 이 테스트가 따라간다.
     */
    @Test
    void 목록_조회는_size_상한값까지_받고_그_다음_값은_422_로_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("size", String.valueOf(PageParams.MAX_SIZE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(PageParams.MAX_SIZE));

        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("size", String.valueOf(PageParams.MAX_SIZE + 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 목록 응답은 §1.8 봉투 그대로다 — 항목 배열과 페이지 정보 넷을 함께 싣는다. */
    @Test
    void 목록_응답은_items_와_page_size_total_count_has_next_를_함께_싣는다() throws Exception {
        등록하고_코드를_받는다("P3T1봉투학원QQ", "제주");

        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("q", "P3T1봉투학원QQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(PageParams.DEFAULT_SIZE))
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false));
    }

    /** 상태 필터는 그 상태만 남긴다 — 걸지 않으면 두 상태가 함께 나온다(§6.1). */
    @Test
    void 목록_조회의_status_필터는_그_상태의_학원만_남긴다() throws Exception {
        등록하고_코드를_받는다("P3T1필터학원WW", "강원");
        long inactiveId = 등록하고_식별자를_받는다("P3T1필터학원WW비활성", "강원");
        비활성화한다(inactiveId);

        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("q", "P3T1필터학원WW"))
                .andExpect(jsonPath("$.data.total_count").value(2));

        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("q", "P3T1필터학원WW")
                        .param("status", "inactive"))
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("inactive"));
    }

    /**
     * 허용 목록 밖 정렬 필드는 {@code 422} 다.
     *
     * <p>그대로 넘기면 Spring Data 가 {@code PropertyReferenceException} 을 던져 {@code 500} 이 되고,
     * 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나른다.
     */
    @Test
    void 허용_목록_밖의_sort_필드는_422_로_거부된다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("sort", "password_hash:asc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 상세는 관계자 수·소속 사용자 수를 함께 센다(§6.1·§6.3).
     *
     * <p>퇴사한 관계자는 {@code staff_count} 에 들어가지 않지만 {@code staff_accounts[]} 에는 남는다 —
     * 정원 판정의 기준(재직자)과 이력 표시의 기준(전체)이 다르기 때문이다. 두 값을 한 테스트에서
     * 함께 보는 이유는, 하나만 보면 "둘 다 재직만" 이나 "둘 다 전체" 인 구현이 통과하기 때문이다.
     */
    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) VALUES ('P3T1DTLQQ', 'P3T1상세학원', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), 'p3t1staffon', 'x', '재직관계자', "
                    + "'010-0000-1001', 'staff', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), 'p3t1staffoff', 'x', '퇴사관계자', "
                    // 계정 자체는 살아 있고 그 학원에서만 퇴사한 상태다 — account.status 에는 'inactive' 값이
                    // 부재하며(CHECK: pending·active·rejected·blocked), 퇴사는 academy_staff.status 가 표현한다.
                    + "'010-0000-1002', 'staff', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), 'p3t1parent', 'x', '학부모', "
                    + "'010-0000-1003', 'parent', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), 'p3t1driver', 'x', '기사', "
                    + "'010-0000-1004', 'driver', 'active')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1staffoff'), 'inactive')",
            "INSERT INTO academy_staff (academy_id, account_id, status) VALUES "
                    + "((SELECT id FROM academy WHERE code = 'P3T1DTLQQ'), "
                    + "(SELECT id FROM account WHERE login_id = 'p3t1staffon'), 'active')"
    })
    void 학원_상세는_재직_관계자_수와_소속_사용자_수를_함께_반환한다() throws Exception {
        long academyId = 목록에서_식별자를_찾는다("P3T1DTLQQ");

        mockMvc.perform(get("/api/v1/admin/academies/" + academyId).header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("P3T1DTLQQ"))
                .andExpect(jsonPath("$.data.staff_count").value(1))
                .andExpect(jsonPath("$.data.user_count").value(2))
                .andExpect(jsonPath("$.data.staff_accounts.length()").value(2))
                .andExpect(jsonPath("$.data.staff_accounts[?(@.login_id == 'p3t1staffon')].status").value("active"))
                .andExpect(jsonPath("$.data.staff_accounts[?(@.login_id == 'p3t1staffoff')].status").value("inactive"))
                .andExpect(jsonPath("$.data.stats.moving_bus_count").value(0));
    }

    /** 미등록 학원 지정은 {@code 404 ACADEMY_NOT_FOUND} 다(§6.3). */
    @Test
    void 없는_학원의_상세_조회는_404_ACADEMY_NOT_FOUND_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/academies/99999999").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
    }

    /**
     * 메인 관리자가 아닌 역할은 {@code 403} 이다 — {@code ACADEMY_MANAGE} 는 메인 관리자만 보유한다.
     *
     * <p>이 단언이 없으면 학원 관계자가 <b>다른 학원</b>을 등록·비활성화할 수 있게 되고, 그것은 격리
     * 위반이 아니라 격리 예외 구역 자체가 열리는 형태다.
     */
    @Test
    void 메인_관리자가_아닌_계정의_학원_목록_조회는_403_이다() throws Exception {
        String staffToken = "Bearer " + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/admin/academies").header("Authorization", staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private String 등록_본문(String name, String region) {
        return "{\"name\":\"%s\",\"region\":\"%s\"}".formatted(name, region);
    }

    private JsonNode 등록한다(String name, String region) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(등록_본문(name, region)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String 등록하고_코드를_받는다(String name, String region) throws Exception {
        return 등록한다(name, region).path("code").asText();
    }

    private long 등록하고_식별자를_받는다(String name, String region) throws Exception {
        return 등록한다(name, region).path("academy_id").asLong();
    }

    private void 비활성화한다(long academyId) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/academies/" + academyId)
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"inactive\"}"))
                .andExpect(status().isOk());
    }

    private String 상세의_코드(long academyId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/academies/" + academyId)
                        .header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("code").asText();
    }

    /** {@code @Sql} 로 심은 행의 식별자는 목록 조회로 되찾는다 — 자동 생성 키라 SQL 문에 적을 수 없다. */
    private long 목록에서_식별자를_찾는다(String code) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/academies")
                        .header("Authorization", 메인관리자_토큰())
                        .param("q", code))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("items").get(0).path("id").asLong();
    }
}

package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * Phase 3 목표 3 — 거절된 계정이 재신청으로 승인 큐에 <b>다시 뜨는가</b>.
 *
 * <p>계정 상태만 {@code pending} 으로 되돌리고 새 {@code signup_request} 행을 만들지 않으면, 관계자가
 * 재심사할 대상이 부재해 계정이 영구히 {@code pending} 에 갇힌다 — 계정 상태만 보는 단언은 그 구현을
 * 통과시킨다(ERD §4.1 "재신청마다 행 추가").
 *
 * <p>거절과 재신청은 {@code AuthFlowIntegrationTest} 도 다루지만 그쪽은 raw {@code UPDATE} 로
 * {@code rejected} 를 만든다 — 승인 경로가 없던 Phase 2 의 사정이다. 여기서는 <b>관계자가 실제로
 * 거절한 결과</b>가 재신청의 입력이 되므로, 거절이 만든 상태와 재신청이 요구하는 상태가 어긋나면
 * 이 클래스에서 드러난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignupReapplyQueueTest {

    private static final long ACADEMY_A = 1L;

    private static final long STAFF_A_ACCOUNT = 2L;

    /** 시드 {@code signup_request} 2번 — {@code parentPending}(계정 8)의 학부모 가입 요청. */
    private static final long FIRST_REQUEST = 2L;

    private static final long PARENT_ACCOUNT = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * JPA 가 쌓아 둔 변경을 DB 로 내보낸 뒤 raw SQL 로 읽는다.
     *
     * <p>테스트가 트랜잭션을 열고 있어 서비스의 {@code @Transactional} 이 그것에 합류한다 — 메서드가
     * 끝나도 커밋·flush 가 일어나지 않아, 이 호출이 없으면 {@link JdbcTemplate} 이 <b>변경 전</b> 값을
     * 읽는다. 프로덕션에서는 요청마다 트랜잭션이 끝나므로 이 사정이 부재하다.
     */
    private void 반영한다() {
        entityManager.flush();
    }

    /**
     * 거절 → 재신청 → 승인 큐 재등장까지 한 흐름으로 밟는다.
     *
     * <p>세 단계를 나누지 않는 이유는 각 단계의 입력이 앞 단계의 산출물이기 때문이다 — 픽스처로 심은
     * {@code rejected} 계정으로 재신청하면 "거절이 만든 상태" 가 아니라 "테스트가 만든 상태" 를
     * 검사하게 된다.
     */
    @Test
    void 거절된_계정이_재신청하면_pending_으로_돌아오고_관계자_목록에_새_요청이_다시_뜬다() throws Exception {
        mockMvc.perform(post("/api/v1/staff/signup-requests/" + FIRST_REQUEST + "/decide")
                        .header("Authorization", 관계자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": false, \"reject_reason\": \"서류 미비\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("rejected"));

        assertThat(계정_상태()).isEqualTo("rejected");
        assertThat(대기_요청_식별자들())
                .as("거절한 요청이 큐에 남아 있으면 관계자가 같은 건을 다시 처리하게 된다")
                .isEmpty();

        mockMvc.perform(post("/api/v1/auth/signup/reapply")
                        .header("Authorization", 거절된_학부모_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"academy_id\": \"%d\"}".formatted(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));

        assertThat(계정_상태()).isEqualTo("pending");
        assertThat(대기_요청_식별자들())
                .as("재신청이 새 행을 만들지 않으면 관계자가 재심사할 대상이 부재해 계정이 영구히 pending 에 갇힌다")
                .hasSize(1)
                .doesNotContain(FIRST_REQUEST);
    }

    /**
     * 재신청으로 학원을 바꾸면 <b>새 학원</b>의 큐에 뜨고 옛 학원의 큐에서는 사라진다.
     *
     * <p>{@code signup_request} 가 계정이 아니라 <b>행</b>으로 학원을 들고 있다는 것이 이 동작의 근거다
     * (ERD §4.1 "재신청 시 학원이 달라질 가능성 존재"). 계정의 학원만 갱신하고 요청 행을 재사용하면
     * 옛 학원 관계자가 남의 학원 지원자를 계속 보게 된다.
     */
    @Test
    void 재신청에서_학원을_바꾸면_새_학원의_큐에만_뜬다() throws Exception {
        mockMvc.perform(post("/api/v1/staff/signup-requests/" + FIRST_REQUEST + "/decide")
                        .header("Authorization", 관계자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": false, \"reject_reason\": \"학원 착오\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/signup/reapply")
                        .header("Authorization", 거절된_학부모_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"academy_id\": \"2\"}"))
                .andExpect(status().isOk());

        assertThat(대기_요청_식별자들()).as("옛 학원 큐에 남으면 안 된다").isEmpty();
        assertThat(대기_요청_식별자들(2L, 3L)).hasSize(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 관계자_토큰() {
        return "Bearer "
                + tokenProvider.createAccessToken(STAFF_A_ACCOUNT, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    /** 거절 직후의 계정 상태를 그대로 실은 토큰 — 재신청은 {@code rejected} 에게만 열린다(§1.4). */
    private String 거절된_학부모_토큰() {
        return "Bearer "
                + tokenProvider.createAccessToken(PARENT_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.REJECTED);
    }

    private String 계정_상태() {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, PARENT_ACCOUNT);
    }

    /** 학원 A 관계자가 보는 대기 요청 식별자 — 시드의 다른 계정 요청과 섞이지 않게 계정으로 좁힌다. */
    private List<Long> 대기_요청_식별자들() throws Exception {
        return 대기_요청_식별자들(ACADEMY_A, STAFF_A_ACCOUNT);
    }

    private List<Long> 대기_요청_식별자들(long academyId, long staffAccountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/staff/signup-requests")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(
                                staffAccountId, academyId, Role.STAFF, AccountStatus.ACTIVE)))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> ids = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.items[?(@.name == '조대기')].request_id");
        return ids.stream().map(Integer::longValue).toList();
    }
}

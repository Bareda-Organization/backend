package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 같은 가입 요청의 동시 처리와 같은 계정의 동시 재신청은 한 건만 성립한다(BR-063 · §5.2 · §2.4).
 *
 * <p>요청 상태를 읽은 시점에 판정하면 두 요청이 모두 {@code pending} 을 보고 진행한다 — 학부모 수락 이중
 * 클릭은 보호자 행 UNIQUE 위반으로 {@code 500}, 재신청 이중 제출은 처리 불가 요청 행이 큐에 남는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 요청마다 자기 트랜잭션을 커밋해야 경쟁이 성립한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupDecisionConcurrencyTest {

    private static final String PARENT_LOGIN_ID = "br063parent";

    private static final String REJECTED_LOGIN_ID = "br063rejected";

    private static final long TIMEOUT_SECONDS = 60;

    /** 시드 관계자 A(학원 A). */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void 대기_학부모와_거절_학생을_만든다() {
        뒷정리한다();
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) VALUES
                (1, ?, 'x', '동시승인학부모', '010-0000-0631', 'parent', 'pending'),
                (1, ?, 'x', '동시재신청학생', '010-0000-0632', 'student', 'rejected')
                """, PARENT_LOGIN_ID, REJECTED_LOGIN_ID);
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at)
                SELECT id, 1, role, 'staff', CASE WHEN status = 'pending' THEN 'pending' ELSE 'rejected' END, now()
                FROM account WHERE login_id IN (?, ?)
                """, PARENT_LOGIN_ID, REJECTED_LOGIN_ID);
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM guardian WHERE account_id IN (SELECT id FROM account WHERE login_id = ?)",
                PARENT_LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id IN (?, ?)", PARENT_LOGIN_ID, REJECTED_LOGIN_ID);
    }

    @Test
    void 같은_가입_요청을_동시에_수락하면_한_건만_성공하고_나머지는_409_다() throws Exception {
        long requestId = jdbcTemplate.queryForObject("""
                SELECT r.id FROM signup_request r JOIN account a ON a.id = r.account_id WHERE a.login_id = ?
                """, Long.class, PARENT_LOGIN_ID);
        String staffToken = "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, 1L, Role.STAFF,
                AccountStatus.ACTIVE);

        List<MvcResult> results = 동시에(() -> mockMvc.perform(post("/api/v1/staff/signup-requests/" + requestId
                        + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true, \"link\": {\"student_ids\": []}}"))
                .andReturn());

        assertThat(상태들(results)).as("본문=%s", 본문들(results)).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void 같은_계정이_동시에_재신청하면_한_건만_성공하고_요청_행도_하나만_는다() throws Exception {
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class,
                REJECTED_LOGIN_ID);
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, 1L, Role.STUDENT,
                AccountStatus.REJECTED);

        List<MvcResult> results = 동시에(() -> mockMvc.perform(post("/api/v1/auth/signup/reapply")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"academy_id\": \"1\"}"))
                .andReturn());

        assertThat(상태들(results)).as("본문=%s", 본문들(results)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM signup_request WHERE account_id = ?",
                Integer.class, accountId)).as("거절 1 + 재신청 1").isEqualTo(2);
    }

    private List<MvcResult> 동시에(Callable<MvcResult> call) throws Exception {
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> gated = () -> {
                출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return call.call();
            };
            Future<MvcResult> first = pool.submit(gated);
            Future<MvcResult> second = pool.submit(gated);
            출발.countDown();
            return List.of(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Integer> 상태들(List<MvcResult> results) {
        return results.stream().map(result -> result.getResponse().getStatus()).toList();
    }

    private List<String> 본문들(List<MvcResult> results) throws Exception {
        return List.of(results.get(0).getResponse().getContentAsString(StandardCharsets.UTF_8),
                results.get(1).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}

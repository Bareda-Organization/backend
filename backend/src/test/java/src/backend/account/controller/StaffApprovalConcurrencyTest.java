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

import com.jayway.jsonpath.JsonPath;

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
 * 관계자 승인 <b>엔드포인트</b>의 동시성(Phase 3 목표 2 ②) — 같은 학원의 관계자 가입 요청 2건이
 * {@code POST /admin/staff-signup-requests/{id}/decide} 로 겹쳐 들어오는 형태다.
 *
 * <p>{@code AcademyStaffQuotaConcurrencyTest} 와 다른 것을 본다 — 그쪽은 판정 지점
 * ({@code AcademyStaffQuota})을 직접 불러 <b>{@code BusinessException} 의 코드</b>를 확인하고,
 * 여기는 그 예외가 <b>HTTP 응답까지 옮겨지는지</b>를 본다. 응답 변환 경로가 빠지면 판정은 멀쩡한 채로
 * 사용자에게 {@code 500} 이 나가고, 그 사고는 엔드포인트를 두드려야만 드러난다.
 *
 * <p>이 클래스만 {@code @Transactional} 이 부재하다 — 테스트가 트랜잭션을 하나 열고 있으면 두
 * 요청이 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다. 대신 만든 행을
 * {@link #뒷정리한다()} 가 직접 지운다.
 *
 * <p>실패한 쪽이 선검사에서 걸렸는지 DB 조건부 UNIQUE 에서 걸렸는지는 타이밍이 정하며 여기서 가리지
 * 않는다 — <b>어느 쪽이든 같은 응답이어야 한다</b>는 것이 이 단언의 내용이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffApprovalConcurrencyTest {

    private static final String ACADEMY_CODE = "P3T2CONC";

    private static final String FIRST_LOGIN_ID = "p3t2conc1";

    private static final String SECOND_LOGIN_ID = "p3t2conc2";

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long academyId;

    private long firstRequestId;

    private long secondRequestId;

    @BeforeEach
    void 관계자가_없는_학원에_대기_요청_2건을_만든다() {
        뒷정리한다();
        jdbcTemplate.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, ?, 'active')",
                ACADEMY_CODE, "P3T2동시승인학원", "울산");
        academyId = jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, ACADEMY_CODE);
        firstRequestId = 대기_요청을_만든다(FIRST_LOGIN_ID, "010-0000-5001");
        secondRequestId = 대기_요청을_만든다(SECOND_LOGIN_ID, "010-0000-5002");
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN "
                + "(SELECT id FROM academy WHERE code = ?)", ACADEMY_CODE);
        jdbcTemplate.update("DELETE FROM signup_request WHERE account_id IN "
                + "(SELECT id FROM account WHERE login_id IN (?, ?))", FIRST_LOGIN_ID, SECOND_LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id IN (?, ?)", FIRST_LOGIN_ID, SECOND_LOGIN_ID);
        jdbcTemplate.update("DELETE FROM academy WHERE code = ?", ACADEMY_CODE);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 STAFF_QUOTA_EXCEEDED} 다.
     *
     * <p>애플리케이션 선검사만 있으면 두 요청이 서로의 미커밋 INSERT 를 보지 못한 채 <b>둘 다</b>
     * 검사를 지나고, 그 뒤 조건부 UNIQUE 가 하나를 거부해 {@code 500} 이 나간다 — 그러면 사용자에게
     * "서버가 고장났다" 와 "정원이 찼다" 가 구별되지 않는다.
     */
    @Test
    void 같은_학원의_관계자_승인_요청_두_건이_동시에_들어오면_성공_1건_실패_1건이다() throws Exception {
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> 첫째 = pool.submit(승인_호출(firstRequestId, 출발));
            Future<MvcResult> 둘째 = pool.submit(승인_호출(secondRequestId, 출발));
            출발.countDown();

            List<Integer> statuses = List.of(
                    첫째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponse().getStatus(),
                    둘째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
            List<String> bodies = List.of(
                    본문(첫째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                    본문(둘째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)));

            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .as("성공은 정확히 1건 — 2건이면 정원이 샜고 0건이면 정상 승인까지 막혔다. 실제 응답=%s", bodies)
                    .isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 409).count())
                    .as("실패는 500 이 아니라 409 여야 한다. 실제 응답=%s", bodies)
                    .isEqualTo(1);
            assertThat(bodies.stream()
                    .filter(body -> body.contains("\"error\""))
                    .map(body -> (String) JsonPath.read(body, "$.error.code"))
                    .toList())
                    .containsExactly("STAFF_QUOTA_EXCEEDED");
            assertThat(재직_관계자_수())
                    .as("응답과 무관하게 DB 에 남은 재직자도 1명이어야 한다")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<MvcResult> 승인_호출(long requestId, CountDownLatch 출발) {
        return () -> {
            출발.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + requestId + "/decide")
                            .header("Authorization", 메인관리자_토큰())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accept\": true}"))
                    .andReturn();
        };
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private long 대기_요청을_만든다(String loginId, String phone) {
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (?, ?, 'x', ?, ?, 'staff', 'pending')
                """, academyId, loginId, "동시승인" + loginId, phone);
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status,
                                            requested_at)
                VALUES ((SELECT id FROM account WHERE login_id = ?), ?, 'staff', 'system_admin', 'pending', now())
                """, loginId, academyId);
        return jdbcTemplate.queryForObject(
                "SELECT r.id FROM signup_request r JOIN account a ON a.id = r.account_id WHERE a.login_id = ?",
                Long.class, loginId);
    }

    private int 재직_관계자_수() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM academy_staff WHERE academy_id = ? AND status = 'active'",
                Integer.class, academyId);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §2.2·§2.3·§2.4 {@code POST /auth/signup} · {@code GET /auth/signup-status} ·
 * {@code POST /auth/signup/reapply} — AUTH-01·AUTH-03.
 *
 * <p>{@code pending}·{@code rejected} 상태 계정은 {@link Account}(Phase 1) 의 정적 팩토리로
 * 곧장 만들 수 없다({@code forSignup} 은 항상 {@code pending} 으로 시작하고, {@code rejected} 는
 * 관리자 승인 절차(Task 4/5 범위)를 거쳐야 한다) — {@code @Sql} 로 고정 상태 행을 직접 심는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 가입_직후_계정_상태는_pending_이다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("P2T3SGNQQQQ", "학원P2T3SGN가입", "서울", null, null));
        String payload = """
                {
                  "role": "parent",
                  "login_id": "p2t3signupqqqq",
                  "password": "password1234",
                  "name": "가입테스트",
                  "phone": "010-0000-0001",
                  "academy_id": "%d"
                }
                """.formatted(academy.getId());

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.account_status").value("pending"));

        assertThat(accountRepository.findByLoginId("p2t3signupqqqq").orElseThrow().getStatus())
                .isEqualTo(AccountStatus.PENDING);
    }

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3STAQQQQ', '학원P2T3STA조회', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3STAQQQQ'), "
                    + "'p2t3statusqqqq', 'x', '조회테스트', '010-0000-0002', 'parent', 'pending')",
            "INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at) "
                    + "VALUES ((SELECT id FROM account WHERE login_id = 'p2t3statusqqqq'), "
                    + "(SELECT id FROM academy WHERE code = 'P2T3STAQQQQ'), 'parent', 'staff', 'pending', now())"
    })
    void pending_계정이_signup_status_를_부르면_200_이다() throws Exception {
        Long accountId = accountRepository.findByLoginId("p2t3statusqqqq").orElseThrow().getId();
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3STAQQQQ"))
                .findFirst().orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.PENDING);

        mockMvc.perform(get("/api/v1/auth/signup-status").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3RJAQQQQ', '학원P2T3RJA재신청', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3RJAQQQQ'), "
                    + "'p2t3pendingqqqq', 'x', '대기중', '010-0000-0003', 'parent', 'pending')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3RJAQQQQ'), "
                    + "'p2t3rejectedqqqq', 'x', '거절됨', '010-0000-0004', 'parent', 'rejected')"
    })
    void rejected_계정만_reapply_를_부를_수_있다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3RJAQQQQ"))
                .findFirst().orElseThrow().getId();
        Long pendingId = accountRepository.findByLoginId("p2t3pendingqqqq").orElseThrow().getId();
        Long rejectedId = accountRepository.findByLoginId("p2t3rejectedqqqq").orElseThrow().getId();
        String reapplyBody = "{\"academy_id\": \"%d\"}".formatted(academyId);

        String pendingToken = "Bearer " + tokenProvider.createAccessToken(pendingId, academyId, Role.PARENT,
                AccountStatus.PENDING);
        mockMvc.perform(post("/api/v1/auth/signup/reapply").header("Authorization", pendingToken)
                        .contentType(MediaType.APPLICATION_JSON).content(reapplyBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_PENDING"));

        String rejectedToken = "Bearer " + tokenProvider.createAccessToken(rejectedId, academyId, Role.PARENT,
                AccountStatus.REJECTED);
        mockMvc.perform(post("/api/v1/auth/signup/reapply").header("Authorization", rejectedToken)
                        .contentType(MediaType.APPLICATION_JSON).content(reapplyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));

        assertThat(accountRepository.findById(rejectedId).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.PENDING);
    }
}

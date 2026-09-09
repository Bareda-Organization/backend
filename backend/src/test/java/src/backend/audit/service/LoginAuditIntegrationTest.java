package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.Role;

/**
 * Phase 14 T1 목표 2 음성 대조용 — {@code LoginCommandService.login()} 이 남기는 감사 로그 행 수를
 * 분기별로 직접 센다.
 *
 * <p>가장 핵심 단언은 {@link #차단된_계정으로의_로그인_시도는_감사_로그를_한_건도_남기지_않는다} 다 —
 * {@code assertNotBlocked()} 가 비밀번호 대조보다 먼저 실행돼 어떤 감사 팩토리도 호출되지 않는다는
 * 설계를, 만약 이 경계가 무너져 {@code login_fail} 행이 하나라도 남으면(negative control #4) 이 테스트만
 * 잡아낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginAuditIntegrationTest {

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long createAccount(String academyCode, String loginId, String phone) {
        Academy academy = academyRepository.save(Academy.register(academyCode, "학원" + academyCode, "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(RAW_PASSWORD), "감사테스트", phone, null, Role.PARENT));
        return account.getId();
    }

    /** {@code AuthControllerTest#forceStatus} 와 같은 근거 — raw UPDATE 후 1차 캐시를 비워야 이후
     * {@code accountRepository} 조회·{@code login()} 이 방금 UPDATE 된 상태를 본다. */
    private void forceStatus(Long accountId, String status, int failedAttempts) {
        jdbcTemplate.update("UPDATE account SET status = ?, failed_attempts = ? WHERE id = ?",
                status, failedAttempts, accountId);
        entityManager.clear();
    }

    private String loginBody(String loginId, String password) {
        return "{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password);
    }

    private List<AuditLog> auditRowsFor(Long accountId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> accountId.equals(log.getActorAccountId()))
                .toList();
    }

    @Test
    void 로그인_성공은_감사_로그_LOGIN_SUCCESS_1건을_남긴다() throws Exception {
        Long accountId = createAccount("P14T1AUD01", "p14t1loginok1", "010-9000-0001");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p14t1loginok1", RAW_PASSWORD)))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditRowsFor(accountId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
    }

    @Test
    void 차단_전_단일_비밀번호_실패는_감사_로그_LOGIN_FAIL_1건을_남긴다() throws Exception {
        Long accountId = createAccount("P14T1AUD02", "p14t1loginng1", "010-9000-0002");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p14t1loginng1", "wrong-password")))
                .andExpect(status().isUnauthorized());

        List<AuditLog> rows = auditRowsFor(accountId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.LOGIN_FAIL);
    }

    @Test
    void 실패_누적이_상한에_닿으면_LOGIN_FAIL_행마다_남고_상한_시도에서만_BLOCK_행이_추가된다() throws Exception {
        Long accountId = createAccount("P14T1AUD03", "p14t1loginblk", "010-9000-0003");

        for (int i = 0; i < Account.MAX_FAILED_ATTEMPTS - 1; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("p14t1loginblk", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p14t1loginblk", "wrong-password")))
                .andExpect(status().isForbidden());

        List<AuditLog> rows = auditRowsFor(accountId);
        long failCount = rows.stream().filter(log -> log.getAction() == AuditAction.LOGIN_FAIL).count();
        long blockCount = rows.stream().filter(log -> log.getAction() == AuditAction.BLOCK).count();
        assertThat(failCount)
                .as("차단을 유발한 마지막 시도도 login_fail 행을 남긴다 — block 은 별행이다")
                .isEqualTo(Account.MAX_FAILED_ATTEMPTS);
        assertThat(blockCount).as("차단 행은 상한 도달 시도 1건에만 선다").isEqualTo(1);
        assertThat(rows.stream().filter(log -> log.getAction() == AuditAction.BLOCK).findFirst().orElseThrow()
                .isBlockEvent()).as("block 행은 block_event=true 다").isTrue();
    }

    @Test
    void 차단된_계정으로의_로그인_시도는_감사_로그를_한_건도_남기지_않는다() throws Exception {
        Long accountId = createAccount("P14T1AUD04", "p14t1blocked1", "010-9000-0004");
        forceStatus(accountId, "blocked", Account.MAX_FAILED_ATTEMPTS);
        assertThat(auditRowsFor(accountId))
                .as("차단 전이는 raw UPDATE 로 만들었으므로 이 시점 감사 행은 0건이어야 이 테스트가 성립한다")
                .isEmpty();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p14t1blocked1", RAW_PASSWORD)))
                .andExpect(status().isForbidden());

        assertThat(auditRowsFor(accountId))
                .as("assertNotBlocked() 가 비밀번호 대조보다 먼저 실행돼 어떤 감사 팩토리도 호출되지 않는다")
                .isEmpty();
    }
}

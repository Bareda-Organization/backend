package src.backend.audit.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * Phase 14 T1 목표 3·4 음성 대조용 — {@code /admin/audit-logs} 와 {@code /admin/login-history} 가
 * 서버 쪽 {@code category} 하드 필터를 지키는지, {@code academy_id}·{@code account_id} 필터가 미등록
 * 대상에서 404 를 내는지 직접 HTTP 로 확인한다.
 *
 * <p>{@link #login_history_는_data_access_카테고리_행을_반환하지_않는다} 와
 * {@link #audit_logs_는_login_카테고리_행을_반환하지_않는다} 가 negative control #6 을 겨눈다 — 두
 * 서비스가 {@code auditLogRepository.search(category, ...)} 에 넘기는 카테고리를 지우거나 무시하면,
 * {@code account_id} 로 상대 카테고리 전용 계정을 필터링했을 때 그 계정의 행이 새어 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditQueryControllerTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;
    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    private Long createAccount(String academyCode, String loginId) {
        Academy academy = academyRepository.save(Academy.register(academyCode, "학원" + academyCode, "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(RAW_PASSWORD), "감사조회테스트", "010-9100-0000", null, Role.PARENT));
        return account.getId();
    }

    @Test
    void login_history_는_data_access_카테고리_행을_반환하지_않는다() throws Exception {
        Long dataAccessOnlyAccountId = createAccount("P14T1AUD05", "p14t1queryda1");
        auditLogRepository.save(AuditLog.forDataAccessRead(null, dataAccessOnlyAccountId, "p14t1queryda1",
                "run_roster", 999L, Map.of("student_ids", List.of("1"), "fields", List.of("note")),
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/login-history")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(dataAccessOnlyAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void audit_logs_는_login_카테고리_행을_반환하지_않는다() throws Exception {
        Long loginOnlyAccountId = createAccount("P14T1AUD06", "p14t1queryln1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"p14t1queryln1\", \"password\": \"%s\"}".formatted(RAW_PASSWORD)));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(loginOnlyAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void audit_logs_는_academy_id_가_미등록이면_404_ACADEMY_NOT_FOUND_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("academy_id", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
    }

    @Test
    void login_history_는_account_id_가_미등록이면_404_ACCOUNT_NOT_FOUND_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/login-history")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }
}

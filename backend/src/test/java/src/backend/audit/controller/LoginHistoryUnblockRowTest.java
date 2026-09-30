package src.backend.audit.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * {@code GET /admin/login-history} 의 {@code unblock} 행은 <b>해제된 계정</b>을 가리킨다(BR-219 · API_SPEC §6.13
 * "계정별 이력") — 해제한 관리자는 감사 로그(§6.12)가 갖는다.
 *
 * <p>행이 처리자를 싣으면 계정별 차단·해제 이력이 끊긴다: 그 계정의 {@code account_id} 필터에 해제 행이 안 걸리고,
 * 관리자의 필터에는 남의 계정 해제가 걸린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginHistoryUnblockRowTest {

    private static final long ADMIN_ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void 해제_행은_해제된_계정의_account_id_와_login_id_를_싣고_그_계정_필터에_걸린다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("BR219AC01", "BR219학원", "서울", null, null));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "br219target",
                passwordEncoder.encode("password1234!"), "해제대상", "010-9200-0000", null, Role.PARENT));
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), ADMIN_ACCOUNT_ID, "admin", target.getId(),
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].account_id").value(String.valueOf(target.getId())))
                .andExpect(jsonPath("$.data.items[0].login_id").value("br219target"))
                .andExpect(jsonPath("$.data.items[0].block_event").value(true));

        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(ADMIN_ACCOUNT_ID))
                        .param("academy_id", String.valueOf(academy.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    private String 관리자() {
        return "Bearer " + tokenProvider.createAccessToken(ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }
}

package src.backend.audit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
                null, OffsetDateTime.now()));

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

    @Test
    void 차단_행과_해제_행은_block_action_으로_구분된다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R37BA01", "R37학원", "서울", null, null));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "r37target",
                passwordEncoder.encode("password1234!"), "차단대상", "010-3700-0000", null, Role.PARENT));
        OffsetDateTime blockedAt = OffsetDateTime.now().minusMinutes(10);
        auditLogRepository.save(AuditLog.forLoginBlock(academy.getId(), target.getId(), "r37target", "10.0.0.1",
                blockedAt));
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), ADMIN_ACCOUNT_ID, "admin", target.getId(),
                null, blockedAt.plusMinutes(5)));

        // 최신순 — 해제가 먼저, 차단이 다음(Ruling 394)
        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].block_action").value("unblock"))
                .andExpect(jsonPath("$.data.items[1].block_action").value("block"));
    }

    /**
     * 해제 행의 {@code account_id}·{@code login_id} 는 <b>해제된 계정</b>이라, 행에 실린 IP(처리한 관리자의 것 — R46-POLISH
     * Ruling 595)를 그대로 내보내면 "그 계정이 그 IP 로 접속했다" 로 읽힌다. 접속 이력 응답은 해제 행의 IP 를 비운다 —
     * 처리자 IP 는 감사 로그 목록이 갖는다.
     */
    @Test
    void 해제_행의_IP_는_해제된_계정의_접속_이력에_실리지_않는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R595AC01", "R595학원", "서울", null, null));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "r595target",
                passwordEncoder.encode("password1234!"), "해제대상", "010-5950-0000", null, Role.PARENT));
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), ADMIN_ACCOUNT_ID, "admin", target.getId(),
                "203.0.113.41", OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].block_action").value("unblock"))
                .andExpect(jsonPath("$.data.items[0].ip").doesNotExist());
    }

    /**
     * 해제 행은 해제한 메인 관리자 계정의 <b>현재 이름</b>을 {@code unblocked_by_name} 으로 싣는다(§6.13, Ruling 846 —
     * AUTH-06·O-03 의 "처리자·일시 이력"). 감사 행에 남은 로그인 아이디 스냅샷({@code stale-admin-login})이나 계정 식별자는
     * 싣지 않는다 — 이름만 건넨다.
     */
    @Test
    void 해제_행은_해제한_관리자의_현재_이름을_싣고_계정_식별자와_로그인_아이디는_싣지_않는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R846AC01", "R846학원", "서울", null, null));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "r846target",
                passwordEncoder.encode("password1234!"), "해제대상", "010-8460-0000", null, Role.PARENT));
        String adminName = accountRepository.findById(ADMIN_ACCOUNT_ID).orElseThrow().getName();
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), ADMIN_ACCOUNT_ID, "stale-admin-login",
                target.getId(), null, OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].block_action").value("unblock"))
                .andExpect(jsonPath("$.data.items[0].unblocked_by_name").value(adminName))
                .andExpect(jsonPath("$.data.items[0].unblocked_by").doesNotExist())
                .andExpect(content().string(not(containsString("stale-admin-login"))));
    }

    /**
     * 해제 행이 아닌 행(차단 · 로그인 성공)과 해제한 계정이 사라진 해제 행은 {@code unblocked_by_name} 이 {@code null} 이고
     * <b>키는 언제나 존재</b>한다(§6.13). 차단 행의 행위자는 차단된 본인이라 이름을 아는 계정이지만 싣지 않는다 —
     * 모든 행에 행위자 이름을 붙이는 구현을 가른다.
     */
    @Test
    void 해제_행이_아니거나_해제한_계정이_없으면_unblocked_by_name_은_null_이고_키는_존재한다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R846AC02", "R846학원2", "서울", null, null));
        Account target = accountRepository.save(Account.forSignup(academy.getId(), "r846other",
                passwordEncoder.encode("password1234!"), "차단대상", "010-8460-0001", null, Role.PARENT));
        OffsetDateTime base = OffsetDateTime.now().minusHours(1);
        auditLogRepository.save(AuditLog.forLoginSuccess(academy.getId(), target.getId(), "r846other", "10.0.0.1",
                base));
        auditLogRepository.save(AuditLog.forLoginBlock(academy.getId(), target.getId(), "r846other", "10.0.0.1",
                base.plusMinutes(1)));
        auditLogRepository.save(AuditLog.forAccountUnblock(academy.getId(), 987_654_321L, "gone-admin",
                target.getId(), null, base.plusMinutes(2)));

        // 최신순 — 해제(행위자 계정 없음) · 차단 · 로그인 성공
        mockMvc.perform(get("/api/v1/admin/login-history").header("Authorization", 관리자())
                        .param("account_id", String.valueOf(target.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].block_action").value("unblock"))
                .andExpect(jsonPath("$.data.items[1].block_action").value("block"))
                .andExpect(jsonPath("$.data.items[2].result").value("success"))
                .andExpect(jsonPath("$.data.items[0]", hasKey("unblocked_by_name")))
                .andExpect(jsonPath("$.data.items[1]", hasKey("unblocked_by_name")))
                .andExpect(jsonPath("$.data.items[2]", hasKey("unblocked_by_name")))
                .andExpect(jsonPath("$.data.items[0].unblocked_by_name").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].unblocked_by_name").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].unblocked_by_name").value(nullValue()));
    }

    private String 관리자() {
        return "Bearer " + tokenProvider.createAccessToken(ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }
}

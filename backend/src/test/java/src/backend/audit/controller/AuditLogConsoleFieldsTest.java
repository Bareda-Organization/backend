package src.backend.audit.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
 * R48 이 감사 로그 목록에 더한 {@code actor_name} · {@code detail_action} · {@code ip}(API_SPEC §6.13, Ruling 809).
 *
 * <p>행은 학원 하나({@code academy_id} 필터)로 가둬 시드 감사 행과 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogConsoleFieldsTest {

    private static final String ADMIN_IP = "203.0.113.7";

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

    /**
     * 강제 확정 행은 {@code detail_action="run.force_confirm"} 이고, 저장된 IP 를 그대로 싣는다. 같은 학원의 일반 조회 행은 {@code detail}
     * 에 {@code action} 키가 없어 {@code detail_action} 이 {@code null} 이고, IP 를 안 남긴 행은 {@code ip} 도 {@code null} 이다.
     */
    @Test
    void 강제_확정_행은_detail_action_과_ip_를_싣고_일반_행은_null_이다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R48AUD001", "R48감사학원", "서울", null, null));
        Account actor = 계정(academy, "r48audadmin", "관리자현재이름");
        auditLogRepository.save(AuditLog.forRunForceConfirm(academy.getId(), actor.getId(), "r48audadmin", 501L,
                "확정 반복 실패", true, 9L, ADMIN_IP, OffsetDateTime.now().minusMinutes(1)));
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), actor.getId(), "r48audadmin", "student",
                77L, Map.of("student_ids", List.of("77")), null, OffsetDateTime.now().minusMinutes(2)));

        목록(academy)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[?(@.target_type == 'run')].detail_action")
                        .value("run.force_confirm"))
                .andExpect(jsonPath("$.data.items[?(@.target_type == 'run')].ip").value(ADMIN_IP))
                .andExpect(jsonPath("$.data.items[?(@.target_type == 'student')].detail_action")
                        .value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.data.items[?(@.target_type == 'student')].ip")
                        .value(org.hamcrest.Matchers.contains((Object) null)));
    }

    /**
     * {@code actor_name} 은 행위자 계정의 <b>현재</b> 이름이다 — {@code actor}(로그인 아이디)는 기록 시점 스냅샷이지만 이름은 계정을 다시
     * 읽는다. 계정 id 가 없으면 로그인 아이디로 찾고, 계정을 못 찾으면 {@code null} 이다.
     */
    @Test
    void actor_name_은_행위자_계정의_현재_이름이고_계정이_없으면_null_이다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R48AUD002", "R48감사학원2", "서울", null, null));
        Account byId = 계정(academy, "r48audbyid", "아이디로찾음");
        Account byLogin = 계정(academy, "r48audbylogin", "로그인아이디로찾음");
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), byId.getId(), "r48audbyid", "student",
                1L, Map.of("student_ids", List.of("1")), null, OffsetDateTime.now().minusMinutes(1)));
        // 계정 id 를 못 남긴 행 — 로그인 아이디 스냅샷으로 계정을 찾는다
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), null, byLogin.getLoginId(), "student",
                2L, Map.of("student_ids", List.of("2")), null, OffsetDateTime.now().minusMinutes(2)));
        // 계정이 사라진 행 — id 도 로그인 아이디도 대응 계정이 없다
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), 999_999_999L, "r48audghost", "student",
                3L, Map.of("student_ids", List.of("3")), null, OffsetDateTime.now().minusMinutes(3)));

        // 계정 id 도 로그인 아이디도 못 남긴 행 — 이름을 찾을 근거가 없어 null 이어야 하고 목록이 실패하면 안 된다
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), null, null, "student",
                4L, Map.of("student_ids", List.of("4")), null, OffsetDateTime.now().minusMinutes(4)));

        목록(academy)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.target_id == '4')].actor_name")
                        .value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.data.items[?(@.target_id == '1')].actor_name").value("아이디로찾음"))
                .andExpect(jsonPath("$.data.items[?(@.target_id == '2')].actor_name").value("로그인아이디로찾음"))
                .andExpect(jsonPath("$.data.items[?(@.target_id == '3')].actor_name")
                        .value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.data.items[?(@.target_id == '3')].actor").value("r48audghost"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private ResultActions 목록(Academy academy) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN,
                        AccountStatus.ACTIVE))
                .param("academy_id", String.valueOf(academy.getId())));
    }

    private Account 계정(Academy academy, String loginId, String name) {
        return accountRepository.save(Account.forSignup(academy.getId(), loginId, "x", name, "010-4805-0000", null,
                Role.PARENT));
    }
}

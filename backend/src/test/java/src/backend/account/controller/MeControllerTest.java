package src.backend.account.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §2.10 {@code GET /me} — 전 역할 공통 본인 프로필.
 *
 * <p>{@code pending}·{@code rejected} 도 호출 가능하다는 API_SPEC 본문 그대로, Task 4 가
 * {@code MeController.me()} 에 {@code @AllowedWhenPending} 을 부착해 이 편차를 해소했다
 * (2026-08-25) — 이전에는 p2-task-2-report.md 의 부착 지침에 따라 게이트 애너테이션 없이
 * 403 {@code AUTH_PENDING} 을 반환했으나, 대기 화면이 상태를 알아야 하는 API_SPEC 본문 요구를
 * 우선해 뒤집었다(브리프 §7 goal sentence, 의도된 RED #3 GREEN 전환).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3MEAQQQQ', '학원P2T3MEA본인', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3MEAQQQQ'), "
                    + "'p2t3pendingme', 'x', '대기자', '010-0000-0005', 'parent', 'pending')"
    })
    void pending_계정도_me_를_부르면_본인_프로필을_받는다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3MEAQQQQ"))
                .findFirst().orElseThrow().getId();
        Long accountId = accountRepository.findByLoginId("p2t3pendingme").orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.PENDING);

        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_id").value("p2t3pendingme"))
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3MEBQQQQ', '학원P2T3MEB활성', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3MEBQQQQ'), "
                    + "'p2t3activeme', 'x', '활성계정', '010-0000-0006', 'parent', 'active')"
    })
    void active_계정이_me_를_부르면_본인_프로필을_받는다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3MEBQQQQ"))
                .findFirst().orElseThrow().getId();
        Long accountId = accountRepository.findByLoginId("p2t3activeme").orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_id").value("p2t3activeme"))
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.linked_student_count").value(0));
    }
}

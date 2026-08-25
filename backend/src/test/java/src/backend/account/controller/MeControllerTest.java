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
 * <p>{@code pending}·{@code rejected} 도 호출 가능하다는 API_SPEC 본문과 달리, Task 3·4 부착
 * 지침(p2-task-2-report.md)이 이 엔드포인트에 계정 상태 게이트 애너테이션을 붙이지 않기로
 * 확정했다 — 그래서 {@code pending} 토큰은 여기서 403 {@code AUTH_PENDING} 을 받는다. 이 테스트가
 * 그 편차를 그대로 단언한다(브리프 §6 goal sentence).
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
    void pending_계정이_me_를_부르면_403_AUTH_PENDING_이다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3MEAQQQQ"))
                .findFirst().orElseThrow().getId();
        Long accountId = accountRepository.findByLoginId("p2t3pendingme").orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.PENDING);

        mockMvc.perform(get("/me").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_PENDING"));
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

        mockMvc.perform(get("/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_id").value("p2t3activeme"))
                .andExpect(jsonPath("$.data.role").value("parent"))
                .andExpect(jsonPath("$.data.linked_student_count").value(0));
    }
}

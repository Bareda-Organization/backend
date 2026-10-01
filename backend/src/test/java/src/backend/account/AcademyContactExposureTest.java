package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;

/**
 * 로그인(§2.5)·{@code GET /me}(§2.10) 응답의 {@code academy.contact} — 매니저 앱이 통신 두절 때
 * 학원에 전화하도록 학원 대표 연락처를 받는 유일한 경로다(R46-MGR, Ruling 460).
 *
 * <p>학원이 연락처를 등록하지 않았으면 키는 있고 값이 {@code null} 이다(§2.5 의 "키는 있고 값이 null" 규칙과
 * 같다 — 키가 빠지면 클라이언트가 두 경우를 나눠 읽어야 한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AcademyContactExposureTest {

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void 학원_연락처가_있으면_로그인과_me_의_academy_에_실린다() throws Exception {
        String loginId = createAccount("R46MGRCT01", "02-555-0101");

        MvcResult login = login(loginId);
        Map<String, Object> loginAcademy = JsonPath.read(login.getResponse().getContentAsString(), "$.data.academy");
        assertThat(loginAcademy).containsEntry("contact", "02-555-0101");

        MvcResult me = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + JsonPath.read(login.getResponse().getContentAsString(),
                                "$.data.access_token")))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> meAcademy = JsonPath.read(me.getResponse().getContentAsString(), "$.data.academy");
        assertThat(meAcademy).containsEntry("contact", "02-555-0101");
    }

    @Test
    void 학원_연락처가_비어_있으면_키는_있고_값이_null_이다() throws Exception {
        String loginId = createAccount("R46MGRCT02", null);

        MvcResult login = login(loginId);
        Map<String, Object> loginAcademy = JsonPath.read(login.getResponse().getContentAsString(), "$.data.academy");
        assertThat(loginAcademy).containsKey("contact").containsEntry("contact", null);

        MvcResult me = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + JsonPath.read(login.getResponse().getContentAsString(),
                                "$.data.access_token")))
                .andReturn();
        Map<String, Object> meAcademy = JsonPath.read(me.getResponse().getContentAsString(), "$.data.academy");
        assertThat(meAcademy).containsKey("contact").containsEntry("contact", null);
    }

    private String createAccount(String academyCode, String contact) {
        Academy academy = academyRepository.save(
                Academy.register(academyCode, "학원" + academyCode, "서울", null, contact));
        String loginId = "r46mgr" + academyCode.toLowerCase();
        accountRepository.save(Account.forSignup(academy.getId(), loginId, passwordEncoder.encode(RAW_PASSWORD),
                "연락처시험", "010-7000-4600", null, Role.PARENT));
        return loginId;
    }

    private MvcResult login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }
}

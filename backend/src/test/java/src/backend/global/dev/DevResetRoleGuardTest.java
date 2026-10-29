package src.backend.global.dev;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.config.ClockConfig;
import src.backend.global.security.AccountStanding;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.security.SecurityConfig;

/**
 * 공개 주소에 올라가는 {@code staging} 서버의 초기화는 관계자(STAFF)와 메인 관리자만 부른다 — 공유 DB 를 통째로 지우는 호출이라 학부모·학생·기사 계정으로는 막는다.
 * {@code staging} 이 아닌 {@code local} 에서는 웹·모바일 실서버 계약 시험이 학부모·기사 토큰으로 초기화하므로 인증된 계정이면 부른다.
 */
@WebMvcTest(controllers = DevResetController.class, properties = {"app.dev-tools.reset.enabled=true",
        "JWT_SECRET=staging-role-guard-secret-staging-role-guard-secret-01", "SEED_PASSWORD_HASH=x",
        "CORS_ALLOWED_ORIGINS=http://localhost:3000", "WS_ALLOWED_ORIGIN_PATTERNS=*"})
@ActiveProfiles({"local", "staging"})
@Import({SecurityConfig.class, JwtTokenProvider.class, ClockConfig.class})
@DisplayName("staging 의 개발용 초기화는 관계자와 메인 관리자만 부른다")
class DevResetRoleGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private AccountStanding accountStanding;

    @MockitoBean
    private DevResetService devResetService;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"PARENT", "STUDENT", "DRIVER", "ESCORT"})
    @DisplayName("관계자가 아닌 역할은 403 FORBIDDEN 이고 초기화는 실행되지 않는다")
    void 관계자가_아니면_403(Role role) throws Exception {
        mockMvc.perform(post("/api/v1/dev/reset").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        org.mockito.Mockito.verify(devResetService, org.mockito.Mockito.never()).reset();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"STAFF", "SYSTEM_ADMIN"})
    @DisplayName("관계자와 메인 관리자는 200 이다")
    void 관계자는_200(Role role) throws Exception {
        when(devResetService.reset()).thenReturn(3);

        mockMvc.perform(post("/api/v1/dev/reset").header("Authorization", bearer(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cleared_position_keys").value(3));
    }

    private String bearer(Role role) {
        Long academyId = role == Role.SYSTEM_ADMIN ? null : 1L;
        return "Bearer " + tokenProvider.createAccessToken(7L, academyId, role, AccountStatus.ACTIVE);
    }
}

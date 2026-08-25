package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * §2.11 {@code POST/DELETE /me/devices} — NTF-12.
 *
 * <p>{@code @CanRegisterDevice} 는 {@link src.backend.global.security.authz.Permissions#DEVICE_REGISTER}
 * 를 전 역할이 보유하도록 설계됐다 — {@code roleHierarchy()} 배선이 어긋나면 특정 역할만
 * 조용히 403 을 받는데, 이 테스트가 role 6종 각각으로 등록을 성공시켜 그 배선을 실측 검증한다
 * (선결 조건① NTF-12 종단 증거).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3DEVQQQQ', '학원P2T3DEV단말', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3DEVQQQQ'), "
                    + "'p2t3devqqqq', 'x', '단말테스트', '010-0000-0007', 'parent', 'active')"
    })
    void 단말_등록은_같은_기기를_두_번_등록해도_행이_늘지_않는다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3DEVQQQQ"))
                .findFirst().orElseThrow().getId();
        Long accountId = accountRepository.findByLoginId("p2t3devqqqq").orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.ACTIVE);

        String firstPayload = """
                {"token": "fcm-token-1", "platform": "android", "device_id": "device-abc"}
                """;
        mockMvc.perform(post("/me/devices").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.device_id").value("device-abc"));

        String secondPayload = """
                {"token": "fcm-token-2", "platform": "android", "device_id": "device-abc"}
                """;
        mockMvc.perform(post("/me/devices").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(secondPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.device_id").value("device-abc"));

        assertThat(deviceTokenRepository.findByAccountIdAndDeviceId(accountId, "device-abc"))
                .isPresent()
                .get().extracting(dt -> dt.getToken()).isEqualTo("fcm-token-2");
        assertThat(deviceTokenRepository.findAll().stream()
                .filter(dt -> dt.getAccountId().equals(accountId) && dt.getDeviceId().equals("device-abc"))
                .count()).isEqualTo(1);
    }

    @Test
    @Sql(statements = {
            "INSERT INTO academy (code, name, region, status) "
                    + "VALUES ('P2T3DELQQQQ', '학원P2T3DEL해지', '서울', 'active')",
            "INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                    + "VALUES ((SELECT id FROM academy WHERE code = 'P2T3DELQQQQ'), "
                    + "'p2t3delqqqq', 'x', '해지테스트', '010-0000-0008', 'parent', 'active')"
    })
    void 단말_해지는_204_를_반환하고_대상이_없어도_멱등하다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3DELQQQQ"))
                .findFirst().orElseThrow().getId();
        Long accountId = accountRepository.findByLoginId("p2t3delqqqq").orElseThrow().getId();
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT,
                AccountStatus.ACTIVE);

        String payload = """
                {"token": "fcm-token-revoke", "platform": "ios", "device_id": "device-xyz"}
                """;
        mockMvc.perform(post("/me/devices").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/me/devices/{token}", "fcm-token-revoke").header("Authorization", token))
                .andExpect(status().isNoContent());

        // 이미 해지된 것을 다시 해지해도(또는 존재하지 않는 토큰이어도) 멱등하게 204.
        mockMvc.perform(delete("/me/devices/{token}", "fcm-token-revoke").header("Authorization", token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/me/devices/{token}", "no-such-token").header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    @Test
    @Sql(statements = "INSERT INTO academy (code, name, region, status) "
            + "VALUES ('P2T3ALLQQQQ', '학원P2T3ALL전역할', '서울', 'active')")
    void 단말_등록은_전_역할_6종이_전부_성공한다() throws Exception {
        Long academyId = academyRepository.findAll().stream()
                .filter(a -> a.getCode().equals("P2T3ALLQQQQ"))
                .findFirst().orElseThrow().getId();

        for (Role role : Role.values()) {
            String loginId = "p2t3role" + role.name().toLowerCase();
            var account = accountRepository.save(src.backend.account.entity.Account.forSignup(
                    role == Role.SYSTEM_ADMIN ? null : academyId, loginId, "x", "역할" + role.name(),
                    "010-1111-0000", null, role));
            String token = "Bearer " + tokenProvider.createAccessToken(account.getId(), academyId, role,
                    AccountStatus.ACTIVE);
            String payload = """
                    {"token": "fcm-%s", "platform": "web", "device_id": "device-%s"}
                    """.formatted(role.name(), role.name());

            mockMvc.perform(post("/me/devices").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isCreated());
        }
    }
}

package src.backend.manager.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 매니저 수정(PATCH)은 보낸 이름·연락처가 <b>비어 있으면 거부</b>한다(BR-245) — 안 보내는 것만 "그대로 둠" 이다.
 * 등록은 {@code @NotBlank} 로 막는데 수정이 빈 문자열을 그대로 저장해, 이름 없는 매니저가 명단에 남았다.
 * 본문 검증은 인가·조회보다 먼저 돌아 경로의 매니저가 실재하지 않아도 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagerUpdateBlankFieldTest {

    private static final String MANAGER = "/api/v1/staff/managers/999999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @ParameterizedTest(name = "name={0}")
    @ValueSource(strings = {"", "   "})
    void 빈_이름은_422_VALIDATION_FAILED_다(String name) throws Exception {
        mockMvc.perform(patch(MANAGER).header("Authorization", 관계자())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest(name = "phone={0}")
    @ValueSource(strings = {"", "   "})
    void 빈_연락처는_422_VALIDATION_FAILED_다(String phone) throws Exception {
        mockMvc.perform(patch(MANAGER).header("Authorization", 관계자())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"%s\"}".formatted(phone)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 이름과_연락처를_보내지_않은_수정은_검증을_지난다() throws Exception {
        mockMvc.perform(patch(MANAGER).header("Authorization", 관계자())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"driver\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MANAGER_NOT_FOUND"));
    }

    private String 관계자() {
        return "Bearer " + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);
    }
}

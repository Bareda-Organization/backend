package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 알림 설정 변경 본문의 경계 검증 — 세 항목(arrive · boarding · no_show)만 boolean 으로 받고, 대상 밖 키 · boolean 이 아닌 값은 422 이며,
 * 보낸 항목만 바뀐다(빈 본문은 그대로 200).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationSettingPatchBodyTest {

    private static final long PARENT_ACCOUNT = 6L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 대상_밖_키는_422_다() throws Exception {
        변경("{\"delay\": true}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 허용_키와_대상_밖_키가_섞여도_422_다() throws Exception {
        변경("{\"arrive\": false, \"delay\": true}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void boolean_이_아닌_값은_422_다() throws Exception {
        변경("{\"arrive\": \"yes\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 문자열_boolean_은_422_다() throws Exception {
        변경("{\"arrive\": \"true\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        변경("{\"boarding\": \"false\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 숫자_boolean_은_422_다() throws Exception {
        변경("{\"arrive\": 1}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        변경("{\"no_show\": 0}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 보낸_항목만_바뀌고_나머지는_그대로다() throws Exception {
        String before = mockMvc.perform(get("/api/v1/me/notification-settings").header("Authorization", 부모_토큰()))
                .andReturn().getResponse().getContentAsString();

        변경("{\"no_show\": false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show").value(false))
                .andExpect(jsonPath("$.data.arrive").value((Boolean) JsonPath.read(before, "$.data.arrive")))
                .andExpect(jsonPath("$.data.boarding").value((Boolean) JsonPath.read(before, "$.data.boarding")));
    }

    @Test
    void 빈_본문은_바꿀_것이_없어_200_이다() throws Exception {
        변경("{}").andExpect(status().isOk());
    }

    @Test
    void API_문서의_요청_스키마에는_세_항목만_나온다() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        Map<String, Object> properties = JsonPath.read(docs,
                "$.components.schemas.NotificationSettingUpdateRequest.properties");
        assertThat(properties.keySet()).as("요청 본문 키 — 대상 밖 키를 모으는 내부 칸이 문서에 나오면 안 된다")
                .containsExactlyInAnyOrder("arrive", "boarding", "no_show");
    }

    private ResultActions 변경(String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/notification-settings").header("Authorization", 부모_토큰())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String 부모_토큰() {
        return 토큰(tokenProvider, PARENT_ACCOUNT, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.PARENT);
    }
}

package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배포 성공 판정에 쓰는 헬스 엔드포인트 계약을 고정한다.
 * compose 의 healthcheck 와 deploy.sh 의 스모크 테스트가 이 응답 형태에 의존한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("헬스 엔드포인트는 인증 없이 UP 을 반환한다")
    void healthIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("헬스 상세는 노출하지 않는다 — DB URL·Redis 호스트가 인증 없이 새 나가면 안 된다")
    void healthHidesDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("health 외 액추에이터 엔드포인트는 외부에서 읽을 수 없다")
    void otherEndpointsAreNotReadable() throws Exception {
        int status = mockMvc.perform(get("/actuator/env"))
                .andReturn().getResponse().getStatus();
        // 노출 목록에 없어 매핑되지 않거나(404), 시큐리티가 먼저 막는다(401).
        // 어느 쪽이든 "읽히지 않는다"가 지켜야 할 계약이다.
        assertThat(status).isIn(401, 404);
    }
}

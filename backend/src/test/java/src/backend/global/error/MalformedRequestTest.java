package src.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 형식이 틀린 요청은 {@code 422 VALIDATION_FAILED} 다(BR-032, API_SPEC §1.11 "형식 위반").
 *
 * <p>catch-all 로 떨어져 {@code 500} 이 되면 클라이언트 실수가 서버 고장으로 보이고, 오프라인 큐는
 * 5xx 를 재시도 대상으로 봐 같은 요청을 끝없이 다시 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("형식이 틀린 요청은 500 이 아니라 422 다")
class MalformedRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 깨진_JSON_본문은_422() throws Exception {
        expect422(mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"login_id\":")));
    }

    @Test
    void Content_Type_없는_본문은_422() throws Exception {
        expect422(mockMvc.perform(post("/api/v1/auth/login").content("{\"login_id\":\"a\",\"password\":\"b\"}")));
    }

    @Test
    void 숫자_경로_변수에_문자는_422() throws Exception {
        expect422(mockMvc.perform(get("/api/v1/runs/abc/roster").header("Authorization", staff())));
    }

    @Test
    void 파라미터_제약_위반은_422() throws Exception {
        expect422(mockMvc.perform(get("/api/v1/staff/stops/search").param("address", " ")
                .header("Authorization", staff())));
    }

    @Test
    void 승인_목록의_없는_status_값은_422() throws Exception {
        expect422(mockMvc.perform(get("/api/v1/staff/approvals").param("status", "done")
                .header("Authorization", staff())));
    }

    /** W01-16 — 범위를 벗어난 좌표가 DB CHECK 까지 가면 제약 위반 500 이다. 2초마다 반복되는 요청이다. */
    @Test
    void 위도_범위_밖_좌표는_422() throws Exception {
        String driver = "Bearer " + tokenProvider.createAccessToken(1L, 1L, Role.DRIVER, AccountStatus.ACTIVE);
        expect422(mockMvc.perform(post("/api/v1/runs/1/position").header("Authorization", driver)
                .contentType("application/json")
                .content("{\"lat\":91,\"lng\":127.0,\"recorded_at\":\"2026-09-25T08:00:00+09:00\"}")));
    }

    private static void expect422(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String staff() {
        return "Bearer " + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);
    }
}

package src.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 요청 본문의 길이·범위 초과는 DB 에 닿기 전에 {@code 422 VALIDATION_FAILED} 다(API_SPEC §1.11 · BR-092).
 *
 * <p>검증이 없으면 컬럼 길이 초과가 {@code DataIntegrityViolationException}, 72바이트를 넘는 비밀번호가 BCrypt 의
 * {@code IllegalArgumentException}, 범위 밖 좌표가 {@code GeoPoint} 생성자 예외가 되어 전부 {@code 500} 으로 나간다.
 * 본문 검증은 인가보다 먼저 돌아 경로의 식별자는 실재하지 않아도 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestLengthValidationTest {

    private static final String KOREAN_75_BYTES = "가".repeat(25);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    static Stream<Arguments> 넘치는_요청() {
        String staff = "STAFF";
        String admin = "SYSTEM_ADMIN";
        String escort = "ESCORT";
        String parent = "PARENT";
        return Stream.of(
                Arguments.of("가입 login_id 51자", HttpMethod.POST, "/api/v1/auth/signup", null,
                        signup("a".repeat(51), "password1", "이름", "010-0000-0000")),
                Arguments.of("가입 name 51자", HttpMethod.POST, "/api/v1/auth/signup", null,
                        signup("br092name", "password1", "가".repeat(51), "010-0000-0000")),
                Arguments.of("가입 phone 31자", HttpMethod.POST, "/api/v1/auth/signup", null,
                        signup("br092phone", "password1", "이름", "0".repeat(31))),
                Arguments.of("가입 비밀번호 72바이트 초과", HttpMethod.POST, "/api/v1/auth/signup", null,
                        signup("br092pw", KOREAN_75_BYTES, "이름", "010-0000-0000")),
                Arguments.of("비밀번호 변경 72바이트 초과", HttpMethod.POST, "/api/v1/auth/password", parent,
                        "{\"current_password\":\"password\",\"new_password\":\"%s\"}".formatted(KOREAN_75_BYTES)),
                Arguments.of("가입 거절 사유 201자", HttpMethod.POST, "/api/v1/staff/signup-requests/999999/decide", staff,
                        "{\"accept\":false,\"reject_reason\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("관계자 가입 거절 사유 201자", HttpMethod.POST,
                        "/api/v1/admin/staff-signup-requests/999999/decide", admin,
                        "{\"accept\":false,\"reject_reason\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("구간 변경 거절 사유 201자", HttpMethod.POST, "/api/v1/staff/approvals/999999/decide", staff,
                        "{\"approve\":false,\"reject_reason\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("단말 device_id 101자", HttpMethod.POST, "/api/v1/me/devices", parent,
                        "{\"token\":\"t\",\"platform\":\"ios\",\"device_id\":\"%s\"}".formatted("d".repeat(101))),
                Arguments.of("단말 app_version 21자", HttpMethod.POST, "/api/v1/me/devices", parent,
                        "{\"token\":\"t\",\"platform\":\"ios\",\"device_id\":\"d\",\"app_version\":\"%s\"}"
                                .formatted("1".repeat(21))),
                Arguments.of("경유 지점 label 101자", HttpMethod.POST, "/api/v1/staff/runs/999999/waypoints", staff,
                        "{\"label\":\"%s\",\"apply\":false,\"address\":\"주소\"}".formatted("가".repeat(101))),
                Arguments.of("경유 지점 note 201자", HttpMethod.POST, "/api/v1/staff/runs/999999/waypoints", staff,
                        "{\"label\":\"l\",\"note\":\"%s\",\"apply\":false,\"address\":\"주소\"}"
                                .formatted("가".repeat(201))),
                Arguments.of("경유 지점 위도 범위 밖", HttpMethod.POST, "/api/v1/staff/runs/999999/waypoints", staff,
                        "{\"label\":\"l\",\"apply\":false,\"lat\":200,\"lng\":127.0}"),
                Arguments.of("지연 문구 501자", HttpMethod.POST, "/api/v1/runs/999999/delay", escort,
                        "{\"minutes\":5,\"reason\":\"traffic\",\"message\":\"%s\"}".formatted("가".repeat(501))),
                Arguments.of("정정 사유 201자", HttpMethod.POST, "/api/v1/runs/999999/riders/999999/revert", escort,
                        "{\"reason\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("강제 추가 note 201자", HttpMethod.POST, "/api/v1/staff/runs/999999/forced-add", staff,
                        "{\"student_id\":1,\"address\":\"주소\",\"note\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("학원 등록 memo 201자", HttpMethod.POST, "/api/v1/admin/academies", admin,
                        "{\"name\":\"n\",\"region\":\"r\",\"memo\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("학원 수정 memo 201자", HttpMethod.PATCH, "/api/v1/admin/academies/999999", admin,
                        "{\"memo\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("비상 신고 memo 201자", HttpMethod.POST, "/api/v1/runs/999999/emergency", escort,
                        "{\"type\":\"accident\",\"memo\":\"%s\",\"client_key\":\"%s\"}"
                                .formatted("가".repeat(201), java.util.UUID.randomUUID())),
                Arguments.of("예외 보고 memo 201자", HttpMethod.POST, "/api/v1/runs/999999/reports", escort,
                        "{\"type\":\"vehicle_issue\",\"memo\":\"%s\"}".formatted("가".repeat(201))),
                Arguments.of("강제 추가 신규 학생 이름 51자", HttpMethod.POST, "/api/v1/staff/runs/999999/forced-add", staff,
                        "{\"new_student\":{\"name\":\"%s\"},\"address\":\"주소\"}".formatted("가".repeat(51))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("넘치는_요청")
    void 길이_범위_초과는_422_VALIDATION_FAILED_다(String name, HttpMethod method, String path, String role,
            String body) throws Exception {
        MockHttpServletRequestBuilder request = (method == HttpMethod.PATCH ? patch(path) : post(path))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (role != null) {
            Role parsed = Role.valueOf(role);
            Long academyId = parsed == Role.SYSTEM_ADMIN ? null : 1L;
            request.header("Authorization",
                    "Bearer " + tokenProvider.createAccessToken(5L, academyId, parsed, AccountStatus.ACTIVE));
        }

        mockMvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static String signup(String loginId, String password, String name, String phone) {
        return "{\"role\":\"parent\",\"login_id\":\"%s\",\"password\":\"%s\",\"name\":\"%s\",\"phone\":\"%s\",\"academy_id\":\"1\"}"
                .formatted(loginId, password, name, phone);
    }
}

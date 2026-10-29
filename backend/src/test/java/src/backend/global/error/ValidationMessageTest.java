package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import src.backend.global.response.ErrorResponse;

/**
 * 422 {@code error.message} 는 한국어이고(API_SPEC §1.10) 같은 입력이면 어느 필드가 보고되는지가 매번 같다 — 검증 라이브러리의 기본 문구는 요청의
 * {@code Accept-Language} 를 따라 영어로 나가고, 위반 목록의 순서는 정해져 있지 않다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("422 검증 실패 문구는 한국어이고 보고 필드가 결정적이다")
class ValidationMessageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "Accept-Language: {0}")
    @ValueSource(strings = {"en", "en-US", "ja", "ko-KR"})
    @DisplayName("Accept-Language 와 무관하게 한글 문구다")
    void 요청_언어와_무관하게_한글_문구(String acceptLanguage) throws Exception {
        String message = loginWithBlankId(acceptLanguage);

        assertThat(message).startsWith("login_id: ").containsPattern("[가-힣]")
                .doesNotContain("must").doesNotContain("blank");
    }

    @Test
    @DisplayName("길이 제약의 문구도 한글이다")
    void 길이_제약_문구도_한글() throws Exception {
        String message = errorMessage(post("/api/v1/auth/signup").header("Accept-Language", "en")
                .contentType("application/json").content("{\"role\":\"parent\",\"login_id\":\"" + "a".repeat(300)
                        + "\",\"password\":\"x\",\"name\":\"n\",\"phone\":\"010\",\"academy_id\":\"1\"}"));

        assertThat(message).startsWith("login_id: ").containsPattern("[가-힣]").doesNotContain("size must");
    }

    /** 번들 문구 하나가 오타 나도 한글 패턴 검사로는 못 잡는다 — 코드가 쓰는 제약마다 정확한 문구를 못 박는다. 길이 문구는 목록 크기에도 쓰여 단위를 붙이지 않는다. */
    @Test
    @DisplayName("번들의 제약별 문구는 정해진 한글 그대로다")
    void 번들_문구는_정해진_한글_그대로다() throws Exception {
        java.util.Properties bundle = new java.util.Properties();
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("ValidationMessages.properties");
                java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }

        java.util.Map<String, String> expected = new java.util.TreeMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("AssertTrue", "조건을 만족해야 합니다"),
                java.util.Map.entry("DecimalMax", "{value} 이하여야 합니다"),
                java.util.Map.entry("DecimalMin", "{value} 이상이어야 합니다"),
                java.util.Map.entry("Email", "이메일 형식이 올바르지 않습니다"),
                java.util.Map.entry("Max", "{value} 이하여야 합니다"),
                java.util.Map.entry("Min", "{value} 이상이어야 합니다"),
                java.util.Map.entry("NotBlank", "필수 입력값입니다"),
                java.util.Map.entry("NotEmpty", "필수 입력값입니다"),
                java.util.Map.entry("NotNull", "필수 입력값입니다"),
                java.util.Map.entry("Pattern", "형식이 올바르지 않습니다"),
                java.util.Map.entry("Positive", "0보다 커야 합니다"),
                java.util.Map.entry("PositiveOrZero", "0 이상이어야 합니다"),
                java.util.Map.entry("Size", "길이는 {min} 이상 {max} 이하여야 합니다")));
        java.util.Map<String, String> actual = new java.util.TreeMap<>();
        expected.keySet().forEach(constraint -> actual.put(constraint,
                bundle.getProperty("jakarta.validation.constraints." + constraint + ".message")));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("위반이 여럿이면 필드 이름 순으로 첫 필드를 보고한다")
    void 여러_위반은_필드_이름_순() throws Exception {
        String message = errorMessage(post("/api/v1/auth/signup").contentType("application/json").content("{}"));

        assertThat(message).startsWith("academy_id: ");
    }

    @Test
    @DisplayName("같은 필드의 위반이 여럿이면 문구 순으로 정해 같은 입력이 같은 문구를 얻는다")
    void 같은_필드_위반도_순서가_정해진다() throws Exception {
        MethodArgumentNotValidException ordered = invalid(
                new FieldError("req", "name", "나 문구"), new FieldError("req", "name", "가 문구"));
        MethodArgumentNotValidException reversed = invalid(
                new FieldError("req", "name", "가 문구"), new FieldError("req", "name", "나 문구"));
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertThat(message(handler.handleValidation(ordered).getBody()))
                .isEqualTo(message(handler.handleValidation(reversed).getBody()))
                .isEqualTo("name: 가 문구");
    }

    @Test
    @DisplayName("타입 변환 실패는 자바 타입명 없이 고정 문구다")
    void 타입_변환_실패는_고정_문구() throws Exception {
        FieldError typeMismatch = new FieldError("req", "pageSize", "abc", true, new String[] {"typeMismatch"}, null,
                "Failed to convert property value of type 'java.lang.String' to required type 'java.lang.Integer'");

        String message = message(new GlobalExceptionHandler().handleValidation(invalid(typeMismatch)).getBody());

        assertThat(message).isEqualTo("page_size 값의 형식이 올바르지 않습니다");
    }

    @Test
    @DisplayName("필수 요청 헤더가 없으면 헤더 이름을 알린다")
    void 필수_헤더_누락은_헤더_이름을_알린다() throws Exception {
        Method method = ValidationMessageTest.class.getDeclaredMethod("message", ErrorResponse.class);
        org.springframework.web.bind.MissingRequestHeaderException missing =
                new org.springframework.web.bind.MissingRequestHeaderException("X-Client-Key", new MethodParameter(method, 0));

        var response = new GlobalExceptionHandler().handleStatusCarrying(missing);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().error().message()).isEqualTo("X-Client-Key 헤더가 필요합니다");
    }

    private String loginWithBlankId(String acceptLanguage) throws Exception {
        return errorMessage(post("/api/v1/auth/login").header("Accept-Language", acceptLanguage)
                .contentType("application/json").content("{\"login_id\":\"\",\"password\":\"x\"}"));
    }

    private String errorMessage(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isUnprocessableContent()).andReturn().getResponse()
                .getContentAsString();
        JsonNode node = JSON.readTree(body);
        return node.path("error").path("message").asText();
    }

    private static String message(ErrorResponse response) {
        return response.error().message();
    }

    private static MethodArgumentNotValidException invalid(FieldError... errors) throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "req");
        List.of(errors).forEach(result::addError);
        Method method = ValidationMessageTest.class.getDeclaredMethod("message", ErrorResponse.class);
        return new MethodArgumentNotValidException(new MethodParameter(method, 0), result);
    }
}

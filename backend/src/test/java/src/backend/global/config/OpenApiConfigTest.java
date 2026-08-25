package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import src.backend.global.common.SeedFixtures;

/**
 * Swagger 문서 골격(Phase 1 Task 9) 검증. {@code bootRun} 없이 실서버(RANDOM_PORT)를 띄워
 * springdoc 이 실제로 생성한 {@code /v3/api-docs} 응답을 대조한다 — {@code OpenApiConfig} 빈
 * 구성이 애너테이션 조합 오류로 무력화돼도 컴파일은 통과하므로, 런타임 응답 확인이 유일한 판별 수단이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiConfigTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String apiDocsJson() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * 단언 1 — 계정표가 {@code SeedFixtures} 의 역할 6종 로그인 아이디를 전부 문자열로 담는다.
     * 상수를 "참조"했는지는 컴파일 타임에 인라인되어 런타임에 구분 불가하므로, 값 자체의 실재를 본다.
     */
    @Test
    void description_containsAllSixRoleLoginIds() {
        String body = apiDocsJson();

        assertThat(body).contains(SeedFixtures.SYSTEM_ADMIN_LOGIN_ID);
        assertThat(body).contains(SeedFixtures.STAFF_A_LOGIN_ID);
        assertThat(body).contains(SeedFixtures.PARENT_A1_LOGIN_ID);
        assertThat(body).contains(SeedFixtures.STUDENT_A4_LOGIN_ID);
        assertThat(body).contains(SeedFixtures.DRIVER_A1_LOGIN_ID);
        assertThat(body).contains(SeedFixtures.ESCORT_A1_LOGIN_ID);
    }

    /**
     * 단언 2 — 옛 역할명이 남아 있지 않다. 이 단언이 없으면 옛 문구를 지우지 않고 새 문구만
     * 덧붙여도 단언 1이 통과하는 거짓 GREEN 이 발생한다.
     */
    @Test
    void description_doesNotContainLegacyRoleNames() {
        String body = apiDocsJson();

        assertThat(body).doesNotContain("ACADEMY_ADMIN");
        assertThat(body).doesNotContain("PLATFORM_ADMIN");
        assertThat(body).doesNotContain("ATTENDANT");
    }

    /** 단언 3 — JWT Bearer 스킴이 역할 체계 재작성과 무관하게 그대로 남아 있다. */
    @Test
    void securityScheme_bearerAuth_isHttpBearerJwt() {
        String body = apiDocsJson();

        assertThat(body).contains("\"bearerAuth\"");
        assertThat(body).contains("\"type\":\"http\"");
        assertThat(body).contains("\"scheme\":\"bearer\"");
        assertThat(body).contains("\"bearerFormat\":\"JWT\"");
    }
}

package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배포 설정이 위험한 기본값으로 되돌아가는 것을 막는 가드.
 *
 * <p>여기서 막는 사고는 전부 "조용히 성공하는" 종류다 — 앱은 정상 기동하지만
 * 공개 도메인에 약한 계정이나 개발용 설정이 열린 채로 뜬다. 테스트가 없으면
 * 리뷰에서 놓치고 배포 후에야 드러난다.
 *
 * <p>파일 텍스트만 읽으므로 Docker·DB 없이 실행된다.
 */
class DeploymentConfigGuardTest {

    private static String applicationYml;

    @BeforeAll
    static void readConfig() throws IOException {
        applicationYml = Files.readString(Path.of("src/main/resources/application.yml"));
    }

    @Test
    @DisplayName("demo 프로파일의 시드 비밀번호 해시에 기본값이 없다")
    void demoSeedHashHasNoDefault() {
        // 기본값(${SEED_PASSWORD_HASH:...})을 두면 주입을 깜빡해도 앱이 뜬다.
        // 그 결과 공개 도메인에 비밀번호 "password" 인 PLATFORM_ADMIN 이 열린다.
        assertThat(applicationYml)
                .as("demo 프로파일은 SEED_PASSWORD_HASH 를 기본값 없이 요구해야 한다")
                .contains("seedPasswordHash: ${SEED_PASSWORD_HASH}")
                .doesNotContain("seedPasswordHash: ${SEED_PASSWORD_HASH:");
    }

    @Test
    @DisplayName("prod 프로파일은 Mock 위치 소스를 켜지 않는다")
    void prodKeepsMockDisabled() {
        String prodSection = sectionOf("on-profile: prod");
        // ⚠️ 단순히 "enabled: true 가 없다"로 검사하면 안 된다 — prod 에는 gps.enabled: true 가
        //    정상적으로 존재한다. 반드시 키와 값을 붙여서 본다.
        assertThat(prodSection)
                .as("prod 는 실 GPS 전용이다. Mock 을 켜면 실제 단말 좌표를 가짜가 덮어쓴다")
                .contains("    mock:\n      enabled: false")
                .contains("    bus-mock:\n      enabled: false");
    }

    @Test
    @DisplayName("prod·demo 프로파일의 WebSocket 허용 출처에 와일드카드 기본값이 없다")
    void deployProfilesRequireExplicitWsOrigins() {
        // 공통 섹션의 기본값은 `${WS_ALLOWED_ORIGIN_PATTERNS:*}` 다 — 배포 프로파일이 이 키를
        // 다시 적지 않으면 그 `*` 를 조용히 상속해 **모든 출처에서 WebSocket 이 허용된다.**
        // REST 와 달리 STOMP 핸드셰이크는 CORS 필터를 타지 않아 이 값이 유일한 방어선이고,
        // 뚫려도 앱은 정상 기동하므로 배포 후에도 드러나지 않는다.
        for (String profile : new String[] {"prod", "demo"}) {
            assertThat(sectionOf("on-profile: " + profile))
                    .as("%s 프로파일은 WS_ALLOWED_ORIGIN_PATTERNS 를 기본값 없이 요구해야 한다"
                            + " (빠뜨리면 공통 섹션의 와일드카드 `*` 를 상속한다)", profile)
                    .contains("    allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS}")
                    .doesNotContain("allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS:");
        }
    }

    @Test
    @DisplayName("demo 프로파일은 Mock 위치 소스를 켠다")
    void demoEnablesMock() {
        String demoSection = sectionOf("on-profile: demo");
        assertThat(demoSection)
                .as("실 기사 단말이 없는 데모에서 Mock 을 끄면 버스가 움직이지 않는다")
                .contains("    mock:\n      enabled: true")
                .contains("    bus-mock:\n      enabled: true");
    }

    @Test
    @DisplayName("jwt 서명키에 공통 기본값이 없다")
    void jwtSecretHasNoDefaultInCommonSection() {
        // 첫 프로파일 구분자(---) 이전 = 공통 섹션. 공통 섹션에 기본값을 두면 prod·demo 가
        // JWT_SECRET 을 빠뜨려도 저장소에 공개된 키로 조용히 기동하고, 그 키로 아무 역할의
        // 토큰이나 위조할 수 있다(ws.allowed-origin-patterns 와 같은 방침).
        String common = applicationYml.substring(0, applicationYml.indexOf("\n---"));

        assertThat(common)
                .as("공통 섹션에 기본값을 두면 prod 에서 JWT_SECRET 미주입 시 저장소에 공개된 키로 조용히 기동한다")
                .contains("${JWT_SECRET}")
                .doesNotContain("${JWT_SECRET:");
    }

    @Test
    @DisplayName("local 프로파일은 jwt 서명키에 개발용 기본값을 둔다")
    void localProfileHasJwtSecretDefault() {
        // 공통 섹션에서 기본값을 뺀 대신, local 프로파일이 개발 편의를 위해 기본값을 되살린다.
        // 이 값이 실제로 다른 프로파일로 새지 않는지는 위 jwtSecretHasNoDefaultInCommonSection() 가 막는다.
        String localSection = sectionOf("on-profile: local");
        assertThat(localSection)
                .as("local 프로파일은 JWT_SECRET 미주입 시에도 개발용 기본값으로 기동해야 한다")
                .contains("${JWT_SECRET:local-dev-secret-change-me-please-32bytes-minimum-length}");
    }

    /** `---` 로 구분된 프로파일 문서 중 표식(marker)을 포함한 것을 돌려준다. */
    private String sectionOf(String marker) {
        for (String section : applicationYml.split("(?m)^---$")) {
            if (section.contains(marker)) {
                return section;
            }
        }
        throw new AssertionError("프로파일 섹션을 찾지 못했다: " + marker);
    }
}

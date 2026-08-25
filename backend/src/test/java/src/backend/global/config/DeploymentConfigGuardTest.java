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
    @DisplayName("옛 도메인 설정 블록(app.location·app.sos·app.connection·app.drivesession)이 부재한다")
    void legacyDomainConfigBlocksAreAbsent() {
        // 새 사양(바래다 재구축)에 대응물이 없는 옛 도메인 이름이라 폐기됐다(IMPLEMENTATION_PLAN §1.2).
        // "locations:"(Flyway) 처럼 부분 문자열로 오탐하지 않도록 들여쓰기까지 포함한 키를 본다.
        assertThat(applicationYml)
                .as("옛 도메인 설정 블록은 공통·prod·demo 어느 섹션에도 없어야 한다")
                .doesNotContain("  location:\n")
                .doesNotContain("  sos:\n")
                .doesNotContain("  connection:\n")
                .doesNotContain("  drivesession:\n");
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
    @DisplayName("demo 프로파일 설명 주석이 삭제된 Mock 위치 소스 설정을 더 이상 언급하지 않는다")
    void demoCommentDoesNotReferenceRemovedMockConfig() {
        // 설정을 지우면서 그 근거를 설명하던 주석을 남겨두면, 없는 설정을 설명하는
        // 거짓 주석이 된다(IMPLEMENTATION_PLAN §1.2 · Task 브리프 §2 주의사항).
        String demoSection = sectionOf("on-profile: demo");
        assertThat(demoSection)
                .as("demo 섹션은 더 이상 Mock 위치 소스 활성 근거를 설명하지 않아야 한다")
                .doesNotContain("Mock 위치 소스");
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

    @Test
    @DisplayName("겹④ — 공통·prod·demo 섹션은 clean-disabled 를 true 로 명시하고 local 만 false 다")
    void flywayCleanDisabledIsExplicitPerProfile() {
        // Flyway 10+ 기본값도 true 이나, "겹①~③ 이 전부 뚫려도 라이브러리가 거부한다"는 마지막
        // 방어선을 기본값에 암묵적으로 맡기지 않고 파일에 명시로 고정한다(docs/IMPLEMENTATION_PLAN.md
        // §3.2 겹4). true 존재만 보면 같은 섹션에 반대값이 중복 키로 잘못 복사돼도(YAML 은 뒤 값이
        // 이겨 최후 방어선이 조용히 무력화돼도) 못 잡으므로 반대값의 부재까지 함께 건다.
        String common = applicationYml.substring(0, applicationYml.indexOf("\n---"));
        assertThat(common)
                .as("공통 섹션은 clean-disabled: true 를 명시하고 false 중복 키가 없어야 한다")
                .contains("clean-disabled: true")
                .doesNotContain("clean-disabled: false");

        for (String profile : new String[] {"prod", "demo"}) {
            assertThat(sectionOf("on-profile: " + profile))
                    .as("%s 프로파일 섹션은 clean-disabled: true 를 명시하고 false 중복 키가 없어야 한다", profile)
                    .contains("clean-disabled: true")
                    .doesNotContain("clean-disabled: false");
        }

        assertThat(sectionOf("on-profile: local"))
                .as("local 프로파일만 clean-disabled: false 로 열고 true 중복 키가 없어야 한다")
                .contains("clean-disabled: false")
                .doesNotContain("clean-disabled: true");
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

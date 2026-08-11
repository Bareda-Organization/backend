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
    @DisplayName("demo 프로파일은 Mock 위치 소스를 켠다")
    void demoEnablesMock() {
        String demoSection = sectionOf("on-profile: demo");
        assertThat(demoSection)
                .as("실 기사 단말이 없는 데모에서 Mock 을 끄면 버스가 움직이지 않는다")
                .contains("    mock:\n      enabled: true")
                .contains("    bus-mock:\n      enabled: true");
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

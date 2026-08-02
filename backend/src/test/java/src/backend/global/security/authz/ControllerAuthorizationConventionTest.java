package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 컨트롤러가 인가를 권한 애너테이션으로만 표현하는지 소스 수준에서 고정하는 회귀 방지 테스트.
 *
 * <p>규칙을 문서와 주석에만 적어 두면 다음 사람이 습관대로
 * {@code @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")} 한 줄을 새 엔드포인트에 붙여도
 * 아무것도 막지 않는다. 그 한 줄이 늘어날 때마다 "새 역할 추가 = 부여표 1파일" 이라는 이 구조의 이점이
 * 조용히 깎이므로, 되돌아가는 것 자체를 실패로 만든다.
 *
 * <p>런타임 인가 동작이 아니라 <b>소스 텍스트</b>를 검사한다. 애너테이션이 어느 역할로 확장되는지는
 * {@link RolePermissionsTest} 가, 실제 200/403 은 컨트롤러별 슬라이스 테스트가 담당한다.
 */
class ControllerAuthorizationConventionTest {

    private static final Path CONTROLLER_ROOT = Path.of("src/main/java/src/backend");

    /**
     * 컨트롤러 파일이 실제로 스캔됐는지 먼저 확인한다.
     * 경로가 어긋나 0개를 훑으면 아래 두 테스트가 <b>검사한 게 없어서</b> 초록이 되는데,
     * 그건 규칙이 지켜진 것과 구별되지 않는다.
     */
    @Test
    void 컨트롤러_소스를_실제로_스캔한다() {
        assertThat(CONTROLLER_ROOT).as("테스트 작업 디렉토리가 backend/ 가 아니면 경로를 고쳐야 한다").isDirectory();
        assertThat(controllerSources()).as("스캔된 컨트롤러 파일").hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    void 컨트롤러에_역할_문자열이_없다() {
        assertThat(violations("hasRole(", "hasAnyRole("))
                .as("컨트롤러는 역할이 아니라 권한으로 인가한다 — global/security/authz 의 애너테이션을 쓴다")
                .isEmpty();
    }

    @Test
    void 컨트롤러에_PreAuthorize_가_직접_붙지_않는다() {
        assertThat(violations("@PreAuthorize"))
                .as("표현식을 컨트롤러에 직접 쓰면 인가 어휘가 다시 흩어진다 — 권한 애너테이션을 새로 만들어 쓴다")
                .isEmpty();
    }

    /** 위반 지점을 {@code 파일:줄: 내용} 형태로 모은다 — 실패 메시지만 보고 고칠 곳을 알 수 있게. */
    private List<String> violations(String... forbidden) {
        List<String> found = new ArrayList<>();
        for (Path file : controllerSources()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (String token : forbidden) {
                    if (line.contains(token)) {
                        found.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
        return found;
    }

    private List<Path> controllerSources() {
        try (Stream<Path> paths = Files.walk(CONTROLLER_ROOT)) {
            return paths.filter(p -> p.getParent() != null && p.getParent().endsWith("controller"))
                    .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

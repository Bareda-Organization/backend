package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 횡단 계층 {@code global} 이 도메인 모듈을 import 하는 줄을 기준선과 같게 묶어 둔다(Ruling 938 I) — 모듈끼리의 순환은
 * {@link ModuleCycleBaselineTest} 가 얼리지만 그 검사는 {@code global} 을 보지 않아, {@code global} 에서 모듈로 새 의존이
 * 생겨도 초록이었다. 기준선 밖의 새 import 는 실패하고, 사라진 import 가 기준선에 남아 있어도 실패한다(목록은 줄어드는 쪽으로만 움직인다).
 */
class GlobalModuleImportBaselineTest {

    /** {@code global} 안에서 다른 {@code global} 하위로 가는 import 는 모듈 의존이 아니다. */
    private static boolean importsDomainModule(String imported) {
        return !imported.startsWith("src.backend.global.");
    }

    /** 항목마다 이유를 둔다 — 비어 있으면 {@code global} 은 도메인 모듈을 모른다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("global 이 도메인 모듈을 import 하는 줄은 기준선과 같다")
    void global_의_모듈_import_는_기준선과_같다() throws IOException {
        TreeSet<String> actual = JavaSourceScan.violations(JavaSourceScan.MAIN,
                path -> path.startsWith("global/"), GlobalModuleImportBaselineTest::importsDomainModule);

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "global → 도메인 모듈 import");
    }

    @Test
    @DisplayName("모의 global 클래스가 도메인 모듈을 import 하면 잡히고, global 끼리의 import 는 안 잡힌다")
    void 모의_소스의_모듈_import_를_잡는다() throws IOException {
        TreeSet<String> found = JavaSourceScan.violations(JavaSourceScan.FIXTURES.resolve("global-import"),
                path -> path.startsWith("global/"), GlobalModuleImportBaselineTest::importsDomainModule);

        assertThat(found).containsExactly("global/Leaky.java -> src.backend.student.repository.StudentRepository");
    }
}

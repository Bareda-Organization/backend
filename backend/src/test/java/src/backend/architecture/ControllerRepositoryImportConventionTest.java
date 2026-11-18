package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨트롤러는 저장소를 직접 import 하지 않는다 — 요청 처리는 서비스를 거친다(CODE_CONVENTIONS §3). 기준선 밖의 새 import 와 이미 사라진
 * 기준선 항목은 둘 다 실패다.
 */
class ControllerRepositoryImportConventionTest {

    /** 항목마다 이유를 둔다 — 비어 있으면 {@code controller} 는 {@code repository} 를 모른다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("controller 패키지가 repository 패키지를 import 하는 줄은 기준선과 같다")
    void controller_가_repository_를_import_하는_줄은_기준선과_같다() throws IOException {
        TreeSet<String> actual = JavaSourceScan.violations(JavaSourceScan.MAIN,
                path -> JavaSourceScan.inPackage(path, "controller"), imported -> JavaSourceScan.importsFromPackage(imported, "repository"));

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "controller → repository import");
    }

    @Test
    @DisplayName("모의 controller 클래스의 repository import 는 잡히고 service 클래스의 repository import 는 안 잡힌다")
    void 모의_소스의_repository_import_를_잡는다() throws IOException {
        TreeSet<String> found = JavaSourceScan.violations(JavaSourceScan.FIXTURES.resolve("controller-repository"),
                path -> JavaSourceScan.inPackage(path, "controller"), imported -> JavaSourceScan.importsFromPackage(imported, "repository"));

        assertThat(found).containsExactly(
                "billing/controller/BillingController.java -> src.backend.billing.repository.InvoiceRepository");
    }
}

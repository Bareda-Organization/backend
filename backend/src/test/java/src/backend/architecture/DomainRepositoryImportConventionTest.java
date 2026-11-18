package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code domain} 패키지의 정책 객체는 저장소를 import 하지 않는다 — 값을 받아 판정만 하고 읽기는 호출하는 서비스가 한다(CODE_CONVENTIONS §3).
 * 기준선 밖의 새 import 와 이미 사라진 기준선 항목은 둘 다 실패다.
 */
class DomainRepositoryImportConventionTest {

    /** 항목마다 이유를 둔다 — 비어 있으면 {@code domain} 은 {@code repository} 를 모른다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("domain 패키지가 repository 패키지를 import 하는 줄은 기준선과 같다")
    void domain_이_repository_를_import_하는_줄은_기준선과_같다() throws IOException {
        TreeSet<String> actual = JavaSourceScan.violations(JavaSourceScan.MAIN,
                path -> JavaSourceScan.inPackage(path, "domain"), imported -> JavaSourceScan.importsFromPackage(imported, "repository"));

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "domain → repository import");
    }

    @Test
    @DisplayName("모의 domain 클래스의 repository import 는 잡히고 entity import 는 안 잡힌다")
    void 모의_소스의_repository_import_를_잡는다() throws IOException {
        TreeSet<String> found = JavaSourceScan.violations(JavaSourceScan.FIXTURES.resolve("domain-repository"),
                path -> JavaSourceScan.inPackage(path, "domain"), imported -> JavaSourceScan.importsFromPackage(imported, "repository"));

        assertThat(found).containsExactly(
                "billing/domain/InvoicePolicy.java -> src.backend.billing.repository.InvoiceRepository");
    }
}

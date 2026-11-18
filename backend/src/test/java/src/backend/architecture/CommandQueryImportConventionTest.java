package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쓰기 쪽({@code command} 패키지)이 읽기 쪽({@code query} 패키지)을 import 하지 않는다(CODE_CONVENTIONS §3 · §7) — 읽기 규칙이
 * 쓰기 쪽에 필요하면 두 쪽이 함께 쓰는 중립 패키지로 옮긴다. 기준선 밖의 새 import 와 이미 사라진 기준선 항목은 둘 다 실패다.
 */
class CommandQueryImportConventionTest {

    /** 항목마다 이유를 둔다 — 비어 있으면 {@code command} 는 {@code query} 를 모른다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("command 패키지가 query 패키지를 import 하는 줄은 기준선과 같다")
    void command_가_query_를_import_하는_줄은_기준선과_같다() throws IOException {
        TreeSet<String> actual = JavaSourceScan.violations(JavaSourceScan.MAIN,
                path -> JavaSourceScan.inPackage(path, "command"), imported -> JavaSourceScan.importsFromPackage(imported, "query"));

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "command → query import");
    }

    @Test
    @DisplayName("모의 command 클래스의 query import 는 잡히고 query 클래스의 repository import 는 안 잡힌다")
    void 모의_소스의_query_import_를_잡는다() throws IOException {
        TreeSet<String> found = JavaSourceScan.violations(JavaSourceScan.FIXTURES.resolve("command-query"),
                path -> JavaSourceScan.inPackage(path, "command"), imported -> JavaSourceScan.importsFromPackage(imported, "query"));

        assertThat(found).containsExactly(
                "billing/command/BillingCommandService.java -> src.backend.billing.query.InvoiceReader");
    }
}

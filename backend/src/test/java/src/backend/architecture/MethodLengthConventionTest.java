package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CODE_CONVENTIONS §20.2 의 메서드 본문 한도(20 줄)를 소스를 직접 세어 지킨다. 한도를 넘은 메서드는 기준선에 이유와 함께 적힌 것뿐이어야
 * 하고, 기준선에 남았는데 이미 한도 안으로 들어온 메서드가 있으면 그것도 실패다.
 */
class MethodLengthConventionTest {

    /** {@code "<경로> <메서드 이름>"} 형태 — 항목마다 이유를 둔다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("본문이 20 줄을 넘는 메인 소스 메서드는 기준선과 같다")
    void 한도를_넘는_메서드는_기준선과_같다() throws IOException {
        TreeSet<String> actual = longMethods(JavaSizeScanner.scan(JavaSourceScan.MAIN));

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "메서드 본문 20 줄");
    }

    @Test
    @DisplayName("모의 소스의 21 줄 메서드는 잡히고 20 줄 메서드는 안 잡힌다")
    void 모의_소스의_긴_메서드를_잡는다() throws IOException {
        TreeSet<String> found = longMethods(JavaSizeScanner.scan(JavaSourceScan.FIXTURES.resolve("method-length")));

        assertThat(found).containsExactly("LongMethod.java tooLong");
    }

    private static TreeSet<String> longMethods(List<JavaSizeScanner.Offender> offenders) {
        return offenders.stream().filter(offender -> !offender.symbol().equals("*"))
                .map(offender -> offender.path() + " " + offender.symbol())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}

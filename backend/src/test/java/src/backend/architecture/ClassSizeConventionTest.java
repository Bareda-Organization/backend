package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CODE_CONVENTIONS §20.2 의 파일 크기 한도(200 줄)를 소스를 직접 세어 지킨다 — 한도는 리뷰만 잡았다. 한도를 넘은 파일은 기준선에 이유와
 * 함께 적힌 것뿐이어야 하고, 기준선에 남았는데 이미 한도 안으로 들어온 파일이 있으면 그것도 실패다.
 */
class ClassSizeConventionTest {

    /** {@code "<경로> *"} 형태 — 항목마다 이유를 둔다. */
    private static final Set<String> BASELINE = Set.of();

    @Test
    @DisplayName("200 줄을 넘는 메인 소스 파일은 기준선과 같다")
    void 한도를_넘는_파일은_기준선과_같다() throws IOException {
        TreeSet<String> actual = oversizedFiles(JavaSizeScanner.scan(JavaSourceScan.MAIN));

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "파일 크기 200 줄");
    }

    @Test
    @DisplayName("모의 소스의 201 줄 파일은 잡히고 메서드 길이는 이 규칙이 보지 않는다")
    void 모의_소스의_큰_파일을_잡는다() throws IOException {
        TreeSet<String> found = oversizedFiles(JavaSizeScanner.scan(JavaSourceScan.FIXTURES.resolve("class-size")));

        assertThat(found).containsExactly("Oversized.java *");
    }

    private static TreeSet<String> oversizedFiles(java.util.List<JavaSizeScanner.Offender> offenders) {
        return offenders.stream().filter(offender -> offender.symbol().equals("*"))
                .map(offender -> offender.path() + " *").collect(Collectors.toCollection(TreeSet::new));
    }
}

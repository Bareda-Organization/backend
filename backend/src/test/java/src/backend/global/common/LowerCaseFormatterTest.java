package src.backend.global.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * F3 S1 목표 5 — {@link LowerCaseFormatter} 자체의 변환 규칙(그 자바독이 다른 곳에서 검증됐다고
 * 미뤄 두고 있던 것을 여기서 채운다).
 */
class LowerCaseFormatterTest {

    @Test
    void null_은_그대로_통과한다() {
        assertThat(LowerCaseFormatter.lower(null)).isNull();
    }

    @Test
    void 대문자_문자열을_소문자로_바꾼다() {
        assertThat(LowerCaseFormatter.lower("VEHICLE_FAULT")).isEqualTo("vehicle_fault");
    }

    /**
     * JVM 기본 로케일이 터키어면 {@code toLowerCase()} 가 {@code I} 를 점 없는 {@code ı}(U+0131)로
     * 바꾼다 — {@code LowerCaseEnumConverterTest} 의 같은 이름 테스트와 같은 근거. 다른 테스트에
     * 영향을 주지 않도록 {@code try/finally} 로 기본 로케일을 반드시 원복한다.
     */
    @Test
    void 터키어_로케일에서도_대소문자_변환이_로케일_불변으로_동작한다() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThat(LowerCaseFormatter.lower("TRAFFIC")).isEqualTo("traffic");
        } finally {
            Locale.setDefault(original);
        }
    }
}

package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

/** {@code Patch<@Size(max = 3) String>} 안쪽 값에 제약이 걸린다 — 추출기가 등록되지 않으면 길이 초과가 검증을 그냥 지난다. */
class PatchValueExtractorTest {

    private record Body(Patch<@Size(max = 3) String> name) {
    }

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    @DisplayName("안쪽 값이 제약을 넘으면 위반이 하나 나온다")
    void 안쪽_값의_제약_위반() {
        Set<ConstraintViolation<Body>> violations = FACTORY.getValidator().validate(new Body(Patch.of("네글자다")));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).startsWith("name");
    }

    @Test
    @DisplayName("제약 안의 값 · 명시적 null · 키 없음은 위반이 없다")
    void 제약_안의_값은_통과() {
        assertThat(FACTORY.getValidator().validate(new Body(Patch.of("세글자")))).isEmpty();
        assertThat(FACTORY.getValidator().validate(new Body(Patch.of(null)))).as("명시적 null").isEmpty();
        assertThat(FACTORY.getValidator().validate(new Body(null))).as("키 없음").isEmpty();
    }
}

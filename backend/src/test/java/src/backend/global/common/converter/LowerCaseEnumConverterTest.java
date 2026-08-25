package src.backend.global.common.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Role;
import src.backend.run.entity.RunStatus;

/**
 * {@link LowerCaseEnumConverter} 자체의 변환 규칙을 {@link Role} 을 표본 삼아 검증한다.
 *
 * <p>{@code Role} 을 고른 이유 — {@code SYSTEM_ADMIN} 처럼 밑줄이 있는 상수가 있어야
 * "단순 소문자화"가 아니라 "밑줄을 보존한 채 소문자화"를 확인할 수 있다.
 */
class LowerCaseEnumConverterTest {

    private final Role.Db converter = new Role.Db();

    @Test
    void 밑줄_있는_상수는_소문자_snake_case_로_저장된다() {
        String dbValue = converter.convertToDatabaseColumn(Role.SYSTEM_ADMIN);

        assertThat(dbValue).isEqualTo("system_admin");
    }

    @Test
    void 소문자_snake_case_값은_대문자_상수로_복원된다() {
        Role restored = converter.convertToEntityAttribute("system_admin");

        assertThat(restored).isEqualTo(Role.SYSTEM_ADMIN);
    }

    @Test
    void null_은_양방향으로_그대로_통과한다() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void enum_에_없는_DB_값은_예외로_실패한다() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not_a_role"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * JVM 기본 로케일이 터키어면 {@code toLowerCase()}/{@code toUpperCase()} 가 {@code I}/{@code i}
     * 를 점 유무가 다른 문자로 바꾼다 — {@code Locale.ROOT} 명시가 빠지면 이 단언이 실패한다.
     * 다른 테스트에 영향을 주지 않도록 {@code try/finally} 로 기본 로케일을 반드시 원복한다.
     */
    @Test
    void 터키어_로케일에서도_대소문자_변환이_로케일_불변으로_동작한다() {
        Locale original = Locale.getDefault();
        RunStatus.Db runStatusConverter = new RunStatus.Db();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            String dbValue = runStatusConverter.convertToDatabaseColumn(RunStatus.IDLE);
            assertThat(dbValue).isEqualTo("idle");

            RunStatus restored = runStatusConverter.convertToEntityAttribute("confirmed");
            assertThat(restored).isEqualTo(RunStatus.CONFIRMED);
        } finally {
            Locale.setDefault(original);
        }
    }
}

package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 요청 문자열을 값 도메인으로 옮기는 유일한 지점 — 어긋난 입력은 전부 422 이고, 주지 않은 값(null)은 그대로 통과한다. */
class ApiValuesTest {

    @Test
    @DisplayName("enum 은 대소문자·앞뒤 공백과 무관하게 옮기고 null 은 null")
    void enum_변환() {
        assertThat(ApiValues.weekday("mon")).isEqualTo(Weekday.MON);
        assertThat(ApiValues.weekday(" SUN ")).isEqualTo(Weekday.SUN);
        assertThat(ApiValues.direction("to_academy")).isEqualTo(Direction.TO_ACADEMY);
        assertThat(ApiValues.recipientRole("parent")).isEqualTo(Role.PARENT);
        assertThat(ApiValues.weekday(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "monday", "mo n", "0"})
    @DisplayName("enum 에 없는 값은 422 이고 문구에 입력이 실린다")
    void 없는_enum_값은_422(String raw) {
        assertThatThrownBy(() -> ApiValues.weekday(raw)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getMessage()).endsWith(raw);
        });
    }

    @Test
    @DisplayName("true/false 필터는 대소문자·공백을 무시하고 그 밖은 422, null 은 null")
    void 불리언_필터() {
        assertThat(ApiValues.ackedFilter("true")).isTrue();
        assertThat(ApiValues.ackedFilter(" FALSE ")).isFalse();
        assertThat(ApiValues.handledFilter(null)).isNull();
        assertThat(ApiValues.groupFilter("True")).isTrue();
        assertThat(ApiValues.assignedTodayFilter("false")).isFalse();
        assertThat(ApiValues.linkedFilter("true")).isTrue();
        for (String raw : new String[] {"abc", "1", "yes", ""}) {
            assertThatThrownBy(() -> ApiValues.ackedFilter(raw)).as("'%s'", raw).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("시각은 HH:mm 두 자리만 받는다")
    void 시각_변환() {
        assertThat(ApiValues.time("08:30")).isEqualTo(LocalTime.of(8, 30));
        assertThat(ApiValues.time(" 23:59 ")).isEqualTo(LocalTime.of(23, 59));
        assertThat(ApiValues.time(null)).isNull();
        for (String raw : new String[] {"8:30", "08:30:00", "0830", "abc", ""}) {
            assertThatThrownBy(() -> ApiValues.time(raw)).as("'%s'", raw).isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-13-01", "2026-02-30", "20260901", "2026-9-1", "내일"})
    @DisplayName("날짜는 YYYY-MM-DD 만 받고 null 은 null")
    void 날짜_변환(String raw) {
        if (raw == null) {
            assertThat(ApiValues.date(raw)).isNull();
            return;
        }
        assertThatThrownBy(() -> ApiValues.date(raw)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("올바른 날짜는 그대로 옮긴다")
    void 올바른_날짜() {
        assertThat(ApiValues.date("2026-09-30")).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(ApiValues.date(" 2026-09-30 ")).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}

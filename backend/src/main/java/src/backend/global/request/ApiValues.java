package src.backend.global.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import src.backend.exception.entity.ExceptionReportType;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 요청이 문자열로 실어 보낸 값을 값 도메인으로 옮기는 유일한 지점 — 어긋난 입력은 전부
 * {@code 422 VALIDATION_FAILED}(API_SPEC §1.11 "형식 위반")다.
 *
 * <p>요청 DTO 가 {@code Weekday}·{@code Direction}·{@code LocalTime} 을 <b>직접 받지 않는</b> 이유는
 * Jackson 역직렬화가 값이 어긋난 요청을 {@code 400} 으로 깨뜨려, {@code 422} 여야 할 입력이
 * 에러 코드 없이 나가기 때문이다({@code ManagerRegisterRequest} 가 {@code role} 을 {@code String} 으로
 * 받는 것과 같은 이유).
 *
 * <p>변환을 한 곳에 모은 것은 <b>표기가 갈리는 것</b>을 막기 위함이다 — 시각을 한쪽이 {@code HH:mm},
 * 다른 쪽이 {@code HH:mm:ss} 로 받으면 같은 값을 두 형태로 저장하게 되고, {@code schedule.depart_time}
 * 과 {@code manager.work_hours} 의 비교(MGR-06)가 표기 차이로 어긋난다.
 */
public final class ApiValues {

    /** 시각 표기 {@code HH:mm}(Ruling 150 · API_SPEC §5.10) — {@code WorkHours} 와 같은 축이다. */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private ApiValues() {
    }

    /** {@code mon}~{@code sun}(§9.8) 을 {@link Weekday} 로 옮긴다. */
    public static Weekday weekday(String raw) {
        return toEnum(Weekday.class, raw, "요일이 아닙니다: ");
    }

    /** {@code to_academy}·{@code from_academy}(§9.7) 를 {@link Direction} 으로 옮긴다. */
    public static Direction direction(String raw) {
        return toEnum(Direction.class, raw, "등하원 방향이 아닙니다: ");
    }

    /**
     * {@code guardian_absent}·{@code road_block}·{@code vehicle_issue}·{@code etc}(§9.8 {@code
     * report_type}) 를 {@link ExceptionReportType} 으로 옮긴다(Phase 11 신설, §4.13 요청 · §5.20
     * 쿼리 필터 둘 다 이 메서드를 쓴다).
     */
    public static ExceptionReportType reportType(String raw) {
        return toEnum(ExceptionReportType.class, raw, "예외 보고 종류가 아닙니다: ");
    }

    /** {@code HH:mm} 을 {@link LocalTime} 으로 옮긴다 — 날짜가 없는 시각이다. */
    public static LocalTime time(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "시각 표기가 HH:mm 이 아닙니다: " + raw);
        }
    }

    /** {@code YYYY-MM-DD} 를 {@link LocalDate} 로 옮긴다. */
    public static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "날짜 표기가 YYYY-MM-DD 가 아닙니다: " + raw);
        }
    }

    /** 소문자 요청 값을 대문자 enum 상수로 옮긴다 — {@code null} 은 "주지 않음" 이라 그대로 통과한다. */
    private static <E extends Enum<E>> E toEnum(Class<E> type, String raw, String failureMessage) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, failureMessage + raw);
        }
    }
}

package src.backend.manager.entity;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 매니저의 근무 시간 — {@code manager.work_hours}({@code jsonb})가 담는 형태를 고정하고 저장 전에
 * 검증한다(ERD §3.3 · MGR-06 · 조율자 Ruling 150).
 *
 * <pre>{@code { "mon": [{ "start": "07:00", "end": "10:00" }, { "start": "16:00", "end": "19:00" }] } }</pre>
 *
 * <p>키는 요일 7종({@link Weekday} · API_SPEC §9.8)이고 <b>키 부재가 곧 그 요일 근무 없음</b>이다.
 * 값을 단일 구간이 아니라 배열로 둔 이유는 등원·하원으로 갈리는 근무가 실재하기 때문이다.
 *
 * <p><b>검증하는 자리가 여기 하나뿐인 것이 요점이다</b> — {@code jsonb} 는 스키마가 없어 DB 가
 * 형태를 막지 못한다. 형태가 자유롭게 늘면 {@code MGR-06} 배치 충돌 판정이 조용히 틀린다.
 *
 * <p><b>자정을 넘는 구간은 담지 않는다</b> — 통학버스 운행 시간대에 부재하고, 허용하면 겹침 판정이
 * 두 배로 복잡해진다. 시작·종료가 같은 날의 {@link LocalTime} 이라 {@code start < end} 하나가
 * 역전 구간과 자정 통과를 함께 막는다.
 *
 * @param byWeekday 요일별 근무 구간. 근무가 없는 요일은 키 자체가 없다
 */
public record WorkHours(Map<Weekday, List<Interval>> byWeekday) {

    /** 구간 객체가 갖는 키 — 이 둘 외의 키가 오면 형태가 늘어난 것이라 거부한다. */
    private static final String START_KEY = "start";

    private static final String END_KEY = "end";

    private static final Set<String> INTERVAL_KEYS = Set.of(START_KEY, END_KEY);

    /** 시각 표기 {@code HH:mm}(Ruling 150) — {@code schedule.depart_time} 과 같은 축에서 비교된다. */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** 근무 구간 한 칸 — 같은 날 안의 시작·종료다. */
    public record Interval(LocalTime start, LocalTime end) {
    }

    /**
     * 요청이 준 원문을 검증해 옮긴다 — 어긋나면 {@code 422 VALIDATION_FAILED}(§1.11 "형식 위반").
     *
     * <p>원문을 {@code Map} 그대로 받는 이유는 {@code jsonb} 에 스키마가 부재하기 때문이다. 요청
     * DTO 에 형태를 박아 두면 그 형태를 통과한 값만 여기 오지만, <b>DTO 를 거치지 않는 경로</b>
     * (시드·배치·다음 Phase 의 다른 입력)가 생기면 검증이 통째로 빠진다.
     *
     * @param raw 요일명 → 구간 객체 배열. {@code null} 이면 근무 시간 미기재({@code null} 반환)
     */
    public static WorkHours of(Map<String, List<Map<String, String>>> raw) {
        if (raw == null) {
            return null;
        }
        Map<Weekday, List<Interval>> parsed = new LinkedHashMap<>();
        raw.forEach((weekday, intervals) -> parsed.put(toWeekday(weekday), toIntervals(intervals)));
        return new WorkHours(parsed);
    }

    /** {@code jsonb} 컬럼에 담을 형태로 되돌린다 — 요일 키는 소문자, 시각은 {@code HH:mm} 문자열이다. */
    public Map<String, Object> toColumnValue() {
        Map<String, Object> column = new LinkedHashMap<>();
        byWeekday.forEach((weekday, intervals) ->
                column.put(weekday.name().toLowerCase(Locale.ROOT), intervals.stream()
                        .map(interval -> Map.of(START_KEY, interval.start().format(TIME_FORMAT),
                                END_KEY, interval.end().format(TIME_FORMAT)))
                        .toList()));
        return column;
    }

    private static Weekday toWeekday(String raw) {
        try {
            return Weekday.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "근무 요일이 아닙니다: " + raw);
        }
    }

    private static List<Interval> toIntervals(List<Map<String, String>> raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "근무 구간 배열이 필요합니다");
        }
        return raw.stream().map(WorkHours::toInterval).toList();
    }

    /**
     * 구간 하나를 옮긴다 — 키 구성 · 시각 형식 · 시작&lt;종료 셋을 함께 본다.
     *
     * <p>{@code start >= end} 를 거부하는 것이 역전 구간과 자정 통과를 <b>동시에</b> 막는다.
     */
    private static Interval toInterval(Map<String, String> raw) {
        if (raw == null || !INTERVAL_KEYS.equals(raw.keySet())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "근무 구간은 start·end 두 값만 갖는다");
        }
        Interval interval = new Interval(toTime(raw.get(START_KEY)), toTime(raw.get(END_KEY)));
        if (!interval.start().isBefore(interval.end())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "근무 구간의 시작이 종료보다 빨라야 한다: " + raw.get(START_KEY) + "~" + raw.get(END_KEY));
        }
        return interval;
    }

    private static LocalTime toTime(String raw) {
        try {
            return LocalTime.parse(raw, TIME_FORMAT);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "시각 표기가 HH:mm 이 아니다: " + raw);
        }
    }
}

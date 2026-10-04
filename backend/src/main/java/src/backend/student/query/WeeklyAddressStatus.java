package src.backend.student.query;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 학생의 요일별 주소 등록 상태(API_SPEC §5.11 {@code weekly_address_status}, Ruling 815) — {@code none} 등록 0건 ·
 * {@code partial} 등록한 요일 중 한 방향만 있는 요일이 하나라도 있음(그날 돌아오는 버스가 없다) · {@code complete} 등록한 요일마다
 * 두 방향이 다 있음. 몇 요일을 다니는지는 학원이 정하므로 요일 수는 따지지 않는다.
 */
enum WeeklyAddressStatus {
    NONE, PARTIAL, COMPLETE;

    /** 응답 문자열({@code none}·{@code partial}·{@code complete}). */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 한 학생이 등록한 (요일, 방향) 칸들로 상태를 가른다. */
    static WeeklyAddressStatus of(Collection<WeeklySlot> slots) {
        if (slots.isEmpty()) {
            return NONE;
        }
        Map<Weekday, Set<Direction>> directionsByWeekday = new HashMap<>();
        for (WeeklySlot slot : slots) {
            directionsByWeekday.computeIfAbsent(slot.weekday(), weekday -> EnumSet.noneOf(Direction.class))
                    .add(slot.direction());
        }
        boolean someWeekdayHasOneDirection = directionsByWeekday.values().stream()
                .anyMatch(directions -> directions.size() < Direction.values().length);
        return someWeekdayHasOneDirection ? PARTIAL : COMPLETE;
    }

    /** 등록된 한 칸 — 요일과 방향. */
    record WeeklySlot(Weekday weekday, Direction direction) {
    }
}

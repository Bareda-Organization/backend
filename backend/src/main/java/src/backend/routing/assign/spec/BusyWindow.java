package src.backend.routing.assign.spec;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 매니저가 이미 배정된 다른 회차 하나의 시간대 — {@link RejectReason#ALREADY_ASSIGNED} 판정의 근거다.
 *
 * <p>겹침은 <b>양끝을 포함한다</b>(API_SPEC §5.14 {@code MANAGER_DOUBLE_BOOKED}, Ruling 193) — 수동 배치 판정
 * ({@code AssignmentConflictDetector})과 같은 경계다. 앞 회차가 끝나는 시각에 뒤 회차가 시작하는 맞배치도 겹침으로
 * 본다. 자동 배정만 배타로 두면 수동 배치가 경고를 내는 배치를 자동 배정은 경고 없이 채운다.
 * 소요가 없는 회차는 시작과 종료가 같은 한 점이다.
 */
public record BusyWindow(OffsetDateTime start, OffsetDateTime end) {

    public BusyWindow {
        Objects.requireNonNull(start, "시작 시각이 없다");
        Objects.requireNonNull(end, "종료 시각이 없다");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("시작이 종료보다 늦다: " + start + "~" + end);
        }
    }

    /** 주어진 구간과 겹치는가 — 양끝이 닿기만 해도 겹침이다. */
    public boolean overlaps(OffsetDateTime otherStart, OffsetDateTime otherEnd) {
        return !start.isAfter(otherEnd) && !otherStart.isAfter(end);
    }
}

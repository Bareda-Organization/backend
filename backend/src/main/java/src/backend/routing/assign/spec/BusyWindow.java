package src.backend.routing.assign.spec;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 매니저가 이미 배정된 다른 회차 하나의 시간대 — {@link RejectReason#ALREADY_ASSIGNED} 판정의 근거다.
 *
 * <p>겹침은 <b>양끝을 포함하지 않는다</b> — 근무 시간 판정({@link RejectReason#OUT_OF_WORK_HOURS})과
 * 반대 방향의 경계다. 앞 회차가 끝나는 시각에 뒤 회차가 시작하는 맞배치는 실제 운영에서 흔한 형태라,
 * 그것까지 충돌로 잡으면 겹치지 않는 두 회차조차 자동 배정에서 매번 걸러진다.
 */
public record BusyWindow(OffsetDateTime start, OffsetDateTime end) {

    public BusyWindow {
        Objects.requireNonNull(start, "시작 시각이 없다");
        Objects.requireNonNull(end, "종료 시각이 없다");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("시작이 종료보다 빨라야 한다: " + start + "~" + end);
        }
    }

    /** 주어진 구간과 겹치는가 — 양끝이 닿기만 하는 것은 겹침이 아니다. */
    public boolean overlaps(OffsetDateTime otherStart, OffsetDateTime otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}

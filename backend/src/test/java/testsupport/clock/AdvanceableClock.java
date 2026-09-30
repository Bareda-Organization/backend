package testsupport.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 실제 시각을 따라가되 시험이 {@link #advance} 로 앞으로 밀 수 있는 시계(BR-265) — {@code Thread.sleep} 으로
 * 토큰 만료를 기다리는 대신 시각을 옮긴다. {@link #reset} 으로 원래대로 돌린다.
 */
public final class AdvanceableClock extends Clock {

    private final Clock base;

    private volatile Duration offset = Duration.ZERO;

    public AdvanceableClock(Clock base) {
        this.base = base;
    }

    /** 지금부터 시각을 {@code amount} 만큼 더 앞으로 민다. */
    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    /** 밀어 둔 만큼을 되돌린다 — 같은 컨텍스트를 쓰는 다음 시험이 시각 이동을 물려받지 않게 한다. */
    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(base.withZone(zone), offset);
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }
}

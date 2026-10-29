package testsupport.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 시험이 {@link #set} 으로 시각을 임의의 순간에 놓는 시계 — 날짜 경계(KST 23:59 · 00:01)처럼 고정 시계 하나로 못 보는 순간들을 한 컨텍스트에서 본다. */
public final class SettableClock extends Clock {

    private final ZoneId zone;

    private volatile Instant now;

    public SettableClock(Instant initial, ZoneId zone) {
        this.now = initial;
        this.zone = zone;
    }

    /** 시계를 {@code instant} 에 놓는다. */
    public void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SettableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}

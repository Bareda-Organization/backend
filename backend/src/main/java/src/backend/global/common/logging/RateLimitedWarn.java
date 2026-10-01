package src.backend.global.common.logging;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.slf4j.Logger;

/**
 * 같은 곳에서 반복되는 실패를 <b>한 간격에 한 번만</b> 스택과 함께 경고로 남기고 나머지는 센다(R46 S-3) — Redis 가 죽은 동안
 * 위치 수신마다·폴링마다·틱마다 같은 스택트레이스가 초당 수십~백 줄 쏟아져 원인 추적이 어렵고, 로그 드라이버가 블로킹
 * 모드면 전송 지연이 로그를 남기는 모든 스레드를 멈출 수 있다. 로그를 남긴 다음 줄에는 그동안 생략한 건수가 붙는다.
 *
 * <p>호출 위치마다 하나씩 둔다 — 서로 다른 실패가 한 줄을 두고 다투지 않게 한다.
 */
public final class RateLimitedWarn {

    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final Logger log;

    private final long intervalNanos;

    private final LongSupplier nanoClock;

    private boolean everLogged;

    private long lastLoggedAt;

    private long suppressed;

    public RateLimitedWarn(Logger log, Duration interval, LongSupplier nanoClock) {
        this.log = log;
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
    }

    /** 분당 1건 — 운영에서 쓰는 형태. */
    public static RateLimitedWarn perMinute(Logger log) {
        return new RateLimitedWarn(log, DEFAULT_INTERVAL, System::nanoTime);
    }

    /**
     * 이번 간격의 첫 실패면 스택과 함께 남기고, 아니면 건수만 올린다. {@code format} 의 {@code {}} 자리는 {@code args} 순서다.
     */
    public synchronized void warn(Throwable cause, String format, Object... args) {
        long now = nanoClock.getAsLong();
        if (everLogged && now - lastLoggedAt < intervalNanos) {
            suppressed++;
            return;
        }
        String message = suppressed == 0 ? format : format + " (직전 " + suppressed + "건은 같은 오류라 로그를 생략했다)";
        Object[] withCause = java.util.Arrays.copyOf(args, args.length + 1);
        withCause[args.length] = cause;
        log.warn(message, withCause);
        everLogged = true;
        lastLoggedAt = now;
        suppressed = 0;
    }
}

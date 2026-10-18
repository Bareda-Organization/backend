package src.backend.global.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 를 쓸 수 없는 동안만 쓰는 이 인스턴스 안의 짧은 기억 — 개수 상한과 항목별 만료가 있다. 운영이 인스턴스 1대일 때 Redis 가 하던 일(미리보기 보관 · 감사 묶기)을 그대로
 * 대신해, Redis 가 멈춘 동안 같은 입력이 지도 API 나 DB 쓰기를 반복해서 부르지 않게 한다(Ruling 919 B ⑤). 인스턴스가 여러 대면 인스턴스끼리 나누지 못하는 값이라
 * 정합성의 근거로 쓰지 않는다 — 호출부는 이 기억을 Redis 가 실패했을 때만 읽고, 지우는 경로는 Redis 와 이 기억 둘 다 지운다.
 *
 * <p>가득 차면 만료된 항목을 먼저 치우고, 그래도 가득이면 아무 항목 하나를 버린다 — 버려진 항목은 다시 계산 · 다시 기록되는 쪽 오차다.
 */
public final class LocalFallbackMemory<V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    public LocalFallbackMemory(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /** {@code ttl} 동안 기억한다 — 같은 키가 있으면 덮어쓴다. */
    public void put(String key, V value, Duration ttl) {
        makeRoomFor(key);
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    /** 만료되지 않은 항목. */
    public Optional<V> get(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    /** 키가 없거나 만료됐으면 {@code ttl} 동안 맡고 {@code true} — 살아 있는 항목이 있으면 {@code false}. 동시에 부르면 하나만 {@code true} 를 받는다. */
    public boolean claim(String key, V value, Duration ttl) {
        makeRoomFor(key);
        Instant now = clock.instant();
        boolean[] claimed = {false};
        entries.compute(key, (k, existing) -> {
            if (existing != null && now.isBefore(existing.expiresAt())) {
                return existing;
            }
            claimed[0] = true;
            return new Entry<>(value, now.plus(ttl));
        });
        return claimed[0];
    }

    private void makeRoomFor(String key) {
        if (entries.size() < maxEntries || entries.containsKey(key)) {
            return;
        }
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        if (entries.size() >= maxEntries) {
            Iterator<String> keys = entries.keySet().iterator();
            if (keys.hasNext()) {
                entries.remove(keys.next());
            }
        }
    }
}

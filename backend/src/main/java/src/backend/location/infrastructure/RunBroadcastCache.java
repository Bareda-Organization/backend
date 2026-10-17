package src.backend.location.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.routing.dto.PositionStopView;

/**
 * 위치 1건(2초마다)이 읽던 회차별 정차 목록 · 탑승 학생 목록의 메모리 캐시(RT-5, Ruling 899 K) — 두 목록은 도착 · 결석 · 운행 전환 때만
 * 바뀌므로 그 이벤트가 커밋될 때 비우고({@code RunBroadcastCacheEvictionListener}) 그 사이에는 다시 읽지 않는다. 1대 운영 기준이라 인스턴스 간
 * 비움 전파는 R59 에서 한다.
 *
 * <p><b>비움과 읽기가 겹쳐도 낡은 목록이 남지 않는다</b> — 읽기가 시작된 뒤 비움이 한 번이라도 일어났으면 읽은 값을 넣지 않는다(비움 횟수
 * 비교). 놓친 이벤트가 있어도 {@value #BACKSTOP_MINUTES}분 뒤에는 스스로 다시 읽는다.
 */
@Component
@RequiredArgsConstructor
public class RunBroadcastCache {

    private static final int BACKSTOP_MINUTES = 5;

    /** 동시에 운행 중인 회차 수보다 훨씬 큰 안전 상한 — 넘으면 통째로 비운다(ponytail: 필요하면 LRU 로). */
    private static final int MAX_RUNS = 2_000;

    private record Entry<T>(T value, Instant expiresAt) {
    }

    private final Clock clock;

    private final AtomicLong evictions = new AtomicLong();

    private final Map<Long, Entry<List<PositionStopView>>> stops = new ConcurrentHashMap<>();

    private final Map<Long, Entry<List<Long>>> students = new ConcurrentHashMap<>();

    /** 회차의 정차 목록 — 없으면 {@code loader} 로 읽어 넣는다. */
    public List<PositionStopView> stops(Long runId, Supplier<List<PositionStopView>> loader) {
        return get(stops, runId, loader);
    }

    /** 회차의 결석이 아닌 학생 id 목록 — 없으면 {@code loader} 로 읽어 넣는다. */
    public List<Long> students(Long runId, Supplier<List<Long>> loader) {
        return get(students, runId, loader);
    }

    /** 회차의 두 목록을 모두 비운다 — 도착 · 운행 시작 · 종료 · 취소 · 노선 확정. */
    public void evict(Long runId) {
        evictions.incrementAndGet();
        stops.remove(runId);
        students.remove(runId);
    }

    /** 회차의 학생 목록만 비운다 — 결석 전이 · 탑승자 최초 생성. */
    public void evictStudents(Long runId) {
        evictions.incrementAndGet();
        students.remove(runId);
    }

    private <T> T get(Map<Long, Entry<T>> cache, Long runId, Supplier<T> loader) {
        Instant now = clock.instant();
        Entry<T> hit = cache.get(runId);
        if (hit != null && hit.expiresAt().isAfter(now)) {
            return hit.value();
        }
        long evictionsBefore = evictions.get();
        T loaded = loader.get();
        if (evictions.get() == evictionsBefore) {
            if (cache.size() >= MAX_RUNS) {
                cache.clear();
            }
            cache.put(runId, new Entry<>(loaded, now.plus(Duration.ofMinutes(BACKSTOP_MINUTES))));
        }
        return loaded;
    }
}

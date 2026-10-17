package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import src.backend.account.repository.AccountRepository;

/**
 * 비움이 질의와 겹쳐도 비움 이전의 옛 값이 보관되지 않는다(R57 RT-1 · Ruling 880 "다음 요청부터 거절") — 두 겹침을 결정적으로 만든다.
 * ① 질의가 DB 를 기다리는 동안 비움이 끝나는 경우, ② 비움이 "보관하기로 한 판단" 과 실제 보관 사이에 끼어드는 경우(보관 맵이 보관 직전에 비움을 일으킨다).
 * 어느 쪽도 동기화에 sleep 을 쓰지 않는다 — 래치로 순서를 고정한다.
 */
class AccountStandingEvictionRaceTest {

    private static final long ACCOUNT = 7L;
    private static final long TIMEOUT_SECONDS = 5;

    private final AccountRepository repository = mock(AccountRepository.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void 질의가_DB를_기다리는_동안_비움이_끝나면_옛_값은_보관되지_않고_다음_확인은_새로_읽는다() throws Exception {
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        when(repository.findIdsWithLostStanding(anyCollection())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            assertThat(releaseLookup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return List.of(); // 비움 전의 상태 — 권한을 잃지 않음
        }).thenReturn(List.of(ACCOUNT)); // 비움 뒤의 상태 — 권한을 잃음
        AccountStanding standing = new AccountStanding(repository, Clock.systemUTC(), Duration.ofSeconds(5));

        Future<Boolean> inFlight = executor.submit(() -> standing.isLost(ACCOUNT));
        assertThat(lookupStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        standing.evict(ACCOUNT);
        releaseLookup.countDown();

        assertThat(inFlight.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("질의를 시작한 요청은 자기가 읽은 값을 돌려받는다").isFalse();
        assertThat(standing.isLost(ACCOUNT)).as("다음 확인은 비움 뒤의 새 상태다").isTrue();
        verify(repository, times(2)).findIdsWithLostStanding(anyCollection());
    }

    @Test
    void 비움이_보관_직전에_끼어들어도_옛_값은_보관되지_않는다() {
        when(repository.findIdsWithLostStanding(anyCollection())).thenReturn(List.of()).thenReturn(List.of(ACCOUNT));
        EvictsBeforeStore map = new EvictsBeforeStore();
        AccountStanding standing = new AccountStanding(repository, Clock.systemUTC(), Duration.ofSeconds(5), map);
        map.beforeStore = () -> standing.evict(ACCOUNT); // 확인 뒤 · 보관 앞의 틈에 비움이 도착한다

        assertThat(standing.isLost(ACCOUNT)).isFalse();
        map.beforeStore = () -> { };

        assertThat(standing.isLost(ACCOUNT)).as("틈에 도착한 비움 뒤 첫 확인은 새 상태를 읽는다 — 옛 값이 5초 남으면 Ruling 880 이 깨진다").isTrue();
    }

    /** 보관({@code put} · {@code compute}) 직전에 훅을 한 번 부르는 맵. */
    private static final class EvictsBeforeStore extends ConcurrentHashMap<Long, AccountStanding.Cached> {

        private Runnable beforeStore = () -> { };

        @Override
        public AccountStanding.Cached put(Long key, AccountStanding.Cached value) {
            beforeStore.run();
            return super.put(key, value);
        }

        @Override
        public AccountStanding.Cached compute(Long key,
                BiFunction<? super Long, ? super AccountStanding.Cached, ? extends AccountStanding.Cached> function) {
            beforeStore.run();
            return super.compute(key, function);
        }
    }
}

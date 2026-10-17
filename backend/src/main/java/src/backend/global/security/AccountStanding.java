package src.backend.global.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import src.backend.account.repository.AccountRepository;

/**
 * 계정이 <b>권한을 잃었는가</b>(Ruling 880) — 차단 · 퇴사한 관계자 · 삭제된 매니저면 그 계정이 이미 받은 access 토큰은
 * 다음 요청부터 {@code 401 TOKEN_EXPIRED} 다. 토큰은 발급 때의 상태를 싣고 있어 서버가 현재 상태를 다시 묻지 않으면
 * 최대 access 유효 시간(15분) 동안 옛 권한이 그대로 통한다.
 *
 * <p>로그인도 같은 판정을 쓴다(Ruling 891) — 비밀번호 대조를 통과한 계정이 이미 권한을 잃었으면 {@code 403 AUTH_STAFF_INACTIVE} 로
 * 거절한다. 로그인은 200 인데 첫 요청부터 401 이 되풀이되는 것을 막고, 두 자리의 기준이 어긋나지 않게 한다.
 *
 * <p><b>계정별 결과를 최대 {@code app.security.account-standing-cache-ttl}(5초) 보관한다</b>(R57 RT-1 · Ruling 899 A) — 요청마다 묻던
 * 기본키 조회(위치 송신 2초 × 기사 수)가 연결 풀을 채웠다. "다음 요청부터" 가 "5초 뒤부터" 로 늦어지지 않게, 상태를 바꾸는 쪽이
 * 커밋 직후 {@link #evict} 로 그 계정 항목을 비운다({@link AccountStandingEvictionListener}). 비우는 쪽이 하나라도 빠지면 응답이 200 그대로라
 * 어떤 검사도 못 잡으므로 {@code AccountStandingCacheTest} 가 경로마다 실제 커밋으로 확인한다. 0초면 캐시를 쓰지 않는다 — 시험 묶음이 그 값으로
 * 돌아 직접 SQL 로 상태를 바꾸는 옛 시험이 그대로 통한다. 인스턴스마다 따로 보관하므로 2대가 되면 비움 신호 전파가 필요하다(R59).
 */
@Component
public class AccountStanding {

    /** PostgreSQL 바인드 파라미터 한도(32,767)에 한참 못 미치는 한 번의 질의 크기. */
    private static final int QUERY_CHUNK = 1000;

    /** 보관 항목 상한 — 계정 수(수천)보다 크게 두고, 넘으면 만료된 항목부터 치운다. */
    private static final int CACHE_LIMIT = 20_000;

    /** 질의 결과와 그 값을 믿을 수 있는 마지막 시각(epoch ms). */
    private record Cached(boolean lost, long expiresAtMillis) {
    }

    private final AccountRepository accountRepository;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    /** 비움이 일어날 때마다 오른다 — 질의 도중에 비움이 끼어들면 그 질의 결과를 보관하지 않는다(옛 값이 비움 뒤에 되살아나는 것을 막는다). */
    private final AtomicLong evictions = new AtomicLong();

    public AccountStanding(AccountRepository accountRepository, Clock clock,
            @Value("${app.security.account-standing-cache-ttl:5s}") Duration cacheTtl) {
        this.accountRepository = accountRepository;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    /** 이 계정이 권한을 잃었으면 참 — 계정 행이 없으면 거짓(알 수 없는 것을 잃었다고 읽지 않는다). */
    public boolean isLost(Long accountId) {
        if (cacheTtl.isZero()) {
            return queryLost(accountId);
        }
        long now = clock.millis();
        Cached hit = cache.get(accountId);
        if (hit != null && now < hit.expiresAtMillis()) {
            return hit.lost();
        }
        long epoch = evictions.get();
        boolean lost = queryLost(accountId);
        if (epoch == evictions.get()) {
            store(accountId, new Cached(lost, now + cacheTtl.toMillis()), now);
        }
        return lost;
    }

    /** 이 계정의 보관 항목을 비운다 — 상태가 바뀐 트랜잭션이 커밋된 직후 부른다. */
    public void evict(Long accountId) {
        evictions.incrementAndGet();
        cache.remove(accountId);
    }

    /** 주어진 계정 중 권한을 잃은 계정 — 열린 WebSocket 세션 점검이 계정 수만큼 묻지 않고 묶음으로 묻는다. 보관 항목을 거치지 않는다. */
    public Set<Long> lostAmong(Collection<Long> accountIds) {
        List<Long> ids = List.copyOf(accountIds);
        Set<Long> lost = new HashSet<>();
        for (int from = 0; from < ids.size(); from += QUERY_CHUNK) {
            lost.addAll(accountRepository.findIdsWithLostStanding(ids.subList(from, Math.min(from + QUERY_CHUNK, ids.size()))));
        }
        return lost;
    }

    private boolean queryLost(Long accountId) {
        return !accountRepository.findIdsWithLostStanding(List.of(accountId)).isEmpty();
    }

    private void store(Long accountId, Cached entry, long now) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.values().removeIf(cached -> now >= cached.expiresAtMillis());
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
        }
        cache.put(accountId, entry);
    }
}

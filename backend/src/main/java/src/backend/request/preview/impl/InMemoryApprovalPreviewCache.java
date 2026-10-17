package src.backend.request.preview.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;

/**
 * {@link ApprovalPreviewCache} 의 프로세스 메모리 구현 — 백엔드 인스턴스가 항상 1개인 이 서비스의
 * 배포 전제(CLAUDE.md)에서는 인스턴스 간 공유가 불필요하다.
 *
 * <p><b>Redis 대신 이것을 고른 이유</b> — 미리보기는 관리자가 승인 화면을 연 뒤 결정하기까지의
 * 짧은 구간에서만 쓰이고 그 범위를 벗어나면 값이 낡는다(승인·거절 어느 쪽이든 결정과 함께 이 값의
 * 효용이 끝난다). Redis 를 쓰면 {@link src.backend.routing.pipeline.RouteComputation}(정차 순서 ·
 * 도착 예정 시각 목록을 포함한 복합 값)을 직렬화해야 하는데, 지금은 얻는 것이 없다 — 다중 인스턴스가
 * 되는 시점에야 그 비용을 들일 이유(요청이 다른 인스턴스로 갈 수 있음)가 생긴다. 그때 이 클래스만
 * 교체하면 되도록 {@link ApprovalPreviewCache} 뒤에 감췄다.
 *
 * <p><b>상한 500건 · 하루 만료</b>(DA-11) — 결정되지 않은 채 남는 승인 건이 힙에 쌓이지 않게 한다. 승인 1건이 결정되면
 * {@link ApprovalPreviewCache#evict} 로 지운다(결정의 몫). 2대 운영의 Redis 이전은 이 클래스를 교체해 한다.
 */
@Component
@RequiredArgsConstructor
public class InMemoryApprovalPreviewCache implements ApprovalPreviewCache {

    /** 보관 상한(DA-11, Ruling 899) — 넘으면 가장 먼저 넣은 항목이 밀려난다. */
    static final int MAX_ENTRIES = 500;

    /** 보관 기간 — 하루가 지난 미리보기는 낡아 쓸 수 없다(결정되지 않은 채 남은 승인 건). */
    static final Duration TTL = Duration.ofDays(1);

    private record Entry(ApprovalPreview preview, Instant expiresAt) {
    }

    private final Clock clock;

    private final Map<Long, Entry> store = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    @Override
    public Optional<ApprovalPreview> find(Long approvalId) {
        Entry entry = store.get(approvalId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            store.remove(approvalId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.preview());
    }

    @Override
    public void put(Long approvalId, ApprovalPreview preview) {
        store.put(approvalId, new Entry(preview, clock.instant().plus(TTL)));
    }

    @Override
    public void evict(Long approvalId) {
        store.remove(approvalId);
    }

    @Override
    public void clear() {
        store.clear();
    }
}

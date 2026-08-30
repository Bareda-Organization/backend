package src.backend.request.preview.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

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
 * <p><b>만료·정리를 두지 않는다</b> — 결정되지 않은 채 남는 승인 건이 쌓이면 이 맵도 함께 쌓인다.
 * 이 태스크(T4)의 책임은 승인 1건이 결정되면 {@link ApprovalPreviewCache#evict} 로 지우는 것까지고,
 * 그 호출은 결정(T5, {@code decide})의 몫이다.
 */
@Component
public class InMemoryApprovalPreviewCache implements ApprovalPreviewCache {

    private final Map<Long, ApprovalPreview> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ApprovalPreview> find(Long approvalId) {
        return Optional.ofNullable(store.get(approvalId));
    }

    @Override
    public void put(Long approvalId, ApprovalPreview preview) {
        store.put(approvalId, preview);
    }

    @Override
    public void evict(Long approvalId) {
        store.remove(approvalId);
    }
}

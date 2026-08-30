package src.backend.request.preview.spec;

import java.util.Optional;

/**
 * 승인 미리보기(재최적화 결과) 저장소 — 구현이 인프라(단일 인스턴스 메모리 · Redis 등)에 따라 바뀔
 * 수 있어 spec/impl 로 가른다({@code reference.md} spec/impl 판단 기준: "구현이 바뀔 가능성이 있는가").
 *
 * <p>이 학원 배차 서비스는 백엔드 인스턴스가 <b>항상 1개</b>라는 전제가 이미 여러 곳에 박혀 있다
 * (CLAUDE.md — {@code @Scheduled} 중복·InMemory 버스위치·WS 세션 로컬 보관과 같은 축). 지금은
 * {@link src.backend.request.preview.impl.InMemoryApprovalPreviewCache} 하나뿐이지만, 그 전제가
 * 바뀌면(다중 인스턴스) 이 자리가 Redis 구현으로 교체될 자리다 — 호출부({@code ApprovalQueryService})는
 * 그 교체를 몰라도 된다.
 */
public interface ApprovalPreviewCache {

    /** 이 승인 건에 지금 유효한(가장 최근에 발급된) 미리보기 — 없으면 빈 값. */
    Optional<ApprovalPreview> find(Long approvalId);

    /** 새 미리보기를 저장한다 — 같은 건에 이미 있던 값은 <b>덮어쓴다</b>(입력이 바뀌어 재계산한 경우). */
    void put(Long approvalId, ApprovalPreview preview);

    /**
     * 이 승인 건의 미리보기를 지운다 — 결정(승인·거절, T5)이 끝나 더는 유효하지 않은 미리보기를
     * 남겨 두지 않을 때 쓴다. 이 태스크(T4)는 이 메서드를 호출하지 않는다.
     */
    void evict(Long approvalId);
}

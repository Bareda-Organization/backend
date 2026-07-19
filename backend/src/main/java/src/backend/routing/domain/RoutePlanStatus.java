package src.backend.routing.domain;

/** DRAFT(최초 생성)·RECOMMENDED(재계산 추천, Phase 6e)·APPROVED(승인)·PUBLISHED(배포) 순차 상태전이. */
public enum RoutePlanStatus {
    DRAFT,
    RECOMMENDED,
    APPROVED,
    PUBLISHED
}

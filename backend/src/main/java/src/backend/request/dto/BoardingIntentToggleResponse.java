package src.backend.request.dto;

import java.time.OffsetDateTime;

/**
 * 탑승 의사 토글 응답(API_SPEC §3.6) — 필드 유무·값이 구간마다 갈린다.
 *
 * <p>{@code result} 는 {@link src.backend.request.domain.ChangeWindow} 이름을 그대로 노출하지 않고
 * 이 세 값({@code applied}·{@code pending_approval}·{@code applied_no_reroute})으로 따로 짓는다 —
 * {@code ChangeWindow} 는 "판정 구간"이고 {@code result} 는 "그 구간에서 실제로 벌어진 처리 결과"라
 * 뜻이 다르다(예: ①·③ 모두 즉시 반영이지만 재최적화 유무가 갈려 각각 {@code applied}·
 * {@code applied_no_reroute} 다).
 *
 * <p>{@code change_request_id} 는 {@link src.backend.request.entity.ChangeRequest} 의 PK 를 그대로
 * 옮긴다. 필드 타입은 {@code Long} 이지만 JSON 으로는 {@link src.backend.global.config.IdentifierJsonConfig}
 * 가 문자열로 감싸 내보낸다(API_SPEC §1.1, Ruling 332) — API_SPEC 예시의 {@code "creq_8812"} 접두는
 * 표기 관례일 뿐 실제 값은 순번 그대로다.
 */
public record BoardingIntentToggleResponse(String result, boolean riding, String riderStatus,
        Long changeRequestId, int changeQuotaLeft, OffsetDateTime deadlineAt) {

    private static final String APPLIED = "applied";

    private static final String PENDING_APPROVAL = "pending_approval";

    private static final String APPLIED_NO_REROUTE = "applied_no_reroute";

    /** ①구간 즉시 반영(재최적화 없음, Ruling 198) 응답. */
    public static BoardingIntentToggleResponse applied(boolean riding, String riderStatus, int changeQuotaLeft) {
        return new BoardingIntentToggleResponse(APPLIED, riding, riderStatus, null, changeQuotaLeft, null);
    }

    /**
     * ②구간 승인 대기 응답 — {@code riding} 은 <b>바뀌지 않은 기존 값</b>을 그대로 싣는다(§3.6). 실제
     * 반영은 관리자 승인 이후이기 때문이다.
     */
    public static BoardingIntentToggleResponse pendingApproval(boolean existingRiding, String riderStatus,
            Long changeRequestId, int changeQuotaLeft, OffsetDateTime deadlineAt) {
        return new BoardingIntentToggleResponse(PENDING_APPROVAL, existingRiding, riderStatus, changeRequestId,
                changeQuotaLeft, deadlineAt);
    }

    /** ③구간 즉시 반영(재최적화 없음 — 순번 불변, {@code run_stop.skipped} 만) 응답. */
    public static BoardingIntentToggleResponse appliedNoReroute(boolean riding, String riderStatus,
            int changeQuotaLeft) {
        return new BoardingIntentToggleResponse(APPLIED_NO_REROUTE, riding, riderStatus, null, changeQuotaLeft,
                null);
    }
}

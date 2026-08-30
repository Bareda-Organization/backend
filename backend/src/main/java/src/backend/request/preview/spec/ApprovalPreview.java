package src.backend.request.preview.spec;

import java.util.Objects;

import src.backend.routing.pipeline.RouteComputation;

/**
 * 승인 미리보기 캐시 1건 — 상세 조회(API_SPEC §5.5)가 계산한 재최적화 결과를 다음 조회·결정(T5)이
 * 재사용할 수 있게 담는다.
 *
 * <p>{@code fingerprint} 는 {@link src.backend.run.domain.RunConfirmationFingerprint} 로 뽑은 값이다
 * — 확정 배치가 "이 조건으로 계산했다" 를 남기는 것과 같은 목적으로, 여기서는 "이 조건에서 이
 * {@code token} 을 발급했다" 를 남긴다. {@code decide}(T5)가 결정 시점에 같은 방식으로 다시 뽑은
 * 값과 대조해 어긋나면 {@code 409 PREVIEW_STALE} 로 답한다(화면에서 본 결과와 배포되는 결과의
 * 동일성 보장, API_SPEC §5.5).
 *
 * <p>{@code token} 은 {@link java.util.UUID#randomUUID()} 문자열이다 — 추측 불가능해야
 * {@code decide} 가 "화면에 그 미리보기가 실제로 떴었다" 는 근거로 쓸 수 있다.
 */
public record ApprovalPreview(String token, String fingerprint, RouteComputation computation) {

    public ApprovalPreview {
        Objects.requireNonNull(token, "미리보기 토큰이 없다");
        Objects.requireNonNull(fingerprint, "입력 지문이 없으면 갱신 여부를 판정할 수 없다");
        Objects.requireNonNull(computation, "계산 결과가 없다");
    }
}

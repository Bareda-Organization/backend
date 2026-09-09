package src.backend.request.dto;

/**
 * 승하차지 하나를 가리키는 최소 표현(API_SPEC §5.5 상세 — {@code reordered}·{@code removed}).
 *
 * <p>{@code seq}·{@code eta} 를 담지 않는 이유는 이 두 목록이 "무엇이 바뀌었는지" 만 보여 주면 되고,
 * 자세한 값은 이미 {@code stops_before}·{@code stops_after}(seq·eta 포함)에 있기 때문이다 — 같은 값을
 * 두 자리에 실으면 한쪽만 고쳤을 때 서로 어긋난다.
 */
public record StopRefResponse(Long stopId, String stopName) {
}

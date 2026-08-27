package src.backend.student.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 요일 × 방향 한 칸의 주소(P-05, API_SPEC §3.7 {@code entries[]}).
 *
 * <p>{@code weekday}·{@code direction} 을 enum 이 아니라 문자열로 받는다 — enum 바인딩 실패는
 * {@code 400} 이나 {@code 500} 으로 새기 쉬워, 사양이 정한 {@code 422 VALIDATION_FAILED} 로 옮기는
 * 판정을 서비스가 한다({@code StudentRegisterRequest.gender} 와 같은 형태).
 *
 * <p>길이 상한은 {@code weekly_address} 컬럼 정의를 그대로 옮겼다 — 상한이 코드에 없으면 초과
 * 입력이 {@code 422} 가 아니라 DB 예외를 거친 {@code 500} 으로 나간다.
 */
public record WeeklyAddressEntryRequest(
        @NotBlank String weekday,
        @NotBlank String direction,
        @NotBlank @Size(max = 255) String address,
        @Size(max = 255) String addressDetail) {
}

package src.backend.academy.dto;

import jakarta.validation.constraints.Size;

/**
 * 학원 정보 수정·비활성화 요청(API_SPEC §6.3 PATCH) — 보낸 항목만 반영되고 {@code null} 은 그대로 둔다.
 *
 * <p>{@code code} 필드가 부재한 것이 "코드는 수정 대상 밖" 을 표현하는 방식이다. 본문에 {@code code} 를
 * 실어 보내도 바인딩될 자리가 없어 무시된다.
 *
 * <p>{@code status} 를 enum 이 아니라 문자열로 받는다 — enum 바인딩 실패는 {@code 500} 이나 형식이
 * 다른 400 으로 새기 쉬워, 사양이 정한 {@code 422 VALIDATION_FAILED} 로 옮기는 판정을 서비스가 직접 한다.
 */
public record AcademyUpdateRequest(
        @Size(max = 100) String name,
        @Size(max = 50) String region,
        @Size(max = 255) String address,
        @Size(max = 30) String contact,
        String memo,
        String status) {
}

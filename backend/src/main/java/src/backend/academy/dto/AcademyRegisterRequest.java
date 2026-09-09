package src.backend.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 학원 등록 요청(API_SPEC §6.2).
 *
 * <p>{@code code} 필드가 부재한 것이 사양이다 — 학원 코드는 서버가 자동 생성하며 관리자가 입력하지
 * 않는다. 길이 상한은 {@code academy} 테이블의 컬럼 정의를 그대로 옮겼다.
 */
public record AcademyRegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String region,
        @Size(max = 255) String address,
        @Size(max = 30) String contact,
        String memo) {
}

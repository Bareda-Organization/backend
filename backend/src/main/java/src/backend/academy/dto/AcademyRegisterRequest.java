package src.backend.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 학원 등록 요청(API_SPEC §6.2).
 *
 * <p>{@code address} 는 필수다({@code Ruling 450}) — 주소가 없으면 그 학원의 회차 확정이 전부
 * {@code ACADEMY_COORDINATES_MISSING} 으로 실패하므로 첫 운행 날이 아니라 등록 때 막는다.
 *
 * <p>{@code code} 필드가 부재한 것이 사양이다 — 학원 코드는 서버가 자동 생성하며 관리자가 입력하지
 * 않는다. 길이 상한은 {@code academy} 테이블의 컬럼 정의를 그대로 옮겼다.
 */
public record AcademyRegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String region,
        @NotBlank @Size(max = 255) String address,
        @Size(max = 30) String contact,
        @Size(max = 200) String memo) {
}

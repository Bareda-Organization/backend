package src.backend.account.dto;

import src.backend.academy.entity.Academy;

/**
 * 관계자 가입 요청에 딸린 학원 정보(API_SPEC §6.4 {@code academy}).
 *
 * <p>메인 관리자 콘솔은 전 학원 범위라 요청마다 어느 학원인지가 함께 있어야 한다 — 관계자 축(§5.1)은
 * 목록 전체가 한 학원이라 이 자리가 부재하다.
 *
 * <p>JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupRequestAcademyResponse(Long id, String code, String name, String region) {

    public static SignupRequestAcademyResponse from(Academy academy) {
        return new SignupRequestAcademyResponse(academy.getId(), academy.getCode(), academy.getName(),
                academy.getRegion());
    }
}

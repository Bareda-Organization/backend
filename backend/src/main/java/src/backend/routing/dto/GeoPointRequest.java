package src.backend.routing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.GeoPoint;

/**
 * 요청 본문이 실어 보내는 좌표 한 점(API_SPEC §5.9).
 *
 * <p>{@link GeoPoint} 를 요청 DTO 로 그대로 받지 않는 이유는 그 컴팩트 생성자가 범위 위반에
 * {@link IllegalArgumentException} 을 던지기 때문이다 — Jackson 역직렬화 안에서 터지면 {@code 422}
 * 여야 할 입력이 에러 코드 없는 {@code 400} 이 된다({@code ApiValues} 가 값 도메인 변환을 한 곳에
 * 모은 것과 같은 이유).
 */
public record GeoPointRequest(@NotNull BigDecimal lat, @NotNull BigDecimal lng) {

    /** 계산이 다루는 좌표 타입으로 옮긴다 — 범위 위반은 {@code 422 VALIDATION_FAILED} 다. */
    public GeoPoint toGeoPoint() {
        try {
            return new GeoPoint(lat, lng);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}

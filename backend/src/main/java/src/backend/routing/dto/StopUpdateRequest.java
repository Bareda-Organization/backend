package src.backend.routing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 승하차지 수정 요청(API_SPEC §5.9 · Ruling 849) — 보낸 필드만 고치고 {@code null} 은 키가 없는 것과 같다(§1.14).
 *
 * @param name 보내면 공백만일 수 없다
 * @param address 보내도 공백이면 지금 주소를 둔다({@code Stop#relocate} 와 같은 규칙)
 */
public record StopUpdateRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "이름은 비울 수 없습니다") String name,
        @Size(max = 255) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng) {

    /**
     * 좌표를 옮기는 요청인가 — 위도·경도는 둘 다 주거나 둘 다 비운다. 하나만 주면 나머지 하나를 지금 값으로 채워 옮길지 거절할지
     * 요청만 보고는 알 수 없어 {@code 422} 로 거절한다.
     */
    public boolean givesCoordinates() {
        if ((lat == null) != (lng == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "위도와 경도는 둘 다 주거나 둘 다 비운다");
        }
        return lat != null;
    }
}

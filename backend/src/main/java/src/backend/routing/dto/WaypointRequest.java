package src.backend.routing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 강제 경유 지점 지정 요청(RTE-10, API_SPEC §5.15) — {@code address} 와 {@code lat}/{@code lng} 은
 * 조건부다: 하나라도 있으면 되고, 둘 다 없으면 {@code 422 VALIDATION_FAILED} 다. 둘 다 있으면 좌표를
 * 우선한다(주소 재검증으로 외부 호출을 늘리지 않는다).
 */
public record WaypointRequest(String address, BigDecimal lat, BigDecimal lng, @NotBlank String label, String note,
        @NotNull Boolean apply) {
}

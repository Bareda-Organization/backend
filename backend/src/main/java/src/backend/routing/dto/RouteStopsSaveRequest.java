package src.backend.routing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 노선의 승하차지를 한 번에 저장한다(§5.9, 2026-09-23 사용자 지시 — 고친 뒤 저장 버튼 한 번).
 *
 * <p>배열 순서가 그대로 정차 순서다. 배열에 없는 승하차지는 노선에서만 빠진다.
 *
 * @param stops 저장 후의 정차 목록 전체
 */
public record RouteStopsSaveRequest(@NotNull @Valid List<Item> stops) {

    /** 이미 있는 승하차지의 식별자 — 새로 만들 항목({@code stop_id} 부재)은 뺀다. */
    public List<Long> existingStopIds() {
        return stops.stream().map(Item::stopId).filter(Objects::nonNull).toList();
    }

    /**
     * 정차 하나.
     *
     * @param stopId 이미 있는 승하차지면 그 식별자(이름·자리를 이 값으로 고친다). 비우면 새로 만든다
     * @param address 생략하면 기존 주소를 두고, 새 항목이면 {@code name} 을 주소 자리에 쓴다
     */
    public record Item(
            Long stopId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String address,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng) {
    }
}

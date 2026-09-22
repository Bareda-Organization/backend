package src.backend.routing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 강제 경유 지점 지정 요청(RTE-10, API_SPEC §5.15) — {@code address} 와 {@code lat}/{@code lng} 은
 * 조건부다: 하나라도 있으면 되고, 둘 다 없으면 {@code 422 VALIDATION_FAILED} 다. 둘 다 있으면 좌표를
 * 우선한다(주소 재검증으로 외부 호출을 늘리지 않는다).
 *
 * @param seq 설 자리(1부터). 생략하면 <b>맨 뒤</b>다(2026-09-22 이전의 유일한 동작).
 *            {@code FixedStop.seq} 가 원래부터 <b>최종 순번</b>이고 엔진이 그 자리를 비워 두므로,
 *            이 값은 최적화에 뒤집히지 않는다. 범위 밖이면 {@code 422} 다 — 조용히 맨 뒤로 보내면
 *            관계자가 지정한 자리와 다른 결과를 알아챌 수단이 부재하다
 */
public record WaypointRequest(String address, BigDecimal lat, BigDecimal lng, @NotBlank String label, String note,
        @Min(1) Integer seq, @NotNull Boolean apply) {
}

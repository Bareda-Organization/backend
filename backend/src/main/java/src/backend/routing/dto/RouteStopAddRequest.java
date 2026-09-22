package src.backend.routing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 좌표로 정차지 더하기(§5.9) — 주소 검색으로 찾고 지도에서 <b>확정한</b> 지점 하나.
 *
 * <p>좌표를 서버가 다시 지오코딩하지 않는 이유가 이 기능의 요점이다 — 관계자가 검색 결과를 지도에서
 * 옮겨 "그 블록 왼쪽 모퉁이" 처럼 실제 버스가 서는 자리를 고른다. 주소를 다시 변환하면 그 조정이
 * 사라진다.
 *
 * @param address 검색에 쓴 정규화 주소. 생략하면 {@code name} 을 주소 자리에 쓴다
 */
public record RouteStopAddRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String address) {
}

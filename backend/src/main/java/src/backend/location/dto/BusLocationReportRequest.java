package src.backend.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 기사 앱이 담당 버스의 현재 위치를 보고할 때의 요청 본문(F1).
 *
 * <p>{@code origin} 은 기사 단말이 스스로 밝히는 좌표 출처다. 서버에는 좌표를 만든 주체를 검증할 수단이
 * 없으므로 이 값은 관제 화면 표시용 메타데이터로만 쓴다. 기존 클라이언트가 {@code {busId, lat, lng}} 만
 * 보내고 있어 필수가 아니며, 생략(null)하면 표준 생성자가 {@link LocationOrigin#GPS} 로 채운다 —
 * 덕분에 {@code origin()} 은 절대 null 이 아니고 호출부에 기본값 처리가 흩어지지 않는다.
 */
public record BusLocationReportRequest(
        @NotNull @Schema(example = "1", description = "버스 id(3호차)") Long busId,
        @NotNull @Schema(example = "37.5075") Double lat,
        @NotNull @Schema(example = "127.0355") Double lng,
        @Schema(example = "GPS", description = "좌표 출처(기사 단말 자기신고). 생략하면 GPS 로 간주한다") LocationOrigin origin) {

    public BusLocationReportRequest {
        origin = origin == null ? LocationOrigin.GPS : origin;
    }
}

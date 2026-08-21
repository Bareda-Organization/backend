package src.backend.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 학생 앱이 자기 현재 위치를 보고할 때의 요청 본문.
 * 실 GPS 전환 시에도 이 인터페이스(엔드포인트 계약)는 그대로 재사용하고,
 * 좌표를 만들어내는 쪽(디바이스 GPS ↔ Mock 시뮬레이터)만 교체한다.
 *
 * <p>{@code origin} 은 학생 단말이 스스로 밝히는 좌표 출처다. 서버에는 좌표를 만든 주체를 검증할 수단이
 * 없으므로 표시용 메타데이터로만 쓴다. 기존 클라이언트가 {@code {lat, lng}} 만 보내고 있어 필수가 아니며,
 * 생략(null)하면 표준 생성자가 {@link LocationOrigin#GPS} 로 채운다 — REST 와 STOMP({@code /app/location})가
 * 같은 record 를 쓰므로 두 통로 모두 이 기본값을 그대로 따른다.
 */
public record LocationReportRequest(
        @NotNull @Schema(example = "37.4998") Double lat,
        @NotNull @Schema(example = "127.0245") Double lng,
        @Schema(example = "GPS", description = "좌표 출처(학생 단말 자기신고). 생략하면 GPS 로 간주한다") LocationOrigin origin) {

    public LocationReportRequest {
        origin = origin == null ? LocationOrigin.GPS : origin;
    }
}

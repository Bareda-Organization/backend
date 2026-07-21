package src.backend.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 학생 SOS 발신 요청. 좌표는 학생 앱이 마지막으로 확인한 위치(Mock 또는 실 GPS)를 그대로 담는다.
 */
public record SosTriggerRequest(
        @Schema(example = "37.4998") Double lat,
        @Schema(example = "127.0245") Double lng) {
}

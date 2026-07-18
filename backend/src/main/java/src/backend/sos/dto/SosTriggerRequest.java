package src.backend.sos.dto;

/**
 * 학생 SOS 발신 요청. 좌표는 학생 앱이 마지막으로 확인한 위치(Mock 또는 실 GPS)를 그대로 담는다.
 */
public record SosTriggerRequest(Double lat, Double lng) {
}

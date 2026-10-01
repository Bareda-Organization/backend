package src.backend.exception.dto;

import jakarta.validation.constraints.Size;

/**
 * 비상 신고 확인(ack) 요청(API_SPEC §5.16, Ruling 541) — 본문 자체가 선택이고 {@code memo} 도 선택이다. 본문 없는 확인은
 * 이 기능 이전과 같게 동작한다. 길이 상한 200 은 비상 신고의 발신 메모({@link EmergencyRaiseRequest#memo})와 같다.
 */
public record EmergencyAckRequest(@Size(max = 200) String memo) {
}

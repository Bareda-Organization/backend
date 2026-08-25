package src.backend.account.command;

/**
 * 승인 판정의 공통 입력 — 두 승인 축(API_SPEC §5.2 관계자 · §6.5 메인 관리자)의 요청 DTO 에서
 * 공통 부분만 뽑아낸 값이다.
 *
 * <p>요청 DTO 를 그대로 {@link SignupDecision} 에 넘기지 않는 이유는, 그러면 공통 골격이 두 DTO 중
 * 하나에 결합돼 다른 축이 자기와 무관한 필드({@code link})를 가진 타입을 만들어 넘겨야 하기 때문이다.
 */
public record Decision(boolean accept, String rejectReason) {
}

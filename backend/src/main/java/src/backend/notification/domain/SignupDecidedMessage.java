package src.backend.notification.domain;

/**
 * 가입 결정 알림의 제목·본문(API_SPEC §9.7 {@code signup_decided}) — 수락과 거절이 <b>다른 문구</b>를
 * 쓴다.
 *
 * <p>문구를 리스너에 인라인으로 두지 않은 이유는 거절 문구에 사유가 들어가기 때문이다 — 사유를 빼면
 * 거절된 사람은 무엇을 고쳐 재신청해야 하는지 알 수단이 부재하고(§1.4), 그 누락은 조립하는 자리가
 * 흩어질수록 잡히지 않는다.
 *
 * <p>§7 규칙 12 의 교체 축 {@code NotificationComposer} 는 아직 두지 않았다 — 그 인터페이스가 가르는
 * 축은 <b>알림 종류</b>인데 이 Phase 가 만드는 종류가 하나뿐이라, 지금 넣으면 구현체가 하나인 인터페이스가
 * 생긴다. 종류가 둘이 되는 Phase 가 이 클래스를 그 구현체로 흡수하면 호출부는 한 곳뿐이다.
 */
public record SignupDecidedMessage(String title, String body) {

    private static final String ACCEPTED_TITLE = "가입 승인 안내";

    private static final String REJECTED_TITLE = "가입 거절 안내";

    /** 결정 결과에 맞는 문구를 고른다 — 거절이면 사유를 본문에 함께 싣는다. */
    public static SignupDecidedMessage of(boolean accepted, String rejectReason) {
        return accepted
                ? new SignupDecidedMessage(ACCEPTED_TITLE, "가입이 승인되었습니다. 로그인 후 이용해 주세요.")
                : new SignupDecidedMessage(REJECTED_TITLE, "가입이 거절되었습니다. 사유: " + rejectReason);
    }
}

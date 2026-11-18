package src.backend.account.entity;

/**
 * 사용자가 새 비밀번호를 정하는 모든 요청(가입 · 비밀번호 변경)이 함께 쓰는 길이 규칙 — 8자 이상이고 글자 종류는 묻지 않는다.
 * 로그인은 이 규칙을 보지 않아 규칙 이전에 만든 짧은 비밀번호의 계정도 그대로 들어온다.
 */
public final class PasswordRule {

    /** 최소 글자 수 — {@code @Size(min)} 이 글자(UTF-16 단위) 수로 센다. */
    public static final int MIN_LENGTH = 8;

    /** 최소 길이 위반 문구 — 응답 {@code error.message} 에 {@code 필드명: 문구} 로 실린다. */
    public static final String MIN_LENGTH_MESSAGE = "비밀번호는 8자 이상이어야 합니다";

    private PasswordRule() {
    }
}

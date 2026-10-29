package src.backend.account.entity;

import java.util.regex.Pattern;

/**
 * 로그인 아이디 형식(API_SPEC §2.2) — 영문 대소문자 · 숫자 · {@code .} · {@code _} · {@code -} 로 1~50자. 가입이 이 형식만 받고,
 * 등록되지 않은 아이디로 실패한 로그인은 이 형식에 맞는 입력만 접속 이력에 남긴다(아이디 칸에 잘못 넣은 비밀번호가 2년 보존되지 않게).
 */
public final class LoginIdFormat {

    /** Bean Validation 과 {@link #matches} 가 함께 쓰는 정규식. */
    public static final String PATTERN = "^[A-Za-z0-9._-]{1,50}$";

    private static final Pattern ACCEPTED = Pattern.compile(PATTERN);

    private LoginIdFormat() {
    }

    /** {@code loginId} 가 가입 아이디 형식이면 참 — {@code null} 은 거짓. */
    public static boolean matches(String loginId) {
        return loginId != null && ACCEPTED.matcher(loginId).matches();
    }
}

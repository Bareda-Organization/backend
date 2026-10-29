package src.backend.account.entity;

import java.util.regex.Pattern;

/**
 * 계정 연락처 규칙(API_SPEC §2.2) — 가입 · 복구 · 관계자 계정 수정이 같은 규칙을 쓴다. 숫자 9~11자리(0 으로 시작, 하이픈은 있어도 없어도
 * 된다)를 받아 하이픈 표준형({@code 010-1234-5678} · 지역번호 02 는 {@code 02-1234-5678}) 하나로 저장한다. 이미 저장된 번호는 표기가
 * 어느 쪽이든 {@link #digits} 로 비교해 찾는다.
 */
public final class PhoneNumber {

    /** 받아들이는 표기 — 02 는 국번 2자리 + 7~8자리, 그 밖은 국번 3자리 + 7~8자리이고 하이픈은 숫자 사이에 하나씩만 둘 수 있다. */
    public static final String PATTERN = "^(?:02(?:-?\\d){7,8}|0(?!2)\\d{2}(?:-?\\d){7,8})$";

    private static final Pattern ACCEPTED = Pattern.compile(PATTERN);

    private PhoneNumber() {
    }

    /** 하이픈 표준형 — {@link #PATTERN} 밖이면 {@link IllegalArgumentException}. */
    public static String normalize(String raw) {
        if (raw == null || !ACCEPTED.matcher(raw).matches()) {
            throw new IllegalArgumentException("연락처 형식이 아닙니다");
        }
        String digits = digits(raw);
        int areaLength = digits.startsWith("02") ? 2 : 3;
        int lastStart = digits.length() - 4;
        return digits.substring(0, areaLength) + "-" + digits.substring(areaLength, lastStart) + "-"
                + digits.substring(lastStart);
    }

    /** 하이픈을 뺀 숫자 — 저장된 표기와 무관하게 같은 번호를 같은 값으로 만든다. */
    public static String digits(String phone) {
        return phone.replace("-", "");
    }
}

package src.backend.global.common;

import java.util.Locale;

/**
 * 문자열을 응답 DTO 필드용 소문자로 바꾸는 공용 변환기 — {@link
 * src.backend.global.common.converter.LowerCaseEnumConverter} 는 JPA {@code AttributeConverter} 라
 * DB 컬럼 변환 전용이고 enum 마다 중첩 {@code Db} 서브클래스가 필요해(그 자바독 참고) 응답 조립처럼
 * 인스턴스 없이 한 번 부르는 자리에는 맞지 않는다. 이쪽은 정적 메서드 하나로 같은 규칙(소문자 + {@link
 * Locale#ROOT})을 재사용한다({@code exception/query}·{@code admin/query} 두 서비스가 각자 같은 로직을
 * 복제해 온 것을 여기로 묶는다).
 */
public final class LowerCaseFormatter {

    private LowerCaseFormatter() {
    }

    /**
     * 문자열을 소문자로 바꾼다. {@code null} 은 그대로 통과시킨다.
     *
     * <p>{@link Locale#ROOT} 를 명시한다 — JVM 기본 로케일이 터키어·아제르바이잔어({@code tr}·{@code
     * az})면 {@code toLowerCase()} 가 {@code I} 를 점 없는 {@code ı}(U+0131)로 바꿔 프런트가 기대하는
     * 값과 달라진다({@link src.backend.global.common.converter.LowerCaseEnumConverter} 와 같은 근거).
     */
    public static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}

package src.backend.academy.command;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 관계자 비밀번호 초기화가 발급하는 임시 비밀번호를 만든다(ACAD-06 · API_SPEC §6.7).
 *
 * <p><b>{@link SecureRandom} 이다.</b> {@code java.util.Random} 은 시드에서 수열 전체가 결정되므로,
 * 임시 비밀번호 몇 개를 관측한 사람이 이후 발급분을 계산할 수 있다 — 그 순간 이 값은 비밀이 아니다.
 *
 * <p>글자를 계정 정보(식별자·이름·생성 시각)에서 유도하지 않는 것이 핵심이다. 유도하면 같은 계정을
 * 두 번 초기화했을 때 같은 값이 나오고, 한 번이라도 임시 비밀번호를 본 사람이 이후 전부를 안다.
 *
 * <p>{@code AcademyCodeGenerator} 의 알파벳을 재사용하지 않는다 — 그쪽은 사람이 <b>구두로 전하고 손으로
 * 입력</b>하는 학원 코드라 혼동 문자를 뺀 32자이고, 이쪽은 추측 난도가 목적이라 뺄 이유가 없다.
 * 한 상수를 공유하면 한쪽 정책을 바꿀 때 다른 쪽이 조용히 따라간다.
 */
@Component
public class TemporaryPasswordGenerator {

    /**
     * 임시 비밀번호 길이 — 발급 즉시 관리자가 사람에게 전달하는 값이라 지나치게 길면 오입력이 늘고,
     * 짧으면 추측 대상이 된다. 아래 알파벳 62자에서 16자를 뽑으면 경우의 수가 62^16 이다.
     */
    private static final int LENGTH = 16;

    /** 대문자·소문자·숫자 62자 — 특수문자를 빼는 이유는 전달 과정(문자·구두)에서 오입력을 만들기 때문이다. */
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final SecureRandom random = new SecureRandom();

    /**
     * 새 임시 비밀번호 원문을 만든다 — <b>호출부가 해시로만 저장하고 원문은 응답에 1회 싣는다.</b>
     * 이 값을 로그에 남기면 저장을 해시로 한 의미가 사라진다.
     */
    public String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}

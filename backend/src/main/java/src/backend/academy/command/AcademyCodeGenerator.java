package src.backend.academy.command;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.repository.AcademyRepository;

/**
 * 학원 코드를 서버가 만든다(API_SPEC §6.2 · Ruling 140) — 관리자 입력 경로는 부재하다.
 *
 * <p>혼동 문자 {@code I}·{@code O}·{@code 0}·{@code 1} 을 뺀 대문자 영숫자 <b>32자</b>에서 8자를 뽑는다.
 * 학원 코드는 가입 화면에서 사람이 구두로 전하고 손으로 입력하는 값이라, {@code O}/{@code 0} 이 섞이면
 * 오입력이 곧 "학원을 못 찾음" 으로 나타난다.
 *
 * <p>난수원이 {@link SecureRandom} 인 이유는 코드가 추측 가능하면 미공개 학원의 코드를 순서대로 훑을 수
 * 있기 때문이다. 순번 방식을 버린 이유도 같다 — 순번은 등록 학원 수를 외부에 그대로 노출한다.
 */
@Component
@RequiredArgsConstructor
public class AcademyCodeGenerator {

    /** 혼동 문자 4개({@code I} · {@code O} · {@code 0} · {@code 1})를 뺀 대문자 영숫자 32자. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 코드 길이 — 32^8 ≈ 1.1조로 {@code academy.code varchar(32)} 안에 들어간다. */
    static final int CODE_LENGTH = 8;

    /**
     * 충돌 시 재생성 횟수 상한.
     *
     * <p>상한 없이 도는 대신 예외로 드러낸다 — 무한 루프로 감추면 코드 공간이 고갈되어 가는 상황이
     * 응답 지연으로만 나타나고, 그것을 코드 공간 문제로 읽을 근거가 어디에도 남지 않는다.
     */
    static final int MAX_ATTEMPTS = 5;

    private final AcademyRepository academyRepository;

    private final SecureRandom random = new SecureRandom();

    /**
     * 아직 쓰이지 않은 학원 코드를 만든다. 충돌은 여기서 흡수하므로 클라이언트에 중복 에러가 노출되지
     * 않는다(API_SPEC §6.2).
     *
     * @throws IllegalStateException 상한까지 전부 충돌한 경우 — 사용자 입력 오류가 아니라 코드 공간
     *                               고갈이나 난수원 이상이라 {@code BusinessException} 이 아니다
     */
    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!academyRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "학원 코드 생성이 " + MAX_ATTEMPTS + "회 연속 충돌했다 — 코드 공간 고갈이나 난수원 이상을 의심한다");
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}

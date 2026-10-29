package src.backend.student.command;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.student.repository.LinkCodeRepository;

/**
 * 자녀 연결 인증 코드(S-05, §3.3)를 뽑는다 — 학원 안에서 지금 쓸 수 있는 코드와 겹치지 않는 6자리. {@link ChildLinkCommandService#issueCode} 의
 * 트랜잭션 안에서 불리는 평범한 빈이라 자기 트랜잭션을 열지 않는다.
 */
@Component
@RequiredArgsConstructor
class LinkCodeGenerator {

    /** 코드 자릿수 — {@code link_code.code} 가 {@code varchar(10)} 이고 학생이 불러 주는 값이다. */
    private static final int CODE_LENGTH = 6;

    /** 발급 때 학원 안 살아 있는 코드와 겹치면 다시 뽑는 최대 횟수(BR-215). */
    private static final int MAX_CODE_DRAWS = 10;

    private final LinkCodeRepository linkCodeRepository;

    /**
     * 코드가 추측 가능하면 6자리를 훑어 남의 자녀를 가져갈 수 있으므로 {@link SecureRandom} 이다 —
     * {@code java.util.Random} 은 시드에서 수열 전체가 결정된다({@code TemporaryPasswordGenerator} 와 같은 근거).
     */
    private final SecureRandom random = new SecureRandom();

    /** 그 학원 안에서 지금 쓸 수 있는 코드와 겹치지 않는 6자리 — 겹치면 다시 뽑는다. */
    String unusedCode(Long academyId, OffsetDateTime now) {
        for (int draw = 0; draw < MAX_CODE_DRAWS; draw++) {
            String code = generateCode();
            boolean taken = linkCodeRepository.findByCodeForAcademy(code, academyId).stream()
                    .anyMatch(existing -> existing.isUsable(now));
            if (!taken) {
                return code;
            }
        }
        // 학원당 살아 있는 코드는 학생 수 이하라 10번 연속 겹칠 수 없다 — 도달하면 코드 공간이 아닌 다른 결함이다.
        throw new IllegalStateException("연결 코드를 " + MAX_CODE_DRAWS + "번 뽑아도 학원 안에서 겹친다");
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }
}

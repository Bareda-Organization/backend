package src.backend.academy.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import src.backend.academy.repository.AcademyRepository;

/**
 * 학원 코드 생성기(Ruling 140, API_SPEC §6.2) — 형식·유일성·상한 셋을 각각 고정한다.
 *
 * <p>저장소를 가짜로 세우는 이유는 "충돌이 계속되는 상태" 를 실제 DB 로는 만들 수 없기 때문이다 —
 * 32^8 가지에서 같은 값을 연달아 뽑게 만들 수단이 부재하다. 상한 초과는 코드 공간 고갈이 실제로
 * 일어났을 때만 관측되는 경로라, 여기서 세우지 않으면 영영 실행되지 않는 분기가 된다.
 */
class AcademyCodeGeneratorTest {

    /** 혼동 문자 {@code I}·{@code O}·{@code 0}·{@code 1} 이 빠진 32자. */
    private static final String CODE_PATTERN = "^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$";

    /**
     * 알파벳이 실제로 32자이고 혼동 문자를 담지 않는지 <b>정본에서 직접 센다</b>.
     *
     * <p>길이만 세면 {@code I} 를 넣고 다른 글자 하나를 빼도 32가 유지된다 — 개수와 구성원을 함께 봐야
     * "혼동 문자를 뺐다" 가 검증된다.
     */
    @Test
    void 코드_알파벳은_혼동_문자를_뺀_32자다() {
        assertThat(AcademyCodeGenerator.ALPHABET).hasSize(32);
        assertThat(AcademyCodeGenerator.ALPHABET)
                .as("O/0 · I/1 이 섞이면 구두 전달과 손 입력에서 오입력이 '학원을 못 찾음' 으로 나타난다")
                .doesNotContain("I").doesNotContain("O").doesNotContain("0").doesNotContain("1");
        assertThat(AcademyCodeGenerator.ALPHABET.chars().distinct().count())
                .as("중복 글자가 있으면 실제 코드 공간은 32^8 보다 작다")
                .isEqualTo(32);
    }

    /** 충돌이 없으면 첫 후보를 그대로 쓰고, 형식은 항상 같다. */
    @Test
    void 생성한_코드는_대문자_영숫자_8자다() {
        AcademyRepository repository = mock(AcademyRepository.class);
        given(repository.existsByCode(anyString())).willReturn(false);

        assertThat(new AcademyCodeGenerator(repository).generate()).matches(CODE_PATTERN);
    }

    /**
     * 이미 쓰이는 코드를 만나면 다시 뽑는다 — 충돌을 서버가 흡수하므로 클라이언트에 중복 에러가
     * 노출되지 않는다(§6.2).
     */
    @Test
    void 이미_쓰이는_코드를_만나면_다시_뽑는다() {
        AcademyRepository repository = mock(AcademyRepository.class);
        given(repository.existsByCode(anyString())).willReturn(true, true, false);

        assertThat(new AcademyCodeGenerator(repository).generate()).matches(CODE_PATTERN);
    }

    /**
     * 상한까지 전부 충돌하면 예외로 드러난다.
     *
     * <p>무한 루프로 감추면 코드 공간이 고갈되어 가는 상황이 <b>응답 지연으로만</b> 나타나고, 그것을
     * 코드 공간 문제로 읽을 근거가 어디에도 남지 않는다. 상한값은 상수를 그대로 읽어, 값이 바뀌어도
     * 이 테스트가 따라간다.
     */
    @Test
    void 코드_충돌이_상한까지_반복되면_예외로_드러난다() {
        AcademyRepository repository = mock(AcademyRepository.class);
        given(repository.existsByCode(anyString())).willReturn(true);

        assertThatThrownBy(() -> new AcademyCodeGenerator(repository).generate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(AcademyCodeGenerator.MAX_ATTEMPTS));
    }

    /**
     * 난수라서 연달아 뽑아도 같은 값이 나오지 않는다.
     *
     * <p>순번이나 이름 기반 유도라면 이 단언이 깨진다 — 순번은 등록 학원 수를 외부에 그대로 노출하고,
     * 이름 기반은 같은 이름의 분원이 같은 코드를 갖게 되어 UNIQUE 에 걸린다. 100회는 우연히 겹칠
     * 확률(32^8 중 중복)이 무시할 만한 규모다.
     */
    @Test
    void 연달아_뽑은_코드는_서로_다르다() {
        AcademyRepository repository = mock(AcademyRepository.class);
        given(repository.existsByCode(anyString())).willReturn(false);
        AcademyCodeGenerator generator = new AcademyCodeGenerator(repository);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(100);
    }
}

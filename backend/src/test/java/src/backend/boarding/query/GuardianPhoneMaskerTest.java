package src.backend.boarding.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 보호자 연락처 마스킹(API_SPEC §1.12 · §4.2, BR-034) — 저장 형식이 하이픈 3분할이 아니어도 원문이
 * 매니저 앱으로 나가지 않아야 한다. 가입·관계자 수정 경로가 하이픈 없는 번호를 그대로 저장한다.
 */
class GuardianPhoneMaskerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "010-2345-8814, 010-2XXX-8814",
            "01023458814, 010-2XXX-8814",
            "010 2345 8814, 010-2XXX-8814",
            "02-123-4567, 02-1XX-4567",
            "0311234567, 031-1XX-4567",
            "12345, XXX-XXXX-XXXX",
            "+82-10-2345-8814, XXX-XXXX-XXXX"
    })
    @DisplayName("BR-034 — 형식과 무관하게 가운데 자리를 가리고, 해석할 수 없으면 전부 가린다")
    void 형식과_무관하게_가운데_자리를_가린다(String phone, String expected) {
        assertThat(GuardianPhoneMasker.mask(phone)).isEqualTo(expected);
    }
}

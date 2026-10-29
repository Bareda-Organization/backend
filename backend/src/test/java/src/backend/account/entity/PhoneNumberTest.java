package src.backend.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** 연락처 규칙(API_SPEC §2.2) — 하이픈 있는 표기와 없는 표기를 받아 하이픈 표준형 하나로 바꾼다. */
class PhoneNumberTest {

    private static final Pattern ACCEPTED = Pattern.compile(PhoneNumber.PATTERN);

    @Test
    void 휴대전화는_3_4_4_표준형이_된다() {
        assertThat(PhoneNumber.normalize("01012345678")).isEqualTo("010-1234-5678");
        assertThat(PhoneNumber.normalize("010-1234-5678")).isEqualTo("010-1234-5678");
        assertThat(PhoneNumber.normalize("010-12345678")).isEqualTo("010-1234-5678");
        assertThat(PhoneNumber.normalize("0101234567")).isEqualTo("010-123-4567");
    }

    @Test
    void 지역번호_02_는_두_자리_국번이_된다() {
        assertThat(PhoneNumber.normalize("0212345678")).isEqualTo("02-1234-5678");
        assertThat(PhoneNumber.normalize("021234567")).isEqualTo("02-123-4567");
    }

    @Test
    void 익명화에_쓰는_번호도_표준형이다() {
        assertThat(PhoneNumber.normalize("000-0000-0000")).isEqualTo("000-0000-0000");
    }

    @Test
    void 허용_패턴은_형식_밖_입력을_거절한다() {
        for (String ok : new String[] {"01012345678", "010-1234-5678", "0212345678"}) {
            assertThat(ACCEPTED.matcher(ok).matches()).as(ok).isTrue();
        }
        for (String bad : new String[] {"abc", "010-12", "123456789012", "010--1234-5678", "-01012345678", "1012345678",
                "010 1234 5678", ""}) {
            assertThat(ACCEPTED.matcher(bad).matches()).as(bad).isFalse();
        }
    }

    @Test
    void 형식_밖_입력의_정규화는_예외이다() {
        assertThatThrownBy(() -> PhoneNumber.normalize("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 하이픈을_뺀_숫자는_저장_표기가_달라도_같다() {
        assertThat(PhoneNumber.digits("010-1234-5678")).isEqualTo("01012345678");
        assertThat(PhoneNumber.digits("01012345678")).isEqualTo("01012345678");
    }
}

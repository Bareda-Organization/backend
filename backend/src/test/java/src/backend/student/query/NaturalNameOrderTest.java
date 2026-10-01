package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 이름 자연 정렬의 경계(R46 FUBE · B1 #27) — 숫자 크기 · 앞 0 · 긴 숫자 · 짧은 접두어 · 숫자 없는 이름. */
class NaturalNameOrderTest {

    @Test
    void 숫자_덩어리는_크기로_비교하고_앞_0_만_다른_이름도_순서가_정해진다() {
        List<String> names = new ArrayList<>(List.of("학생100", "학생10", "학생2", "학생02", "학생1", "학생01", "학생",
                "가나", "학생9999999999999999999999", "학생10000000000000000000000"));
        Collections.shuffle(names);

        names.sort(NaturalNameOrder.INSTANCE);

        assertThat(names).containsExactly("가나", "학생", "학생01", "학생1", "학생02", "학생2", "학생10", "학생100",
                "학생9999999999999999999999", "학생10000000000000000000000");
    }

    @Test
    void 숫자_사이의_글자도_차례대로_비교한다() {
        List<String> names = new ArrayList<>(List.of("3반 10번", "3반 2번", "10반 1번", "2반 5번"));

        names.sort(NaturalNameOrder.INSTANCE);

        assertThat(names).containsExactly("2반 5번", "3반 2번", "3반 10번", "10반 1번");
    }
}

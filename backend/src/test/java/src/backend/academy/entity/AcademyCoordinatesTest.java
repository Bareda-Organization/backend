package src.backend.academy.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link Academy#assignCoordinates}·{@link Academy#hasCoordinates} 의 순수 Java 규칙을 확인한다 —
 * DB CHECK({@code ck_academy_coords_paired})가 막는 것과 같은 결함을 저장 이전에 걸러내는지가
 * 대상이라 컨테이너를 띄우지 않는다. DB 레벨 CHECK·매핑은 {@link AcademyEntitySchemaValidationTest}.
 */
class AcademyCoordinatesTest {

    private Academy 학원() {
        return Academy.register("A001", "테스트학원", "서울", "테스트로 1", "010-0000-0000");
    }

    @Test
    void 좌표를_전혀_주지_않으면_hasCoordinates_가_거짓이다() {
        Academy academy = 학원();

        assertThat(academy.hasCoordinates()).isFalse();
    }

    @Test
    void 좌표가_모두_있어야_hasCoordinates_가_참이다() {
        Academy academy = 학원();

        academy.assignCoordinates(new BigDecimal("37.497942"), new BigDecimal("127.027621"));

        assertThat(academy.hasCoordinates()).isTrue();
    }

    @Test
    void 위도만_주면_거부된다() {
        Academy academy = 학원();

        assertThatThrownBy(() -> academy.assignCoordinates(new BigDecimal("37.497942"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(academy.hasCoordinates()).as("거부되면 기존 상태(둘 다 없음)가 유지돼야 한다").isFalse();
    }

    @Test
    void 경도만_주면_거부된다() {
        Academy academy = 학원();

        assertThatThrownBy(() -> academy.assignCoordinates(null, new BigDecimal("127.027621")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(academy.hasCoordinates()).as("거부되면 기존 상태(둘 다 없음)가 유지돼야 한다").isFalse();
    }

    /**
     * {@code assignCoordinates} 가 한쪽만 있는 상태를 이미 막으므로, 그 상태에서도
     * {@code hasCoordinates()} 가 실제로 "둘 다" 를 검사하는지는 리플렉션으로 필드를 직접
     * 건드려야 확인할 수 있다 — {@code &&} 를 {@code ||} 로 바꿔도 공개 API 만으로는 이 차이가
     * 드러나지 않는다.
     */
    @Test
    void 필드가_직접_한쪽만_채워진_상태에서도_hasCoordinates_는_거짓이다() {
        Academy academy = 학원();
        ReflectionTestUtils.setField(academy, "lat", new BigDecimal("37.497942"));

        assertThat(academy.hasCoordinates()).isFalse();
    }
}

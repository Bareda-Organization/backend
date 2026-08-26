package src.backend.bus.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@link Bus} 의 정원 규칙 — <b>HTTP 표면으로는 검증할 수 없는 축</b>을 도메인 단위로 고정한다.
 *
 * <p>이 클래스가 존재하는 이유가 게이트 리뷰 Important 5 다. {@code API_SPEC §5.12} 의 요청 필드에
 * 기사·동승자 수가 부재해 <b>비기본(1·1 이 아닌) 승무 인원으로 차량을 만드는 API 경로가 없고</b>,
 * 그래서 "정원을 바꿔도 승무 인원을 이어받는다" 와 "기본값으로 되돌린다" 가 HTTP 왕복에서 항상 같은
 * 값을 낸다 — 컨트롤러 테스트로는 둘을 구별하는 단언을 쓸 수 없다.
 *
 * <p><b>이 테스트의 값어치는 "지금 맞다" 가 아니라 "학원별 승무 인원이 생기는 Phase 에서 이 로직이
 * 조용히 죽지 않게" 다.</b> 그날 요청 필드가 열리면 기본값 재설정 구현은 관계자가 적어 넣은 승무
 * 인원을 정원 수정 한 번으로 1·1 로 되돌리고, 학생 정원이 그만큼 부풀어 Phase 7 확정 배치가 태울 수
 * 없는 인원을 편성한다.
 */
class BusTest {

    private static final long ACADEMY_ID = 1L;

    /** 기본값(1·1)과 겹치지 않는 승무 인원 — 겹치면 이어받기와 기본값 재설정이 같은 값을 내 구별되지 않는다. */
    private static final int NON_DEFAULT_DRIVERS = 2;

    private static final int NON_DEFAULT_ESCORTS = 3;

    @Test
    void 등록하면_학생_정원이_정원에서_승무_인원을_뺀_값이_된다() {
        Bus bus = 비기본_승무_인원_차량(20);

        assertThat(bus.getStudentCapacity()).isEqualTo(20 - NON_DEFAULT_DRIVERS - NON_DEFAULT_ESCORTS);
    }

    /**
     * 정원만 고치면 <b>승무 인원은 그대로 남고</b> 학생 정원만 다시 계산된다.
     *
     * <p>세 단언이 함께 있어야 한다 — 학생 정원만 보면 승무 인원을 기본값으로 되돌린 구현이
     * "25 대신 28" 이라는 다른 값을 내며 걸리지만, <b>왜</b> 틀렸는지는 승무 인원 단언에서만 드러난다.
     */
    @Test
    void 정원을_고쳐도_기사와_동승자_수는_그대로_남고_학생_정원만_다시_계산된다() {
        Bus bus = 비기본_승무_인원_차량(20);

        bus.update(null, null, 30, null);

        assertThat(bus.getDriverCount()).as("정원 수정이 기사 수를 기본값으로 되돌리면 안 된다")
                .isEqualTo(NON_DEFAULT_DRIVERS);
        assertThat(bus.getEscortCount()).as("정원 수정이 동승자 수를 기본값으로 되돌리면 안 된다")
                .isEqualTo(NON_DEFAULT_ESCORTS);
        assertThat(bus.getStudentCapacity()).as("학생 정원은 바뀐 정원에서 그 승무 인원을 뺀 값이다")
                .isEqualTo(30 - NON_DEFAULT_DRIVERS - NON_DEFAULT_ESCORTS);
    }

    /** 정원을 보내지 않은 수정은 정원 계열 값을 하나도 건드리지 않는다 — PATCH 는 보낸 필드만 반영한다(§5.12). */
    @Test
    void 정원을_보내지_않은_수정은_정원_계열_값을_건드리지_않는다() {
        Bus bus = 비기본_승무_인원_차량(20);

        bus.update("새호차", null, null, false);

        assertThat(bus.getBusNo()).isEqualTo("새호차");
        assertThat(bus.isOperable()).isFalse();
        assertThat(bus.getCapacity()).isEqualTo(20);
        assertThat(bus.getDriverCount()).isEqualTo(NON_DEFAULT_DRIVERS);
        assertThat(bus.getStudentCapacity()).isEqualTo(20 - NON_DEFAULT_DRIVERS - NON_DEFAULT_ESCORTS);
    }

    /**
     * 승무 인원이 비기본일 때도 정원 하한은 <b>그 승무 인원</b>을 기준으로 걸린다.
     *
     * <p>하한을 기본값(2명)으로 굳힌 구현은 정원 5(승무 5)를 통과시키고, 학생 정원 0인 차량이 저장된다.
     * 컨트롤러 테스트의 경계값(정원 2)은 기본 승무 인원에서만 성립해 이 형태를 보지 못한다.
     */
    @Test
    void 정원이_비기본_승무_인원_이하가_되는_수정은_차단된다() {
        Bus bus = 비기본_승무_인원_차량(20);

        assertThatThrownBy(() -> bus.update(null, null, NON_DEFAULT_DRIVERS + NON_DEFAULT_ESCORTS, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private Bus 비기본_승무_인원_차량(int capacity) {
        return Bus.register(ACADEMY_ID, "1호차", "01가0001",
                new BusSeating(capacity, NON_DEFAULT_DRIVERS, NON_DEFAULT_ESCORTS));
    }
}

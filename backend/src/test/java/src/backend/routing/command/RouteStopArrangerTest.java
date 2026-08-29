package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 정차 목록 검증의 두 거부 사유를 <b>갈라서</b> 고정한다(RTE-01).
 *
 * <p><b>이 클래스가 있는 이유가 음성 대조의 산물이다.</b> 두 가드를 HTTP 왕복으로만 검사하면
 * 중복 가드를 통째로 지워도 초록이다 — 중복이 있으면 "학원에 실재하는 개수" 가 요청 개수보다
 * 반드시 작아져 <b>뒤쪽 개수 대조가 먼저 잡아 버리기</b> 때문이다. 두 가드가 같은
 * {@code 422 VALIDATION_FAILED} 를 내고 사유 문구는 응답 봉투에 실리지 않으므로
 * ({@code GlobalExceptionHandler} 가 {@link ErrorCode} 의 기본 문구를 쓴다), HTTP 층에서는 둘을
 * 가릴 수단이 부재하다.
 *
 * <p>그래서 서비스 층에서 <b>예외 문구</b>를 직접 본다. 문구를 갈라 두는 값어치는 로그다 — 관계자가
 * 같은 자리를 두 번 보낸 것과 쓸 수 없는 승하차지를 보낸 것은 고칠 방법이 정반대인데, 한 문구로
 * 합치면 어느 쪽인지 알 수단이 사라진다.
 */
@SpringBootTest
@Transactional
class RouteStopArrangerTest {

    /** 시드의 학원 A 와 그 학원의 승하차지 2곳. */
    private static final long ACADEMY_A_ID = 1L;

    /** 시드 학원 B 의 승하차지 — 학원 A 로 물으면 "빠짐" 으로 돌아와야 한다. */
    private static final long STOP_OF_B = 5L;

    @Autowired
    private RouteStopArranger routeStopArranger;

    /** 같은 승하차지가 두 번 담긴 목록은 <b>중복</b> 사유로 거부된다 — 개수 대조가 아니라 중복 가드가 잡는다. */
    @Test
    void 같은_승하차지를_두_번_담으면_중복_사유로_거부된다() {
        assertThatThrownBy(() -> routeStopArranger.resolve(ACADEMY_A_ID, List.of(1L, 2L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("두 번")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 이 학원에 없는 승하차지는 <b>다른</b> 사유로 거부된다 — 없는 것과 남의 학원 것은 같은 사유다. */
    @Test
    void 이_학원에_없는_승하차지는_편성_불가_사유로_거부된다() {
        assertThatThrownBy(() -> routeStopArranger.resolve(ACADEMY_A_ID, List.of(1L, STOP_OF_B)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("편성할 수 없는")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 빈 목록은 거부가 아니다 — 차량·요일·방향 칸을 먼저 잡아 두는 조작이 실재한다. */
    @Test
    void 빈_목록은_거부되지_않는다() {
        assertThat(routeStopArranger.resolve(ACADEMY_A_ID, List.of())).isEmpty();
    }
}

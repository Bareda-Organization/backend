package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 목록 조회 공통 파라미터(API_SPEC §1.8) — 기본값 · 상한 · 정렬 허용 목록. */
class PageParamsTest {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "created_at", "createdAt");

    private static final Sort FALLBACK = Sort.by(Sort.Direction.ASC, "name");

    /**
     * §1.8 이 정한 <b>값 자체</b>를 사양에서 손으로 옮겨 고정한다 — 기본 20 · 최대 100 · 0 기점.
     *
     * <p>아래 단언들은 상수를 읽어 쓴다. 이름이 바뀌어도 따라가라는 뜻인데, 그 대가로 <b>값이 바뀌면
     * 함께 따라가서</b> 상한을 1000 으로 넓혀도 전부 통과한다 — 실측으로 확인한 사실이다(변형 M4 가
     * 살아남았다). 그래서 값을 사양에서 직접 옮긴 축을 따로 둔다. 두 축의 출처가 달라야
     * 한쪽의 실수를 다른 쪽이 잡는다(Ruling 103 과 같은 이유).
     */
    @Test
    void 페이지_기본값과_상한은_사양이_정한_0_20_100_이다() {
        assertThat(PageParams.DEFAULT_PAGE).isZero();
        assertThat(PageParams.DEFAULT_SIZE).isEqualTo(20);
        assertThat(PageParams.MAX_SIZE)
                .as("상한이 넓어지면 size=1000 한 번이 전량 조회에 가까워진다")
                .isEqualTo(100);
    }

    /** 값을 주지 않으면 §1.8 기본값(page 0 · size 20)이다. */
    @Test
    void 값을_주지_않으면_page_0_size_20_이다() {
        PageParams params = PageParams.of(null, null);

        assertThat(params.page()).isZero();
        assertThat(params.size()).isEqualTo(PageParams.DEFAULT_SIZE);
    }

    /**
     * 상한값까지는 받고 그 다음 값은 거부한다 — <b>양쪽 경계를 다</b> 본다.
     *
     * <p>거부 쪽만 보면 상한을 1 로 낮춘 구현도 통과하고, 통과 쪽만 보면 상한이 아예 없는 구현도
     * 통과한다. 절삭이 아니라 거부인 근거는 {@link PageParams} 클래스 주석에 있다.
     */
    @Test
    void size_는_상한값까지_받고_그_다음_값은_422_로_거부한다() {
        assertThatCode(() -> PageParams.of(0, PageParams.MAX_SIZE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> PageParams.of(0, PageParams.MAX_SIZE + 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 상한을 넘긴 요청이 100 으로 <b>잘려서 통과하지 않는다</b> — 절삭이면 클라이언트의 페이지 계산이 어긋난다. */
    @Test
    void 상한을_넘긴_size_는_100_으로_절삭되지_않는다() {
        assertThatThrownBy(() -> PageParams.of(0, 100_000))
                .as("절삭하면 size=100000 요청이 조용히 100 건만 받고, 클라이언트는 나머지를 건너뛴 줄 모른다")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void size_0_과_음수_page_는_거부한다() {
        assertThatThrownBy(() -> PageParams.of(0, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PageParams.of(-1, 20)).isInstanceOf(BusinessException.class);
    }

    /** {@code sort} 를 주지 않으면 엔드포인트 기본 정렬이다. */
    @Test
    void sort_가_없으면_기본_정렬을_쓴다() {
        assertThat(SortParam.parse(null, SORTABLE, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortParam.parse("  ", SORTABLE, FALLBACK)).isEqualTo(FALLBACK);
    }

    /** API 이름은 엔티티 속성으로 바뀐다 — 둘이 같으리라는 보장이 부재하다({@code created_at} ↔ {@code createdAt}). */
    @Test
    void sort_는_API_필드명을_엔티티_속성으로_옮긴다() {
        assertThat(SortParam.parse("created_at:desc", SORTABLE, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(SortParam.parse("name", SORTABLE, FALLBACK))
                .as("방향을 생략하면 오름차순이다")
                .isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
    }

    /**
     * 허용 목록 밖 필드와 알 수 없는 방향은 둘 다 {@code 422} 다.
     *
     * <p>필드를 그대로 넘기면 Spring Data 가 {@code PropertyReferenceException} 을 던져 {@code 500} 이
     * 되고, 방향 오타를 조용히 오름차순으로 삼으면 사용자가 요청한 것과 다른 순서를 돌려주면서
     * 아무 신호도 내지 않는다.
     */
    @Test
    void 허용_목록_밖_필드와_알_수_없는_방향은_422_다() {
        assertThatThrownBy(() -> SortParam.parse("password_hash:asc", SORTABLE, FALLBACK))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> SortParam.parse("name:sideways", SORTABLE, FALLBACK))
                .isInstanceOf(BusinessException.class);
    }
}

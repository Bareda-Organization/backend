package src.backend.global.request;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 목록 조회의 페이지 위치·크기(API_SPEC §1.8) — Phase 3~14 의 모든 목록 엔드포인트가 공유한다.
 *
 * <p>모듈 안이 아니라 {@code global} 에 둔 이유는 첫 소비자({@code GET /admin/academies}) 옆에 두면
 * 다음 모듈이 같은 규약을 복제하고, 그 순간 상한이 엔드포인트마다 갈리기 때문이다.
 *
 * <p><b>상한을 넘긴 {@code size} 는 잘라 주지 않고 거부한다.</b> 100 으로 조용히 절삭하면
 * {@code size=200} 으로 페이지를 넘기는 클라이언트가 자기 계산(200건씩 건너뛰기)과 실제 응답(100건)이
 * 어긋난 채로 <b>행을 건너뛴다</b> — 에러 없이 데이터가 빠지는 형태라 아무도 알아채지 못한다.
 * 거부는 {@code 422 VALIDATION_FAILED}(§1.11 "형식 위반")다.
 */
public record PageParams(int page, int size) {

    /** 0 기점(API_SPEC §1.8). */
    public static final int DEFAULT_PAGE = 0;

    /** 값을 주지 않았을 때의 페이지 크기(API_SPEC §1.8). */
    public static final int DEFAULT_SIZE = 20;

    /** 한 페이지 최대 크기(API_SPEC §1.8) — 없으면 {@code size=100000} 한 번이 전량 조회가 된다. */
    public static final int MAX_SIZE = 100;

    /**
     * 요일표처럼 한 화면이 전량을 한 번에 그리는 목록({@code GET /staff/routes} · {@code GET /staff/schedules})의 한 페이지 최대
     * 크기(API_SPEC §1.8 의 100 예외, Ruling 818). 학원당 편성은 차량 × 14 가 상한이라 500 이면 한 번에 담긴다.
     */
    public static final int LARGE_MAX_SIZE = 500;

    /**
     * 페이징이 없는 목록(§5.16 · §5.20 · §6.11)이 한 번에 돌려주는 최대 행 수(BR-228) — 최근 것부터 자른다. 이 목록들은
     * 무기한 보존 테이블이라 상한이 없으면 호출 한 번이 누적 전량을 읽는다.
     */
    public static final int UNPAGED_LIST_MAX = 200;

    public PageParams {
        if (page < DEFAULT_PAGE || size < 1 || size > LARGE_MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 주지 않은 파라미터를 §1.8 기본값으로 채운다 — 값이 있으면 그대로 검증 대상이 되고, {@code size} 상한은 {@link #MAX_SIZE} 다. */
    public static PageParams of(Integer page, Integer size) {
        return limitedTo(MAX_SIZE, page, size);
    }

    /** {@link #of} 와 같되 {@code size} 상한이 {@link #LARGE_MAX_SIZE} 인 목록 전용이다(Ruling 818). */
    public static PageParams ofLarge(Integer page, Integer size) {
        return limitedTo(LARGE_MAX_SIZE, page, size);
    }

    private static PageParams limitedTo(int maxSize, Integer page, Integer size) {
        PageParams params = new PageParams(page == null ? DEFAULT_PAGE : page, size == null ? DEFAULT_SIZE : size);
        if (params.size() > maxSize) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return params;
    }

    /** 정렬은 엔드포인트마다 허용 필드가 달라 밖에서 만들어 넘긴다({@link SortParam}). */
    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}

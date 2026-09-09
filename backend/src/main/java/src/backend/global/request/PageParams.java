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

    public PageParams {
        if (page < DEFAULT_PAGE || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 주지 않은 파라미터를 §1.8 기본값으로 채운다 — 값이 있으면 그대로 검증 대상이 된다. */
    public static PageParams of(Integer page, Integer size) {
        return new PageParams(page == null ? DEFAULT_PAGE : page, size == null ? DEFAULT_SIZE : size);
    }

    /** 정렬은 엔드포인트마다 허용 필드가 달라 밖에서 만들어 넘긴다({@link SortParam}). */
    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}

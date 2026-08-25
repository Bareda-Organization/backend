package src.backend.global.request;

import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 목록 조회의 정렬 파라미터 {@code {필드}:{asc|desc}}(API_SPEC §1.8)를 {@link Sort} 로 옮긴다. */
public final class SortParam {

    private static final String SEPARATOR = ":";
    private static final String ASCENDING = "asc";
    private static final String DESCENDING = "desc";

    private SortParam() {
    }

    /**
     * 허용 목록에 있는 필드만 받는다 — 목록에 없는 이름을 그대로 넘기면 Spring Data 가
     * {@code PropertyReferenceException} 을 던져 422 여야 할 입력이 500 이 되고, 그 예외 문구가
     * 엔티티의 필드 목록을 응답 로그에 실어 나른다.
     *
     * @param raw            요청이 준 원문. 비었으면 {@code fallback} 을 그대로 돌려준다
     * @param sortableFields API 가 노출하는 필드 이름 → 엔티티 속성 이름. 값을 갈라 둔 이유는 둘이
     *                       같으리라는 보장이 없어서다({@code created_at} ↔ {@code createdAt})
     * @param fallback       정렬을 지정하지 않았을 때 쓰는 엔드포인트별 기본값
     */
    public static Sort parse(String raw, Map<String, String> sortableFields, Sort fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String[] parts = raw.split(SEPARATOR, 2);
        String property = sortableFields.get(parts[0].trim());
        if (property == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return Sort.by(direction(parts), property);
    }

    /** 방향을 생략하면 오름차순이다 — 그 외 값은 오타이므로 조용히 오름차순으로 삼지 않고 거부한다. */
    private static Sort.Direction direction(String[] parts) {
        if (parts.length == 1) {
            return Sort.Direction.ASC;
        }
        String raw = parts[1].trim().toLowerCase(Locale.ROOT);
        if (ASCENDING.equals(raw)) {
            return Sort.Direction.ASC;
        }
        if (DESCENDING.equals(raw)) {
            return Sort.Direction.DESC;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}

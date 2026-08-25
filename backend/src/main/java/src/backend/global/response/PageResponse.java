package src.backend.global.response;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 목록 조회 공통 응답 봉투(API_SPEC §1.8) — {@code items[]} · {@code page} · {@code size} ·
 * {@code total_count} · {@code has_next}.
 *
 * <p>{@code has_next} 를 함께 싣는 이유는 클라이언트가 {@code total_count} 와 {@code size} 로
 * 마지막 페이지를 계산하지 않게 하기 위함이다 — 그 계산을 클라이언트마다 하면 경계(딱 나누어떨어지는
 * 건수)에서 한 페이지를 더 요청하거나 덜 요청하는 차이가 앱마다 갈린다.
 *
 * @param <T> 항목 응답 DTO 타입
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalCount, boolean hasNext) {

    /**
     * 조회 결과의 페이지 정보는 {@link Page} 에서 그대로 옮기고 항목만 바꿔 끼운다.
     *
     * <p>변환 함수가 아니라 <b>변환이 끝난 목록</b>을 받는다 — 항목 하나를 만드는 데 그 원소만으로는
     * 얻을 수 없는 부가 집계(학원별 관계자 수 등)가 필요한 경우가 있고, 그때 원소별 변환 함수를
     * 요구하면 호출부가 원소마다 질의를 하나씩 붙이게 된다.
     */
    public static <T> PageResponse<T> of(Page<?> source, List<T> items) {
        return new PageResponse<>(items, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.hasNext());
    }
}

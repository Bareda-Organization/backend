package src.backend.routing.dto;

/**
 * 승하차지 관리 목록 요청(API_SPEC §5.9 · §1.8) — 검색어와 페이지 위치를 묶는다. 정렬은 이름 오름차순(동명은 {@code stop_id})으로 고정이라
 * {@code sort} 가 없다.
 *
 * @param q 이름 또는 주소에 들어 있는 글자(대소문자 무시) — 비우면 전부
 */
public record StopListRequest(String q, Integer page, Integer size) {
}

package src.backend.notification.dto;

/**
 * 알림 로그 전수 조회 요청(API_SPEC §5.17) — {@code type}·{@code date}·{@code acked} 는 원문 그대로
 * 받아 {@code StaffNotificationQueryService} 가 {@code ApiValues} 로 옮긴다({@code
 * ExceptionReportQueryService} 와 같은 근거 — Jackson 이 아니라 이 지점에서 걸러야 형식 오류가
 * {@code 422} 를 유지한다). {@code type}·{@code date}·{@code acked}·{@code page}·{@code size} 는
 * 전부 한 단어라 {@code @ModelAttribute} 바인딩이 스네이크케이스 문제를 겪지 않는다({@code
 * StudentListRequest} 와 같은 근거).
 *
 * <p>{@code sort} 가 없다 — §5.17 은 요청 파라미터로 "페이징" 만 명시하고 정렬 필드를 별도로 열거하지
 * 않는다(§5.11 이 "이름 오름차순" 을 명시한 것과 다르다). 정렬은 ERD §5 인덱스({@code
 * notification_log(academy_id, sent_at desc)})가 가리키는 발송시각 내림차순으로 고정한다.
 */
public record StaffNotificationListRequest(String type, String date, String acked, Integer page, Integer size) {
}

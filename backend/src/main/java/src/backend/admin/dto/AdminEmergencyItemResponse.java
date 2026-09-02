package src.backend.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 메인 관리자 콘솔의 비상 알림 1건(목표 11) — {@link EmergencyStaffItemResponse}(T2 소유
 * {@code exception.dto})와 형태를 나란히 두지 않고 이 패키지에 독립 record 로 둔다. 이유는
 * {@code academy}(교차 학원 식별)·{@code staffAcked}·{@code elapsedSinceRaised} 세 필드가 학원
 * 관계자 화면에는 없는 관리자 전용 필드라, 상속·합성으로 묶으면 관계자 응답이 이 필드들을 실수로
 * 노출할 여지가 생기기 때문이다.
 *
 * <p>{@code staffAcked} 는 {@code emergency_alert.acked_at IS NOT NULL} 과 같다 — 확인자가 학원
 * 관계자든 다른 메인관리자든 구분하지 않는다({@link src.backend.exception.entity.EmergencyAlert#ack}
 * 가 "최초 확인자만" 기록해 애초에 구분할 데이터가 없다).
 *
 * <p>{@code elapsedSinceRaised} 는 DB 컬럼이 아니라 <b>조회 시점</b>에 {@code Clock} 으로 계산한
 * 파생값이다(목표 11 요구 — 저장하면 조회할 때마다 갱신 배치가 따로 필요해진다). 단위는 <b>초</b>다
 * (정본이 "경과 초" 로 요구, {@code API_SPEC.md:1923}). 취소된 신고는 더 흐르지 않도록
 * {@code canceledAt} 을, 확인된 신고는 {@code ackedAt} 을 종료 시점으로 우선한다 — 그렇지 않으면
 * 이미 끝난 신고의 경과 시간이 콘솔을 열어 둔 만큼 계속 늘어난다.
 *
 * <p>타입을 {@code Duration} 이 아니라 {@code Long}(초)으로 둔다 — 이 저장소에 {@code Duration} 을
 * 응답 필드로 노출한 전례가 없고({@code StudentBusPositionQueryService} 도 내부 계산에만 쓰고
 * 응답에는 원본 시각만 싣는다), 전역 {@code ObjectMapper} 에 {@code WRITE_DURATIONS_AS_TIMESTAMPS}
 * 설정이 없어 직렬화 형태가 이 태스크 밖의 전역 설정에 암묵적으로 기댄다.
 */
public record AdminEmergencyItemResponse(Long id, AcademyInfo academy, Long runId, String busNo, String type,
        String memo, BigDecimal lat, BigDecimal lng, Integer riderCount, OffsetDateTime occurredAt,
        OffsetDateTime receivedAt, boolean staffAcked, OffsetDateTime ackedAt, OffsetDateTime canceledAt,
        Long elapsedSinceRaised) {

    public record AcademyInfo(Long id, String name, String contact) {
    }
}

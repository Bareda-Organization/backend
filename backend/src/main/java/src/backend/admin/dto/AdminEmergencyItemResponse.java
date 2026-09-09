package src.backend.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 메인 관리자 콘솔의 비상 알림 1건(목표 11·13, API_SPEC §5.16 상속 + §6.11 고유 3개) — {@code
 * EmergencyStaffItemResponse}(T3 소유 {@code exception.dto})와 형태를 나란히 두지 않고 이 패키지에
 * 독립 record 로 둔다. 이유는 {@code academy}(교차 학원 식별)·{@code staffAcked}·{@code
 * elapsedSinceRaised} 세 필드가 학원 관계자 화면에는 없는 관리자 전용 필드라, 상속·합성으로 묶으면
 * 관계자 응답이 이 필드들을 실수로 노출할 여지가 생기기 때문이다. 같은 이유로 {@code raisedBy}·
 * {@code position}·{@code contacts}·{@code ackedBy} 의 중첩 record 도 이 파일 안에 독립으로 둔다.
 *
 * <p>§5.16 상속분(§6.11 이 "§5.16 항목 + 아래" 로 정의)의 판정 근거는 {@code
 * EmergencyStaffItemResponse} 자바독과 같다 — {@code raisedAt = received_at}(occurred_at 은
 * 조작 가능해 취소 창 판정에도 쓰이지 않는다), {@code contacts} 는 배치 인력 전원(중복 허용),
 * {@code ackedBy} 는 이름만.
 *
 * <p>{@code staffAcked} 는 {@code emergency_alert.acked_at IS NOT NULL} 과 같다 — 확인자가 학원
 * 관계자든 다른 메인관리자든 구분하지 않는다({@link src.backend.exception.entity.EmergencyAlert#ack}
 * 가 "최초 확인자만" 기록해 애초에 구분할 데이터가 없다).
 *
 * <p>{@code elapsedSinceRaised} 는 DB 컬럼이 아니라 <b>조회 시점</b>에 {@code Clock} 으로 계산한
 * 파생값이다(목표 11 요구 — 저장하면 조회할 때마다 갱신 배치가 따로 필요해진다). 단위는 <b>초</b>다
 * (정본이 "경과 초" 로 요구, {@code API_SPEC.md:1923}). 취소된 신고는 더 흐르지 않도록
 * {@code canceledAt} 을, 확인된 신고는 {@code ackedAt} 을 종료 시점으로 우선한다 — 그렇지 않으면
 * 이미 끝난 신고의 경과 시간이 콘솔을 열어 둔 만큼 계속 늘어난다. 이 계산식 자체는 Phase 13 T3
 * 소유가 아니다 — 건드리지 않는다.
 *
 * <p>타입을 {@code Duration} 이 아니라 {@code Long}(초)으로 둔다 — 이 저장소에 {@code Duration} 을
 * 응답 필드로 노출한 전례가 없고({@code StudentBusPositionQueryService} 도 내부 계산에만 쓰고
 * 응답에는 원본 시각만 싣는다), 전역 {@code ObjectMapper} 에 {@code WRITE_DURATIONS_AS_TIMESTAMPS}
 * 설정이 없어 직렬화 형태가 이 태스크 밖의 전역 설정에 암묵적으로 기댄다.
 */
public record AdminEmergencyItemResponse(Long emergencyId, AcademyInfo academy, String type, String memo,
        RaisedBy raisedBy, Long runId, String busNo, String direction, Position position, Integer riderCount,
        List<Contact> contacts, OffsetDateTime raisedAt, boolean staffAcked, OffsetDateTime ackedAt,
        OffsetDateTime canceledAt, AckedBy ackedBy, Long elapsedSinceRaised) {

    /** 신고를 낸 학원(§6.11 고유). */
    public record AcademyInfo(Long id, String name, String contact) {
    }

    /** 발신자(§5.16 {@code raised_by} 상속) — {@code role} 은 소문자. */
    public record RaisedBy(String name, String role, String phone) {
    }

    /** 발신 시점 위치(§5.16 {@code position} 상속) — {@code recordedAt} 근거는 Ruling 236. */
    public record Position(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt) {
    }

    /** 회차에 배치된 기사·동승자 연락처(§5.16 {@code contacts} 상속) — {@code role} 은 소문자. */
    public record Contact(String name, String role, String phone) {
    }

    /** 확인한 관계자(§5.16 {@code acked_by} 상속) — 확인 전이면 {@code null}. */
    public record AckedBy(String name) {
    }
}

package src.backend.exception.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 학원 관계자 화면의 비상 알림 1건(목표 8·10·13, API_SPEC §5.16) — 발신자·확인자 연락처는
 * {@code emergency_alert} 테이블 컬럼이 아니다(스키마에 연락처 칼럼이 없다) — 조회 시점에
 * {@code raisedBy}(manager.id)로 {@code Manager} 를 다시 읽어 채운다.
 *
 * <p>{@code raisedAt} 은 {@code emergency_alert.received_at} 이다 — {@code occurred_at}(클라이언트가
 * 신고했다고 주장하는 시각, 조작 가능)이 아니라 서버가 실제로 접수한 시각을 쓴다. {@link
 * src.backend.exception.command.EmergencyCommandService#assertWithinCancelWindow} 가 같은 이유로
 * {@code receivedAt} 을 취소 창 기준으로 삼는 것과 같은 원칙이다. {@code occurred_at} 은 정본
 * §5.16 응답에 없어 이 응답에도 싣지 않는다(Phase 13 목표 13 판정 ①).
 *
 * <p>{@code contacts} 는 그 회차에 배치된 기사·동승자 전원이다 — {@code raisedBy} 본인이 배치
 * 인력이어도 빼지 않는다(중복 허용). 비상 상황에서 관계자가 이 배열만 보고 바로 연락할 수 있어야
 * 하므로, 발신자와 겹친다는 이유로 목록에서 지우면 오히려 그 사람의 연락처를 다시 {@code raised_by}
 * 에서 찾아야 하는 수고가 생긴다(Phase 13 목표 13 판정 ②).
 *
 * <p>{@code ackedBy} 는 이름만 담는다 — 정본이 "확인한 관계자" 라고만 적고 하위 필드를 명시하지
 * 않는다(§5.16). 역할·연락처까지 필요하다는 정본 문면 근거가 없어 최소 형태로 둔다.
 */
public record EmergencyStaffItemResponse(Long emergencyId, String type, String memo, RaisedBy raisedBy, Long runId,
        String busNo, String direction, Position position, Integer riderCount, List<Contact> contacts,
        OffsetDateTime raisedAt, OffsetDateTime ackedAt, OffsetDateTime canceledAt, boolean acked, AckedBy ackedBy) {

    /** 발신자(§5.16 {@code raised_by}) — {@code role} 은 {@code driver}·{@code escort} 소문자. */
    public record RaisedBy(String name, String role, String phone) {
    }

    /**
     * 발신 시점 위치(§5.16 {@code position}) — {@code recordedAt} 은 위치 발신 장비가 찍은 시각
     * ({@code emergency_alert.position_recorded_at}, Ruling 236). 위치 캐시가 없어 첨부되지 않은
     * 발신은 {@code lat}·{@code lng}·{@code recordedAt} 이 전부 {@code null} 이다.
     */
    public record Position(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt) {
    }

    /** 회차에 배치된 기사·동승자 연락처(§5.16 {@code contacts}) — {@code role} 은 소문자. */
    public record Contact(String name, String role, String phone) {
    }

    /** 확인한 관계자(§5.16 {@code acked_by}) — 확인 전이면 {@code null}. */
    public record AckedBy(String name) {
    }
}

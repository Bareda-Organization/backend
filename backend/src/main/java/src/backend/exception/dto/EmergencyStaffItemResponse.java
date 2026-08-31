package src.backend.exception.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 학원 관계자 화면의 비상 알림 1건(목표 8·10) — {@code raisedByName}·{@code raisedByPhone} 은
 * {@code emergency_alert} 테이블 컬럼이 아니다(스키마에 연락처 칼럼이 없다, Phase 11 T4 소유
 * 마이그레이션을 건드리지 않는다는 전제) — 조회 시점에 {@code raisedBy}(manager.id)로
 * {@code Manager} 를 다시 읽어 채운다.
 */
public record EmergencyStaffItemResponse(Long id, Long runId, String busNo, String type, String memo,
        BigDecimal lat, BigDecimal lng, Integer riderCount, String raisedByName, String raisedByRole,
        String raisedByPhone, OffsetDateTime occurredAt, OffsetDateTime receivedAt, boolean acked,
        String ackedByName, OffsetDateTime ackedAt, OffsetDateTime canceledAt) {
}

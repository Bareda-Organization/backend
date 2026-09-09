package src.backend.routing.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 회차별 확정 노선 — 회차와 노선 버전 목록을 잇는 자리이자 "지금 유효한 버전"의 단일 지시자다
 * (ERD §3.3 · C-03 · RTE-02 · UF-X-05).
 *
 * <p>{@code run.id} 를 그대로 PK 로 쓰는 1:1 확장 테이블이라 {@code @GeneratedValue} 를 두지 않는다
 * — 값은 회차 확정 시점에 호출자가 직접 넣는다(엔티티 작성 규약 §4, {@code AcademySetting} 과 동일한
 * 패턴). {@code save()} 로 저장하면 식별자가 이미 채워져 있어 {@code persist} 가 아니라 {@code merge}
 * 경로를 탄다.
 *
 * <p>{@code currentVersionId} 는 {@code route_version.confirmed_route_id} 와 순환 FK 쌍이다
 * (`V1` 에서 {@code DEFERRABLE INITIALLY DEFERRED} 로 사후 추가). 어느 쪽도 엔티티 참조를 두지
 * 않고 {@code Long} 컬럼으로만 잇는다 — "현재 버전 포인터 전진" 은 Phase 7 이 담당한다.
 */
@Entity
@Table(name = "confirmed_route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConfirmedRoute extends BaseTimeEntity {

    @Id
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(name = "confirmed_at", nullable = false)
    private OffsetDateTime confirmedAt;

    private ConfirmedRoute(Long runId, OffsetDateTime confirmedAt) {
        this.runId = runId;
        this.confirmedAt = confirmedAt;
    }

    /** 확정 배치가 회차의 노선을 처음 확정할 때 생성한다(RTE-02) — 현재 버전 포인터는 아직 비어 있다. */
    public static ConfirmedRoute forRun(Long runId, OffsetDateTime confirmedAt) {
        return new ConfirmedRoute(runId, confirmedAt);
    }
}

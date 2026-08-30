package src.backend.run.navigation.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.enums.ChangeType;

/**
 * {@code run_stop} 을 내비 조회 전용으로 읽는 엔티티(API_SPEC §4.16) — 확정 노선의 정차 순번·
 * 도착·변경 여부만 본다. 이 모듈이 이 표에 <b>쓰지 않는다</b> — {@code change='skipped'} 는 다른
 * Phase 9 작업 갈래가 쓰는 값이고, 여기는 그 저장된 값만 읽어 제외 판단에 쓴다(경계 계약: 저장된
 * 컬럼 값에만 의존하고 그 작업 갈래의 코드는 부르지 않는다).
 *
 * <p>{@code @Entity(name = "NavRunStop")} 을 명시한 이유는 같은 표를 매핑하는 엔티티가 다른
 * 패키지에도 있을 수 있어서다 — 클래스 간 완전한 이름은 패키지가 갈라 겹치지 않지만, JPA 엔티티
 * 이름은 기본값이 단순 클래스명이라 우연히 같은 이름을 고르면 영속성 단위 등록이 충돌한다.
 */
@Entity(name = "NavRunStop")
@Table(name = "run_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NavRunStop {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "waypoint_id")
    private Long waypointId;

    @Column(name = "seq", nullable = false)
    private int seq;

    /** {@code null} 이면 원래 노선 그대로다 — {@link ChangeType#SKIPPED} 인 행만 내비에서 뺀다(C-05). */
    @Convert(converter = ChangeType.Db.class)
    @Column(name = "change", length = 10)
    private ChangeType change;

    /** 이미 지난 지점이면 채워진다 — 채워진 행은 내비 목적지 후보에서 뺀다. */
    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;
}

package src.backend.manager.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.enums.ManagerRole;

/**
 * 회차별 매니저 배치 — 매니저 앱의 회차 접근 범위 판정("배치된 회차만")과 대시보드의
 * {@code ack_driver}·{@code ack_escort} 표시의 근거다(ERD §3.3 · MGR-05·06 · RUN-07 · MON-05).
 *
 * <p>소유 모듈은 {@code run} 이 아니라 {@code manager} 다(조율자 Ruling 38). {@code created_at}·
 * {@code updated_at} 컬럼이 없어 {@code BaseTimeEntity} 를 상속하지 않는다 — 대신
 * {@code assigned_at} 을 배치 발생 시각으로 직접 관리한다.
 *
 * <p>{@code acked_route_version_id}·{@code acked_at} 은 노선 재배포 후 확인 응답을 기록하는
 * 상태 전이 대상이라 이 태스크(Phase 1)에서는 채우지 않고 {@code null} 로 둔다.
 */
@Entity
@Table(name = "assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    @Convert(converter = ManagerRole.Db.class)
    @Column(name = "role", length = 10, nullable = false)
    private ManagerRole role;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "acked_route_version_id")
    private Long ackedRouteVersionId;

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;

    private Assignment(Long runId, Long managerId, ManagerRole role, OffsetDateTime assignedAt, Long assignedBy) {
        this.runId = runId;
        this.managerId = managerId;
        this.role = role;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    /** 배치 담당자가 회차에 기사·동승자를 지정할 때 생성한다(MGR-05) — 확인 응답은 아직 미기록. */
    public static Assignment uponAssignment(Long runId, Long managerId, ManagerRole role, OffsetDateTime assignedAt,
            Long assignedBy) {
        return new Assignment(runId, managerId, role, assignedAt, assignedBy);
    }

    /**
     * 이미 채워진 자리에 다른 매니저를 넣는다(MGR-05, API_SPEC §5.14) — 관리 화면에서 담당자를 바꾸는
     * 정상 조작이라 거부하지 않는다.
     *
     * <p><b>확인 응답을 함께 지운다</b>({@code ackedRouteVersionId}·{@code ackedAt}) — 앞 담당자가
     * 노선을 확인했다는 기록이지 새 담당자가 확인했다는 기록이 아니다. 남겨 두면 노선을 한 번도 보지
     * 않은 기사가 확인 완료로 표시된다.
     *
     * <p>{@code role} 은 바뀌지 않는다 — 자리가 곧 역할이고, 바꾸려면 그 자리의 배치를 지우는 것이지
     * 이 행의 역할을 뒤집는 것이 아니다({@code uk_assignment_run_role} 이 그 자리를 하나로 고정한다).
     */
    public void reassign(Long newManagerId, OffsetDateTime assignedAt, Long assignedBy) {
        this.managerId = newManagerId;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
        this.ackedRouteVersionId = null;
        this.ackedAt = null;
    }

    /**
     * 기사·동승자가 노선 변경 확인 응답을 남긴다(API_SPEC §4.11, RUN-07·M-04) — 대상은 배포
     * 버전 단위다. {@code change_ids[]} 처럼 변경 건 하나하나를 골라 확인하는 저장 구조는 스키마에
     * 부재하다({@code acked_route_version_id} 가 배포 버전 1개만 가리킨다) — 요청에 그 필드가
     * 와도 이 메서드는 버전 전체 확인으로 처리한다.
     */
    public void ack(Long routeVersionId, OffsetDateTime ackedAt) {
        this.ackedRouteVersionId = routeVersionId;
        this.ackedAt = ackedAt;
    }
}

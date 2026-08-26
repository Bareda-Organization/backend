package src.backend.manager.entity;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 운행인력 — 기사·동승자를 한 테이블에 두고 {@code role} 로 가르며, 이 값이 앱 권한을 결정한다
 * (ERD §3.3 · MGR-01~06 · C-06 · A-12).
 *
 * <p>{@code accountId} 는 가입 승인 전 {@code NULL} 이다(AUTH-11) — 계정 연결은 별도 상태 전이라
 * 이 태스크(Phase 1) 범위 밖이다. {@code deletedAt} 은 soft delete 컬럼이며 필터(`@Where` 등)를
 * 이 태스크에서 붙이지 않는다.
 *
 * <p>{@code workHours} 는 {@code jsonb} 라 스키마가 부재하고 DB 가 형태를 막지 못한다 — 형태를
 * 고정하고 검증하는 자리는 {@link WorkHours} 이고, 이 엔티티는 <b>그 타입을 거친 값만</b> 담는다.
 * 컬럼 타입이 {@code Map} 인 것은 읽기 쪽 때문이다 — 이 타입이 생기기 전에 적재된 행이 실재해
 * (로컬 시드), 강타입으로 읽으면 그 행을 조회하는 것만으로 실패한다.
 */
@Entity
@Table(name = "manager")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Manager extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "phone", length = 30, nullable = false)
    private String phone;

    @Convert(converter = ManagerRole.Db.class)
    @Column(name = "role", length = 10, nullable = false)
    private ManagerRole role;

    /** 근무 시간(요일 × 시작·종료). 배치 충돌 검증(MGR-06)의 근거이며 내부 스키마는 소유 Phase 가 정한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "work_hours")
    private Map<String, Object> workHours;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    private Manager(Long academyId, ManagerProfile profile) {
        this.academyId = academyId;
        this.name = profile.name();
        this.phone = profile.phone();
        this.role = profile.role();
        this.workHours = columnValueOf(profile.workHours());
    }

    /** 학원 관계자가 기사·동승자를 등록할 때 생성한다(MGR-02, §5.13) — 계정 연결은 이후 별도로 채워진다. */
    public static Manager register(Long academyId, ManagerProfile profile) {
        return new Manager(academyId, profile);
    }

    /**
     * 매니저 정보를 고친다(MGR-03, §5.13) — {@code null} 인 항목은 <b>고치지 않는다</b>는 뜻이다.
     *
     * <p>{@code role} 변경이 곧 앱 권한 변경이다(C-06 · §5.13) — 기사를 동승자로 바꾸면 그 계정이
     * 승하차를 기록할 수 있게 되고 운행 시작 권한을 잃는다.
     */
    public void update(ManagerProfile profile) {
        if (profile.name() != null) {
            this.name = profile.name();
        }
        if (profile.phone() != null) {
            this.phone = profile.phone();
        }
        if (profile.role() != null) {
            this.role = profile.role();
        }
        if (profile.workHours() != null) {
            this.workHours = columnValueOf(profile.workHours());
        }
    }

    /**
     * 매니저를 삭제한다(MGR-04, §5.13) — 행을 지우지 않고 {@code deletedAt} 을 채우는 soft delete 다
     * (ERD §7.1).
     *
     * <p>행을 남기는 이유는 지난 회차의 {@code assignment} 가 이 행을 가리키기 때문이다 — 지우면
     * 과거 운행의 담당자가 누구였는지 답할 수단이 사라진다.
     *
     * <p>시각을 파라미터로 받는다(Ruling 62 · 횡단 규칙 1) — 호출부가 {@code Clock} 에서 얻어 넘긴다.
     */
    public void delete(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** 이미 삭제된 매니저인가 — 목록에서 빠지고 다시 삭제되지 않는다. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static Map<String, Object> columnValueOf(WorkHours workHours) {
        return workHours == null ? null : workHours.toColumnValue();
    }

    /**
     * 가입 승인 시점에 계정을 연결한다(AUTH-11 · API_SPEC §5.2) — {@code accountId} 가 승인 전
     * {@code NULL} 인 것이 정상이며 이 전이가 그 자리를 채운다.
     *
     * <p>이미 다른 계정이 붙어 있으면 {@link BusinessException}({@code ALREADY_LINKED}) — 덮어쓰면
     * 앞 계정이 배치된 회차 명단에 닿을 근거를 잃고, 새 계정이 그 회차를 대신 받는다.
     */
    public void linkAccount(Long newAccountId) {
        if (this.accountId != null && !this.accountId.equals(newAccountId)) {
            throw new BusinessException(ErrorCode.ALREADY_LINKED);
        }
        this.accountId = newAccountId;
    }
}

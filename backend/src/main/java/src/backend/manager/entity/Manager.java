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
 * <p>{@code workHours} 는 등록 시점에 비어 있을 수 있어(nullable) 팩토리 파라미터로 직접 받는다 —
 * {@code Academy.register()} 가 nullable {@code address}·{@code contact} 를 직접 받는 것과 같은
 * 패턴이다.
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

    private Manager(Long academyId, String name, String phone, ManagerRole role, Map<String, Object> workHours) {
        this.academyId = academyId;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.workHours = workHours;
    }

    /** 학원 관리자가 기사·동승자를 등록할 때 생성한다(MGR-01) — 계정 연결은 이후 별도로 채워진다. */
    public static Manager register(Long academyId, String name, String phone, ManagerRole role,
            Map<String, Object> workHours) {
        return new Manager(academyId, name, phone, role, workHours);
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

package testsupport.timeaudit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import src.backend.global.common.BaseTimeEntity;

/** BaseTimeEntity 의 감사 시각 동작만 검증하기 위한 테스트 전용 엔티티 — 실제 도메인 테이블이 아니다. */
@Entity
public class AuditingProbeEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    protected AuditingProbeEntity() {
    }

    private AuditingProbeEntity(String label) {
        this.label = label;
    }

    /** 라벨만 가진 테스트용 인스턴스를 만든다. */
    public static AuditingProbeEntity of(String label) {
        return new AuditingProbeEntity(label);
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** updatedAt 갱신을 유발하기 위한 수정 동작. */
    public void changeLabel(String label) {
        this.label = label;
    }
}

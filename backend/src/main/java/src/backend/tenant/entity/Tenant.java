package src.backend.tenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;

/**
 * 학원(테넌트) — 멀티 테넌시의 최상위 격리 단위.
 * 테넌트 종속 데이터(학생/버스/노선/기록…)는 모두 이 테넌트를 tenant_id 로 참조한다.
 */
@Entity
@Table(name = "tenant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private Double lat;   // 학원 위치(depot) — routing 모듈이 노선 계산 기준점으로 사용
    private Double lng;

    @Builder
    public Tenant(String name, Double lat, Double lng) {
        this.name = name;
        this.lat = lat;
        this.lng = lng;
    }

    /** 학원 위치 갱신 — routing 이 depot 좌표로 사용하려면 반드시 설정돼 있어야 한다. */
    public void updateLocation(Double lat, Double lng) {
        this.lat = lat;
        this.lng = lng;
    }
}

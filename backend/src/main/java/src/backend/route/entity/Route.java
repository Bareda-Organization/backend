package src.backend.route.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.tenant.entity.Tenant;

/**
 * 노선 — 정류장 순서 목록을 가지며 버스가 이 노선을 운행한다.
 * assignCapacity(배정 정원)는 버스의 물리 좌석 수(seatCapacity)와 별개 개념.
 */
@Entity
@Table(name = "route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Route extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int assignCapacity;   // 노선에 배정 가능한 학생 수

    @Builder
    public Route(Tenant tenant, String name, int assignCapacity) {
        this.tenant = tenant;
        this.name = name;
        this.assignCapacity = assignCapacity;
    }
}

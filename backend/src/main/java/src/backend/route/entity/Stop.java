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

/**
 * 정류장 — 노선에 속하며 seq(순서)와 좌표를 가진다.
 */
@Entity
@Table(name = "stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stop extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id")
    private Route route;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int seq;      // 노선 내 정류장 순서

    private double lat;
    private double lng;

    @Builder
    public Stop(Route route, String name, int seq, double lat, double lng) {
        this.route = route;
        this.name = name;
        this.seq = seq;
        this.lat = lat;
        this.lng = lng;
    }
}

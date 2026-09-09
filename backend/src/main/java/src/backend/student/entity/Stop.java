package src.backend.student.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 승하차지 마스터 — 학생 등록·주소 수정 시 "주소 검증 → 승하차지 매칭 또는 신규 생성"이
 * 회차 생성 이전에 발생하므로, 회차와 무관하게 존속하는 마스터가 필요하다(ERD §3.2 · STU-05 ·
 * C-12 · UF-M-03·06).
 *
 * <p>이름이 {@code routing}·{@code boarding} 을 연상시키지만 소유는 {@code student} 모듈이다 —
 * 생성 계기가 주소 검증(STU-05)이기 때문이다(ARCHITECTURE §3.3). {@code routing}·{@code boarding}
 * 은 이 테이블을 읽기만 한다.
 */
@Entity
@Table(name = "stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stop extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "lat", precision = 9, scale = 6, nullable = false)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6, nullable = false)
    private BigDecimal lng;

    private Stop(Long academyId, String name, String address, BigDecimal lat, BigDecimal lng) {
        this.academyId = academyId;
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
    }

    /** 주소 검증 결과 기존 승하차지와 매칭되지 않아 새로 만드는 시점에 생성한다(STU-05). */
    public static Stop forVerifiedAddress(Long academyId, String name, String address, BigDecimal lat,
            BigDecimal lng) {
        return new Stop(academyId, name, address, lat, lng);
    }
}

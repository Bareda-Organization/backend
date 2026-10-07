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

    /**
     * 관계자가 승하차지의 이름·자리를 고친다(고정 노선 편성 화면, 2026-09-23 사용자 지시).
     *
     * <p><b>이 행을 가리키는 모든 노선·학생 주소에 함께 반영된다</b> — 승하차지는 노선마다 따로 있는 것이
     * 아니라 학원의 한 장소다. 노선별 사본을 만들면 학생 주소({@code weekly_address.stop_id})가 옛 행을
     * 가리킨 채 남아, 확정 배치가 그 학생을 새 자리에서 태우지 못한다.
     *
     * @param address 생략하면 지금 주소를 그대로 둔다
     */
    public void relocate(String name, String address, BigDecimal lat, BigDecimal lng) {
        this.name = name;
        if (address != null && !address.isBlank()) {
            this.address = address;
        }
        this.lat = lat;
        this.lng = lng;
    }

    /** 이 좌표로 옮기면 지금 자리와 달라지는가 — 운행 중 노선 잠금(BR-052)이 이름·주소만 고치는 요청과 가르는 기준이다. */
    public boolean movesTo(BigDecimal lat, BigDecimal lng) {
        return this.lat.compareTo(lat) != 0 || this.lng.compareTo(lng) != 0;
    }

    /** 주소 검증 결과 기존 승하차지와 매칭되지 않아 새로 만드는 시점에 생성한다(STU-05). */
    public static Stop forVerifiedAddress(Long academyId, String name, String address, BigDecimal lat,
            BigDecimal lng) {
        return new Stop(academyId, name, address, lat, lng);
    }
}

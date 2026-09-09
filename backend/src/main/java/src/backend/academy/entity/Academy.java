package src.backend.academy.entity;

import java.math.BigDecimal;

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

/**
 * 학원 — 멀티테넌시의 최상위 단위이자 가입 시 사용자가 선택하는 대상이다(ERD §3.1 · ACAD-01~04).
 * 비활성화해도 기존 로그인은 유지되며 신규 가입만 차단된다(O-01).
 */
@Entity
@Table(name = "academy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Academy extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "region", length = 50, nullable = false)
    private String region;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "contact", length = 30)
    private String contact;

    @Column(name = "memo", columnDefinition = "text")
    private String memo;

    @Convert(converter = AcademyStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private AcademyStatus status;

    /** 확정 배치(RTE-08)가 읽는 노선 기준점 — 주소 미등록 학원이 있어 {@link #hasCoordinates()} 없이 단독으로 참조하지 않는다. */
    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    /** {@link #lat} 과 항상 짝을 이루며 DB 의 {@code ck_academy_coords_paired} 가 한쪽만 채워지는 것을 막는다. */
    @Column(name = "lng", precision = 9, scale = 6)
    private BigDecimal lng;

    private Academy(String code, String name, String region, String address, String contact) {
        this.code = code;
        this.name = name;
        this.region = region;
        this.address = address;
        this.contact = contact;
        this.status = AcademyStatus.ACTIVE;
    }

    /** 플랫폼 관리자가 학원을 등록할 때 생성한다(O-01) — 코드는 서버가 자동 생성해 호출 전에 확정된 값으로 넘어온다. */
    public static Academy register(String code, String name, String region, String address, String contact) {
        return new Academy(code, name, region, address, contact);
    }

    /**
     * 학원 정보를 고친다(ACAD-04, API_SPEC §6.3) — {@code null} 인 항목은 그대로 둔다.
     *
     * <p>{@code code} 를 인자로 받지 않는 것이 "코드는 수정 대상 밖" 을 강제하는 방식이다 — 요청 본문에
     * {@code code} 가 실려 와도 이 메서드까지 닿을 경로가 부재하다.
     */
    public void update(AcademyProfile profile) {
        this.name = profile.name() == null ? this.name : profile.name();
        this.region = profile.region() == null ? this.region : profile.region();
        this.address = profile.address() == null ? this.address : profile.address();
        this.contact = profile.contact() == null ? this.contact : profile.contact();
        this.memo = profile.memo() == null ? this.memo : profile.memo();
    }

    /**
     * 운영 상태를 바꾼다(ACAD-04) — {@code inactive} 는 가입 검색·신규 가입만 막고 <b>기존 로그인은
     * 유지</b>한다. 물리 삭제 경로는 부재하며 soft delete 로만 다룬다(API_SPEC §6.3).
     */
    public void changeStatus(AcademyStatus status) {
        this.status = status;
    }

    /**
     * 확정 배치(RTE-08)의 노선 기준점을 정한다 — 지오코딩 자동 채움은 이 Phase 의 범위 밖이라
     * 좌표는 이 메서드를 통해서만 채워진다. 한쪽만 넘기면 DB CHECK 이전에 여기서 걸러진다.
     */
    public void assignCoordinates(BigDecimal lat, BigDecimal lng) {
        if (lat == null || lng == null) {
            throw new IllegalArgumentException("학원 좌표는 위도·경도가 모두 있어야 한다");
        }
        this.lat = lat;
        this.lng = lng;
    }

    /** 확정 배치가 이 학원을 기준점으로 쓸 수 있는지 — 둘 다 있을 때만 참이다(목표 5). */
    public boolean hasCoordinates() {
        return lat != null && lng != null;
    }
}

package src.backend.bus.entity;

import java.time.LocalDate;

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
import src.backend.route.entity.Route;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.User;

/**
 * 통원 버스 — 담당 기사·선탑자·운행 노선·물리 좌석 수를 가진다.
 * driver(역할 DRIVER)는 운전·운행 세션을, attendant(역할 ATTENDANT)는 승하차 기록을 맡는다.
 * 둘 다 배차 전이면 null 가능.
 */
@Entity
@Table(name = "bus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bus extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(nullable = false)
    private String name;          // 예: "3호차"

    private String plateNumber;   // 차량번호

    @Column(nullable = false)
    private int seatCapacity;     // 물리 좌석 수

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private User driver;          // 담당 기사 (미배차 시 null)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendant_id")
    private User attendant;       // 담당 선탑자(승하차 기록 주체). 스키마상 nullable 이지만 운영상 항상 배정한다(I-1)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;          // 운행 노선

    private LocalDate insuranceExpiry;  // 보험 만료일

    @Builder
    public Bus(Tenant tenant, String name, String plateNumber, int seatCapacity,
               User driver, User attendant, Route route, LocalDate insuranceExpiry) {
        this.tenant = tenant;
        this.name = name;
        this.plateNumber = plateNumber;
        this.seatCapacity = seatCapacity;
        this.driver = driver;
        this.attendant = attendant;
        this.route = route;
        this.insuranceExpiry = insuranceExpiry;
    }

    /** 담당 기사 배차(관리자 배차 변경). null 이면 배차 해제. */
    public void assignDriver(User driver) {
        this.driver = driver;
    }

    /** 담당 선탑자 배정(관리자 배차 변경). null 이면 배정 해제. */
    public void assignAttendant(User attendant) {
        this.attendant = attendant;
    }

    /** 운행 노선 배정(관리자 배차 변경). */
    public void assignRoute(Route route) {
        this.route = route;
    }
}

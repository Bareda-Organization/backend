package src.backend.student.entity;

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
import src.backend.bus.entity.Bus;
import src.backend.global.common.BaseTimeEntity;
import src.backend.route.entity.Stop;
import src.backend.tenant.entity.Tenant;

/**
 * 학생 — 단일 학원(tenant)에 종속. 담당 버스·기본 승차 정류장을 가진다.
 * 보호자(학부모) 연결은 N:M 이라 StudentGuardian 조인 엔티티로 표현한다.
 */
@Entity
@Table(name = "student")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    // 학생 로그인 계정(User, 역할 STUDENT) 참조. 계정 미연결 시 null.
    // 학생 본인이 자기 승하차 기록을 조회할 때 userId → Student 로 해석한다.
    private Long userId;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bus_id")
    private Bus assignedBus;      // 배정 버스 (미배정 시 null)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stop_id")
    private Stop boardingStop;    // 기본 승차 정류장(등원 좌표로도 재사용)

    private String dropoffAddress;   // 하차지(하원) 주소 — 표시용
    private Double dropoffLat;
    private Double dropoffLng;

    @Builder
    public Student(Tenant tenant, Long userId, String name, Bus assignedBus, Stop boardingStop) {
        this.tenant = tenant;
        this.userId = userId;
        this.name = name;
        this.assignedBus = assignedBus;
        this.boardingStop = boardingStop;
    }

    /** 배차 갱신(관리자 배차 변경 시 사용). */
    public void assignBus(Bus bus) {
        this.assignedBus = bus;
    }

    /** 기본 승차 정류장 갱신(관리자 재배정 시 사용). */
    public void assignStop(Stop stop) {
        this.boardingStop = stop;
    }

    /** 하차지(하원) 좌표 갱신 — routing 모듈이 하원 노선 계산에 사용한다. */
    public void updateDropoff(String dropoffAddress, Double dropoffLat, Double dropoffLng) {
        this.dropoffAddress = dropoffAddress;
        this.dropoffLat = dropoffLat;
        this.dropoffLng = dropoffLng;
    }
}

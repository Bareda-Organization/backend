package src.backend.bus.entity;

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
 * 차량 — 정원 초과 차단의 기준값을 보유한다(ERD §3.3 · BUS-01~04 · A-11).
 *
 * <p>{@code student_capacity} 는 {@code capacity - driver_count - escort_count} 의 DB
 * CHECK(`ck_bus_student_capacity`)로 정합성이 강제되는 계산값이지만, 그 계산 자체는 이 태스크의
 * 범위 밖(Phase 5)이라 팩토리는 호출자가 미리 계산해 넘긴 값을 그대로 저장한다.
 */
@Entity
@Table(name = "bus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bus extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "bus_no", length = 20, nullable = false)
    private String busNo;

    @Column(name = "plate_no", length = 20, nullable = false)
    private String plateNo;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "driver_count", nullable = false)
    private int driverCount;

    @Column(name = "escort_count", nullable = false)
    private int escortCount;

    @Column(name = "student_capacity", nullable = false)
    private int studentCapacity;

    @Column(name = "operable", nullable = false)
    private boolean operable;

    private Bus(Long academyId, String busNo, String plateNo, int capacity, int driverCount, int escortCount,
            int studentCapacity) {
        this.academyId = academyId;
        this.busNo = busNo;
        this.plateNo = plateNo;
        this.capacity = capacity;
        this.driverCount = driverCount;
        this.escortCount = escortCount;
        this.studentCapacity = studentCapacity;
        this.operable = true;
    }

    /**
     * 학원 관리자가 차량을 등록할 때 생성한다(BUS-01) — 운행 가능 상태로 시작한다.
     *
     * <p>{@code studentCapacity} 는 DB CHECK 가 {@code capacity - driverCount - escortCount} 와
     * 일치하는지 검증할 값이며, 그 계산은 호출자(Phase 5) 책임이다.
     */
    public static Bus register(Long academyId, String busNo, String plateNo, int capacity, int driverCount,
            int escortCount, int studentCapacity) {
        return new Bus(academyId, busNo, plateNo, capacity, driverCount, escortCount, studentCapacity);
    }
}

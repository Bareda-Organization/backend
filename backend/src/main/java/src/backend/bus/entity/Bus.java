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
 * <p>{@code studentCapacity} 를 파라미터로 받지 않고 {@link BusSeating} 이 계산한 값만 담는다 —
 * 요청이 실어 보낸 정원을 그대로 저장하는 경로를 <b>타입에서 없앤 것</b>이 API_SPEC §5.12 의 "응답 전용,
 * 관계자가 입력하지 않음" 을 지키는 방식이다.
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

    private Bus(Long academyId, String busNo, String plateNo, BusSeating seating) {
        this.academyId = academyId;
        this.busNo = busNo;
        this.plateNo = plateNo;
        this.operable = true;
        applySeating(seating);
    }

    /** 학원 관계자가 차량을 등록할 때 생성한다(BUS-02, §5.12) — 운행 가능 상태로 시작한다. */
    public static Bus register(Long academyId, String busNo, String plateNo, BusSeating seating) {
        return new Bus(academyId, busNo, plateNo, seating);
    }

    /**
     * 차량 정보를 고친다(BUS-03, §5.12) — {@code null} 인 인자는 <b>고치지 않는다</b>는 뜻이다.
     *
     * <p>{@code capacity} 만 받고 승무 인원은 받지 않는 것이 이 시그니처의 요점이다 — <b>정원을 바꿔도
     * 기사·동승자 수는 이 차량이 지금 들고 있는 값 그대로</b>이고, 학생 정원만 그에 맞게 다시 계산된다.
     * 그 이어받기를 호출부에 맡기면 호출부마다 승무 인원을 어디서 가져올지가 갈리고, 기본값(1·1)으로
     * 되돌리는 구현과 이어받는 구현이 <b>지금은 같은 값을 내</b> 아무 단언에도 걸리지 않는다
     * (게이트 리뷰 A4 가 실증). 학원별 승무 인원이 생기는 Phase 에서 조용히 죽는 자리라 여기로 옮겼다.
     *
     * <p>정원만 갈아 끼우고 학생 정원 재계산을 건너뛰면 DB CHECK 가 그 UPDATE 를 거부해 {@code 500} 이
     * 되고, 그 실패가 정원 정책이 아니라 저장 계층의 문제로 보인다.
     */
    public void update(String busNo, String plateNo, Integer capacity, Boolean operable) {
        if (busNo != null) {
            this.busNo = busNo;
        }
        if (plateNo != null) {
            this.plateNo = plateNo;
        }
        if (capacity != null) {
            applySeating(new BusSeating(capacity, this.driverCount, this.escortCount));
        }
        if (operable != null) {
            this.operable = operable;
        }
    }

    private void applySeating(BusSeating seating) {
        this.capacity = seating.capacity();
        this.driverCount = seating.driverCount();
        this.escortCount = seating.escortCount();
        this.studentCapacity = seating.studentCapacity();
    }
}

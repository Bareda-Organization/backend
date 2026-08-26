package src.backend.bus.entity;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 차량의 좌석 구성 — 승차 정원에서 승무 인원을 빼 <b>학생 탑승 가능 인원을 계산</b>한다
 * (ERD §3.3 {@code ck_bus_student_capacity} · BUS-04 · API_SPEC §5.12).
 *
 * <p>계산을 {@link Bus} 가 아니라 이 타입이 갖는 이유는 <b>정원 수정(BUS-03)도 같은 계산을 다시
 * 해야</b> 하기 때문이다 — 등록과 수정 두 곳에 식을 적으면 한쪽만 고쳐졌을 때 CHECK 가 등록 경로
 * 에서만 통과한다.
 *
 * <p>{@code student_capacity} 를 이 타입 밖에서 받을 자리를 두지 않은 것이 <b>"응답 전용 — 관계자가
 * 입력하지 않음"</b>(§5.12)을 구조로 강제하는 방식이다. 요청 DTO 에 그 필드가 없는 것만으로는
 * 다음 사람이 필드를 하나 더해 그대로 저장하는 것을 막지 못한다.
 *
 * @param capacity    승차 정원(승무 인원 포함)
 * @param driverCount 정원에서 제외할 기사 수
 * @param escortCount 정원에서 제외할 동승자 수
 */
public record BusSeating(int capacity, int driverCount, int escortCount) {

    /** 기사 수 기본값 — ERD §3.3 {@code bus.driver_count} 의 {@code NN default 1}. */
    public static final int DEFAULT_DRIVER_COUNT = 1;

    /** 동승자 수 기본값 — ERD §3.3 {@code bus.escort_count} 의 {@code NN default 1}. */
    public static final int DEFAULT_ESCORT_COUNT = 1;

    /**
     * 학생 정원이 0 이하가 되는 구성을 저장 전에 거부한다 — DB CHECK({@code ck_bus_capacity})와 같은
     * 조건이나, 여기서 막지 않으면 제약 위반이 예외 번역을 거치지 않아 {@code 500} 으로 샌다.
     *
     * <p>그 차량이 저장되면 Phase 7 확정 배치가 <b>0명짜리 버스를 편성</b>하고, 그때는 원인이 배치
     * 로직으로 보인다.
     */
    public BusSeating {
        if (driverCount < 0 || escortCount < 0 || capacity <= driverCount + escortCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 승무 인원을 ERD 기본값으로 채운다 — API_SPEC §5.12 의 요청 필드에 기사·동승자 수가 부재해
     * 등록·수정 경로가 값을 받을 자리가 없다.
     */
    public static BusSeating withDefaultCrew(int capacity) {
        return new BusSeating(capacity, DEFAULT_DRIVER_COUNT, DEFAULT_ESCORT_COUNT);
    }

    /** 학생 탑승 가능 인원 = 정원 − 기사 − 동승자. 정원 검증의 기준값이다(§5.12). */
    public int studentCapacity() {
        return capacity - driverCount - escortCount;
    }
}

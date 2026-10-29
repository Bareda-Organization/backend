package src.backend.student.repository;

/**
 * 그날 그 학생이 서는 승하차지 1행 — 노선 계산 ①단계(좌표 해석)가 읽는 조회 전용 투영이다
 * ({@code ARCHITECTURE §8.1} · C-16).
 *
 * <p>{@code WeeklyAddress} 엔티티를 그대로 꺼내지 않는 이유는 계산이 쓰는 것이 <b>승하차지 참조
 * 하나뿐</b>이기 때문이다. 주소 문자열·검증 여부까지 딸려 오면 버스가 서는 자리를 정하는 코드가
 * 학생의 주소 원문을 손에 쥐게 되고, 좌표를 {@code weekly_address} 쪽에서 읽는 경로가 생긴다 —
 * 그러면 같은 지점의 학생들이 지오코딩 오차만큼 서로 다른 자리에 서서 버스가 몇 m 씩 나눠 선다.
 */
public interface StudentDailyStop {

    Long getStudentId();

    Long getStopId();
}

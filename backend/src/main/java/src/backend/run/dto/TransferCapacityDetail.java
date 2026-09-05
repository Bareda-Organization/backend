package src.backend.run.dto;

/**
 * {@code 409 CAPACITY_EXCEEDED}(API_SPEC §5.8, RTE-07)의 {@code details} 페이로드 —
 * "현재 인원·정원 병기" 요구를 담는다. {@code ForcedAdditionCommandService}(§5.7)의 같은 오류는
 * 이 요구가 사양에 없어 details 를 싣지 않는다 — 절마다 다른 요구를 절 문면 그대로 따른 결과다.
 *
 * @param current 이번 이동을 반영하기 <b>전</b> 도착 회차의 투영 인원(요일별 주소 기준 예정 명단 +
 *                대기 중인 강제 추가 + 대기 중인 다른 이동 건)
 * @param capacity 도착 회차 버스의 {@code student_capacity}(BUS-04)
 */
public record TransferCapacityDetail(long current, int capacity) {
}

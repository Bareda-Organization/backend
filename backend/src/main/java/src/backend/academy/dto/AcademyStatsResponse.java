package src.backend.academy.dto;

/**
 * 학원 상세의 운영 지표(API_SPEC §6.3 {@code stats}).
 *
 * @param movingBusCount 지금 운행 중인 차량 수. <b>현재는 항상 0 이다</b> — 값의 재료인 {@code run}
 *                       행은 회차 생성 배치(Phase 9)가 만들고, 그 배치가 없는 지금은 어떤 학원도
 *                       {@code moving} 회차를 가질 수 없어 실제 집계 결과와 0 이 일치한다. 필드를
 *                       빼지 않는 이유는 만들 것이 사라진 게 아니라 재료가 아직 없기 때문이며,
 *                       빼면 클라이언트가 나중에 생기는 필드에 맞춰 다시 고쳐야 한다
 */
public record AcademyStatsResponse(long movingBusCount) {

    /** 회차 테이블에 행이 생기기 전까지의 값 — Phase 9 가 실제 집계로 바꾼다. */
    public static AcademyStatsResponse empty() {
        return new AcademyStatsResponse(0);
    }
}

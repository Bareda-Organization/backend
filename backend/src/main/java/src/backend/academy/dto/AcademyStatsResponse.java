package src.backend.academy.dto;

/**
 * 학원 상세의 운영 지표(API_SPEC §6.3 {@code stats}).
 *
 * @param movingBusCount 지금 운행 중({@code run.status = moving}) 회차가 있는 차량 수 — 회차 수가 아니라
 *                       차량 수다(BR-057)
 */
public record AcademyStatsResponse(long movingBusCount) {
}

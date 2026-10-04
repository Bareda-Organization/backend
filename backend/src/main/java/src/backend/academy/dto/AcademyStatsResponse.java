package src.backend.academy.dto;

import java.util.List;

/**
 * 학원 상세의 운영 지표(API_SPEC §6.3 {@code stats}).
 *
 * @param movingBusCount 지금 운행 중({@code run.status = moving}) 회차가 있는 차량 수 — 회차 수가 아니라
 *                       차량 수다(BR-057)
 * @param movingBusNos   그 차량들의 호차 이름(Ruling 806) — {@code movingBusCount} 와 같은 범위이며 이름순이다
 */
public record AcademyStatsResponse(long movingBusCount, List<String> movingBusNos) {

    /** 호차 이름 목록에서 개수까지 함께 만든다 — 두 값이 같은 목록에서 나와 범위가 갈릴 수 없다. */
    public static AcademyStatsResponse of(List<String> movingBusNos) {
        return new AcademyStatsResponse(movingBusNos.size(), movingBusNos);
    }
}

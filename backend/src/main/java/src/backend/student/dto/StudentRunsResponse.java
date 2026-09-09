package src.backend.student.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 자녀·본인 당일 회차 목록(API_SPEC §3.5, P-04 · S-01).
 *
 * <p>{@code runId} 는 문서 표기가 {@code string} 이지만 순수 식별자로 {@code Long} 을 쓴다 —
 * {@code BoardingIntentToggleResponse.changeRequestId} 가 이미 같은 어긋남에 같은 판단을 내렸다
 * (문서 예시의 접두 문자열은 표기 관례일 뿐, 이 저장소의 식별자 응답 관례는 항상 순수 {@code Long}).
 *
 * <p>{@code riding}·{@code changeQuotaLeft} 는 그 회차에 {@code boarding_intent} 행이 아직 없으면
 * 기본값(탑승 ON·한도 미사용, {@link src.backend.request.entity.BoardingIntent#forRun} 의 초기값과
 * 같다)으로 채운다 — 아직 한 번도 토글하지 않은 학생도 "탑승 예정" 이 기본이어야 하기 때문이다.
 */
public record StudentRunsResponse(List<Item> items) {

    public record Item(Long runId, String direction, String busNo, OffsetDateTime departTime, String runStatus,
            boolean confirmed, boolean riding, String riderStatus, Stop stop, int changeQuotaLeft) {
    }

    public record Stop(Long stopId, String name, String address) {
    }
}

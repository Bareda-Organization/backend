package src.backend.student.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 자녀·본인 당일 회차 목록(API_SPEC §3.5, P-04 · S-01).
 *
 * <p>{@code runId} 필드 타입은 자바 코드에서 {@code Long} 그대로다 — JSON 으로는
 * {@link src.backend.global.config.IdentifierJsonConfig} 가 문자열로 감싸 내보낸다(API_SPEC §1.1,
 * Ruling 332). 예전에는 이 필드를 순수 {@code Long} 으로 노출하는 것이 관례였으나 그 판단은
 * Ruling 332 로 뒤집혔다.
 *
 * <p>{@code riding}·{@code changeQuotaLeft} 는 그 회차에 {@code boarding_intent} 행이 아직 없으면
 * 기본값(탑승 ON·한도 미사용, {@link src.backend.request.entity.BoardingIntent#forRun} 의 초기값과
 * 같다)으로 채운다 — 아직 한 번도 토글하지 않은 학생도 "탑승 예정" 이 기본이어야 하기 때문이다.
 */
public record StudentRunsResponse(List<Item> items) {

    /** 회차 1건 — {@code riding}·{@code changeQuotaLeft} 는 명단 미생성 시 기본값으로 채운다(위 클래스 자바독). */
    public record Item(Long runId, String direction, String busNo, OffsetDateTime departTime, String runStatus,
            boolean confirmed, boolean riding, String riderStatus, Stop stop, int changeQuotaLeft) {
    }

    /** 그 회차의 이 학생 승하차지. */
    public record Stop(Long stopId, String name, String address) {
    }
}

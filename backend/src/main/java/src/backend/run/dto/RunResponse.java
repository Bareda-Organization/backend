package src.backend.run.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import src.backend.manager.dto.AssignedManagerResponse;
import src.backend.run.entity.Run;

/**
 * 회차 응답(API_SPEC §5.10) — 목록·임시 추가가 함께 쓴다. 관계자 웹 전용이라 역할별로 가르지 않는다.
 *
 * <p>{@code confirmAt} 을 함께 싣는 이유는 그것이 <b>파생이 아니라 저장된 값</b>이기 때문이다
 * (C-03 · {@code ck_run_confirm_at}) — 클라이언트가 출발 시각에서 다시 빼서 그리면 정책이 바뀔 때
 * 화면과 서버가 갈린다.
 *
 * <p>{@code scheduleId} 가 {@code null} 이면 임시 추가한 회차다(SCH-03) — 정규 회차와 임시 회차를
 * 가르는 유일한 표시라 응답에서 뺄 수 없다.
 *
 * <p>CODE_CONVENTIONS §20.2 — 필드 14개를 넘긴 채 둔다(2026-09-25 검사 BR-101). 필드 목록은 API_SPEC §5.10 응답
 * 스키마가 정한 계약이라 코드 쪽에서 임의로 줄이거나 쪼갤 수 없다.
 *
 * @param assignments 그 회차의 <b>현재 배치 전부</b>. 배치 화면이 결과를 되읽는 유일한 경로다(§5.14)
 * @param consecutiveFailures 확정 배치의 연속 실패 횟수 — 확정이 계속 실패하는 회차를 알아보는 재료(BR-047 · UF-O-07)
 */
public record RunResponse(Long id, Long busId, String busNo, Long scheduleId, LocalDate serviceDate,
        String direction, OffsetDateTime departTime, OffsetDateTime confirmAt, String status,
        String originName, String destinationName, Integer estDurationMin, OffsetDateTime canceledAt,
        List<AssignedManagerResponse> assignments, int consecutiveFailures) {

    /** {@link Run} 엔티티와 함께 조회한 차량 번호·배치 목록을 응답 형태로 옮긴다. */
    public static RunResponse of(Run run, String busNo, List<AssignedManagerResponse> assignments) {
        return new RunResponse(run.getId(), run.getBusId(), busNo, run.getScheduleId(), run.getServiceDate(),
                lower(run.getDirection().name()), run.getDepartTime(), run.getConfirmAt(),
                lower(run.getStatus().name()), run.getOriginName(), run.getDestinationName(),
                run.getEstDurationMin(), run.getCanceledAt(), assignments, run.getConsecutiveFailures());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

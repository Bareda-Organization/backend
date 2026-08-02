package src.backend.schedule.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/**
 * 등하원 위치 변경 신청 — 학부모가 본인 자녀에 대해서만 생성할 수 있다.
 * <p>⚠️ <b>busId 를 받지 않는다.</b> 대상 버스는 서버가 {@code student.getAssignedBus()} 에서 유도한다 —
 * 클라이언트가 버스를 지정하게 하면 "남의 버스 노선을 계산·재배포" 시킬 수 있다.
 */
public record CreateLocationChangeRequest(
        @NotNull @Schema(example = "1", description = "학생 id(요청자의 자녀)") Long studentId,
        @NotNull @Schema(example = "DROPOFF", description = "PICKUP=등원 승차지, DROPOFF=하원 하차지") RouteDirection direction,
        @Schema(example = "2026-08-02", description = "대상 날짜. 생략하면 오늘") LocalDate targetDate,
        @NotNull @Schema(example = "37.5045") Double lat,
        @NotNull @Schema(example = "127.0480") Double lng,
        @Schema(example = "역삼동 주민센터 앞", description = "표시용 주소/정류장명") String label) {
}

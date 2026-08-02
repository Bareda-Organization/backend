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
        @NotNull @Schema(example = "1", description = "학생 id(1=김민준, parent@school.com 의 자녀). 자기 자녀가 아니면 403") Long studentId,
        @NotNull @Schema(example = "DROPOFF", description = "PICKUP=등원 승차지, DROPOFF=하원 하차지") RouteDirection direction,
        @Schema(example = "2026-08-02", description = "대상 날짜. 생략하면 오늘") LocalDate targetDate,
        // 시드 학생 1의 현재 하차지(37.4998, 127.0245) 에서 300m 남짓 떨어진 좌표다 —
        // 노선 계획이 있는 상태에서도 임계(+1000m·+300s) 안에 들어가 REPLANNED 를 볼 수 있게 잡았다.
        @NotNull @Schema(example = "37.5013", description = "새 위도") Double lat,
        @NotNull @Schema(example = "127.0268", description = "새 경도") Double lng,
        @Schema(example = "서초구 자택 앞 큰길", description = "표시용 주소/정류장명. 학생의 승차지/하차지 주소로도 함께 저장된다") String label) {
}

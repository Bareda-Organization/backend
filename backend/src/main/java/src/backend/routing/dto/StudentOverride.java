package src.backend.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 배차 변경안 한 건 — 저장하지 않고 시뮬레이션에만 적용된다(BE-4). */
@Schema(description = "배차 변경안 한 건. 현재 명단(로스터)에 이 변경을 얹어 노선을 다시 계산한다 — 어떤 행도 저장하지 않는다.")
public record StudentOverride(
        @Schema(example = "3", description = "대상 학생 id(3=박도윤, 3호차 탑승). 다른 학원 학생이면 403") Long studentId,
        @Schema(example = "MOVE",
                description = "ADD=명단에 없는 학생을 끼워 넣는다 / REMOVE=명단에서 뺀다 / MOVE=좌표만 옮긴다")
        OverrideAction action,
        @Schema(example = "37.5061", description = "새 위도. ADD·MOVE 면 필수, REMOVE 면 무시된다(없으면 400)") Double lat,
        @Schema(example = "127.0299", description = "새 경도. ADD·MOVE 면 필수, REMOVE 면 무시된다(없으면 400)") Double lng) {

    public enum OverrideAction { ADD, REMOVE, MOVE }
}

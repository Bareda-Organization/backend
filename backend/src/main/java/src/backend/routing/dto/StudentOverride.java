package src.backend.routing.dto;

/** 배차 변경안 한 건 — 저장하지 않고 시뮬레이션에만 적용된다(BE-4). */
public record StudentOverride(
        Long studentId,
        OverrideAction action,          // ADD | REMOVE | MOVE
        Double lat,                     // MOVE·ADD 일 때 필수, REMOVE 면 null
        Double lng) {

    public enum OverrideAction { ADD, REMOVE, MOVE }
}

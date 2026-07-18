package src.backend.bus.dto;

import java.util.List;

/**
 * 버스 상세 응답 — 요약 정보 + 탑승 학생 명단(roster).
 */
public record BusDetailResponse(
        BusResponse bus,
        List<RosterEntry> roster) {

    /** 명단 한 줄 — 학생 식별자와 이름. */
    public record RosterEntry(Long studentId, String name) {
    }
}

package src.backend.run.roster;

import java.util.List;

import src.backend.request.entity.ChangeRequest;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.entity.RunTransfer;
import src.backend.student.repository.StudentDailyStop;

/**
 * 한 회차의 예정 명단을 조립하는 재료({@link RosterAssembler}) — 요일별 주소 학생 · ①구간 탑승 끄기 학생 · 승인된 승하차지 이동 · 강제 추가 · 출발 이동 · 도착 이동.
 *
 * @param excludedIds ①구간 탑승 의사가 꺼진 학생 id
 */
record RosterSources(Run run, List<StudentDailyStop> dailyStops, List<Long> excludedIds,
        List<ChangeRequest> relocations, List<RunForcedAddition> forcedAdditions, List<RunTransfer> outgoingTransfers,
        List<RunTransfer> incomingTransfers) {
}

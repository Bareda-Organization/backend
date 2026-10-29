package src.backend.run.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 정차 항목의 표시 이름 — 학생 승하차지 · 경유 지점 · 학원(도착지) 항목을 한 규칙으로 읽는다(API_SPEC §4.5 {@code next_stop}).
 */
@Component
@RequiredArgsConstructor
class RunStopLabels {

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final AcademyRepository academyRepository;

    /** 정차 항목의 이름 — 대상 행이 없으면 {@code null}. */
    String nameOf(Run run, RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(Stop::getName).orElse(null);
        }
        if (stop.getWaypointId() != null) {
            return waypointRepository.findById(stop.getWaypointId()).map(Waypoint::getLabel).orElse(null);
        }
        return academyRepository.findById(run.getAcademyId()).map(Academy::getName).orElse(null);
    }
}

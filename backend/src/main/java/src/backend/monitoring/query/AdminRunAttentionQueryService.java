package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.monitoring.dto.AdminRunAttentionResponse;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.AcademyRunCount;
import src.backend.run.repository.AcademyRunStatusCount;
import src.backend.run.repository.RunRepository;

/**
 * 전체 관제의 학원별 오늘 지연·확정 실패 집계(API_SPEC §6.15, Ruling 543) — 두 집계를 학원 식별자로 합쳐 문제가 있는
 * 학원만 {@code items} 에 싣고, 같은 값을 전 학원의 오늘 회차 상태별 수와 함께 {@code today} 에 싣는다(Ruling 805).
 * "오늘" 은 {@code GET /admin/academies/{id}/runs/live}(§6.8)와 같은 {@link Clock} 날짜다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRunAttentionQueryService {

    private final RunRepository runRepository;

    private final AcademyRepository academyRepository;

    private final Clock clock;

    /** 오늘 지연 회차 또는 확정 실패 회차가 있는 학원({@code items})과 전 학원의 오늘 요약({@code today}) — 둘 다 학원 식별자 순. */
    public AdminRunAttentionResponse list() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, Long> delayed = byAcademy(runRepository.countDelayedByAcademy(today));
        Map<Long, Long> confirmFailed = byAcademy(runRepository.countConfirmFailedByAcademy(today));

        Set<Long> academyIds = new TreeSet<>(delayed.keySet());
        academyIds.addAll(confirmFailed.keySet());
        List<AdminRunAttentionResponse.Item> items = academyIds.stream()
                .map(id -> new AdminRunAttentionResponse.Item(id, delayed.getOrDefault(id, 0L),
                        confirmFailed.getOrDefault(id, 0L)))
                .toList();
        return new AdminRunAttentionResponse(items, todayOf(today, delayed, confirmFailed));
    }

    private List<AdminRunAttentionResponse.Today> todayOf(LocalDate today, Map<Long, Long> delayed,
            Map<Long, Long> confirmFailed) {
        Map<Long, Map<RunStatus, Long>> countsByAcademy = runRepository.countByAcademyAndStatus(today).stream()
                .collect(Collectors.groupingBy(AcademyRunStatusCount::getAcademyId,
                        Collectors.toMap(AcademyRunStatusCount::getStatus, AcademyRunStatusCount::getRunCount)));
        return academyRepository.findAll(Sort.by("id")).stream()
                .map(academy -> todayRowOf(academy, countsByAcademy.getOrDefault(academy.getId(), new EnumMap<>(RunStatus.class)),
                        delayed.getOrDefault(academy.getId(), 0L), confirmFailed.getOrDefault(academy.getId(), 0L)))
                .toList();
    }

    private AdminRunAttentionResponse.Today todayRowOf(Academy academy, Map<RunStatus, Long> counts, long delayedRuns,
            long confirmFailedRuns) {
        AdminRunAttentionResponse.ByStatus byStatus = new AdminRunAttentionResponse.ByStatus(
                counts.getOrDefault(RunStatus.IDLE, 0L), counts.getOrDefault(RunStatus.CONFIRMED, 0L),
                counts.getOrDefault(RunStatus.MOVING, 0L), counts.getOrDefault(RunStatus.FINISHED, 0L));
        long runCount = byStatus.idle() + byStatus.confirmed() + byStatus.moving() + byStatus.finished();
        return new AdminRunAttentionResponse.Today(academy.getId(), academy.getName(),
                academy.getStatus().name().toLowerCase(Locale.ROOT), runCount, byStatus, delayedRuns,
                confirmFailedRuns);
    }

    private static Map<Long, Long> byAcademy(List<AcademyRunCount> rows) {
        return rows.stream().collect(Collectors.toMap(AcademyRunCount::getAcademyId, AcademyRunCount::getRunCount,
                Long::sum, java.util.HashMap::new));
    }
}

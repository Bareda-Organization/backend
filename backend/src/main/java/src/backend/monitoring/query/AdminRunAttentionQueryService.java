package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.monitoring.dto.AdminRunAttentionResponse;
import src.backend.run.repository.AcademyRunCount;
import src.backend.run.repository.RunRepository;

/**
 * 전체 관제의 학원별 오늘 지연·확정 실패 집계(API_SPEC §6.15, Ruling 543) — 두 집계를 학원 식별자로 합쳐 문제가 있는
 * 학원만 싣는다. "오늘" 은 {@code GET /admin/academies/{id}/runs/live}(§6.8)와 같은 {@link Clock} 날짜다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRunAttentionQueryService {

    private final RunRepository runRepository;

    private final Clock clock;

    /** 오늘 지연 회차 또는 확정 실패 회차가 있는 학원을 학원 식별자 순으로 돌려준다. */
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
        return new AdminRunAttentionResponse(items);
    }

    private static Map<Long, Long> byAcademy(List<AcademyRunCount> rows) {
        return rows.stream().collect(Collectors.toMap(AcademyRunCount::getAcademyId, AcademyRunCount::getRunCount,
                Long::sum, java.util.HashMap::new));
    }
}

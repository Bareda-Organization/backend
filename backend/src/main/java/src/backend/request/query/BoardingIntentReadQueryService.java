package src.backend.request.query;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.request.repository.BoardingIntentRepository;

/**
 * 학생 1명의 탑승 의사 읽기 전용 조회 — {@code student/query/StudentRunsQueryService} 가
 * {@code boarding_intent} 저장소를 직접 참조하지 않도록 두는 진입점이다(BR-094, ARCHITECTURE §3.3
 * 읽기 방향). 호출자가 조회 전용이라{@code query} 패키지에 둔다(§7 위반 없음 — Query→Query).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardingIntentReadQueryService {

    private final BoardingIntentRepository boardingIntentRepository;

    /**
     * 그 회차 그 학생의 탑승 의사 — 행이 없으면 기본값(탑승 ON · 한도 미사용)이다
     * ({@code BoardingIntentStatusReader.riderStatusOf}·{@code quotaLeftOf} 와 같은 기본값 규칙).
     */
    public Intent findIntent(Long runId, Long studentId) {
        return boardingIntentRepository.findByRunIdAndStudentId(runId, studentId)
                .map(intent -> new Intent(intent.isRiding(), intent.hasChangeQuota()))
                .orElse(new Intent(true, true));
    }

    /** 한 학생의 여러 회차 탑승 의사를 한 번의 질의로 읽는다 — 행이 없는 회차는 키가 없고 호출부가 {@link Intent#DEFAULT} 로 채운다. */
    public Map<Long, Intent> findIntents(Long academyId, Long studentId, Collection<Long> runIds) {
        Map<Long, Intent> intents = new HashMap<>();
        boardingIntentRepository.findAllByAcademyIdAndStudentIdAndRunIdIn(academyId, studentId, runIds).forEach(
                intent -> intents.put(intent.getRunId(), new Intent(intent.isRiding(), intent.hasChangeQuota())));
        return intents;
    }

    /** 탑승 의사(ON/OFF) · 이번 회차 변경 한도가 남았는가. */
    public record Intent(boolean riding, boolean hasChangeQuota) {

        /** 행이 없을 때의 기본값 — 탑승 ON · 한도 미사용. */
        public static final Intent DEFAULT = new Intent(true, true);
    }
}

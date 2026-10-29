package src.backend.global.retention;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.student.repository.StudentRepository;

/**
 * 퇴원 90일이 지난 학생의 개인정보 파기를 묶음 단위로 돌린다(Ruling 480 ②·520) — 보존 정리 배치({@link RetentionCleanupScheduler})가 같은 잠금 안에서 부른다.
 * 행을 지우지 않고 익명화하며({@link StudentAnonymizationService}), 파기에 실패한 학생은 같은 틱 안에서 다시 시도하지도 다시 세지도 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnStudentAnonymizer {

    /**
     * 퇴원 학생 파기의 묶음 크기 — 삭제용 {@link RetentionPolicy#BATCH_SIZE}(5,000)는 한 문장짜리 벌크 DELETE 기준이다.
     * 파기는 한 트랜잭션이 학생·계정 엔티티를 전부 적재하고 커밋 때 건별 UPDATE 를 쏟아내 묶음이 크면 연결을 오래 쥔다.
     */
    private static final int ANONYMIZATION_BATCH_SIZE = 200;

    private final StudentRepository studentRepository;

    private final StudentAnonymizationService studentAnonymizationService;

    private final RetentionTableCleaner tableCleaner;

    /**
     * 퇴원 90일이 지난 학생을 {@link #ANONYMIZATION_BATCH_SIZE}명씩 파기한다(BR-310 · BR-311) — 사진 파일을 못 지웠거나 처리에 실패해 파기되지
     * 못한 학생은 조회 조건이 그대로라 다음 틱에 다시 잡히므로, 건너뛴 학생 수만큼 실패 지표를 올린다. 한 회차에서 하나도 파기하지 못했으면
     * 같은 묶음을 되풀이하지 않고 멈춘다(다른 테이블 정리와 달리 파기 수가 조회 수와 같다는 보장이 없어, 상한만 보면 같은 묶음을 끝없이 돈다).
     * 실패는 {@link RetentionTableCleaner#cleanUpSafely} 와 같이 삼켜 다른 테이블 정리를 막지 않는다.
     *
     * <p>ponytail: 파기하지 못하는 학생이 한 묶음(200명)을 넘으면 그 학생들이 조회 앞쪽(id 오름차순)을 채워 뒤의 학생이 파기되지 못한다 — 지표가
     * 먼저 드러내므로 그때 조회에 id 커서를 더한다.
     */
    public void anonymizeSafely(OffsetDateTime cutoff) {
        try {
            int total = anonymizeBatches(cutoff);
            if (total > 0) {
                log.info("보존 정리 — student_anonymization {}행 처리", total);
            }
        } catch (Exception e) {
            log.warn("보존 정리 실패 — student_anonymization (다음 틱에 재시도)", e);
            tableCleaner.recordItemFailure();
        }
    }

    /** 대상이 바닥나거나 한 묶음에서 하나도 파기하지 못할 때까지 묶음을 되풀이하고 파기한 학생 수를 돌려준다. */
    private int anonymizeBatches(OffsetDateTime cutoff) {
        int total = 0;
        int fetched;
        int purged;
        Set<Long> stuck = new HashSet<>();
        do {
            List<Long> fetchedIds = studentRepository.findIdsForAnonymization(cutoff, Limit.of(ANONYMIZATION_BATCH_SIZE));
            fetched = fetchedIds.size();
            // 파기되지 못한 학생은 조회 조건이 그대로라 다음 조회에 다시 잡힌다 — 파기에 빠진 묶음이 있었다면 그 묶음의 학생은 이번 틱에 다시 시도하지도 다시 세지도 않는다
            List<Long> ids = fetchedIds.stream().filter(id -> !stuck.contains(id)).toList();
            purged = ids.isEmpty() ? 0 : studentAnonymizationService.anonymize(ids);
            total += purged;
            if (purged < ids.size()) {
                stuck.addAll(ids);
            }
            for (int skipped = ids.size() - purged; skipped > 0; skipped--) {
                tableCleaner.recordItemFailure();
            }
        } while (fetched == ANONYMIZATION_BATCH_SIZE && purged > 0);
        return total;
    }
}

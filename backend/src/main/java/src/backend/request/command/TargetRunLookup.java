package src.backend.request.command;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.entity.Student;

/**
 * 학부모가 지목한 회차가 그 자녀의 대상 회차인지 판정한다(API_SPEC §1.11 — 대상이 아니면 존재를 숨겨
 * {@code 404 RUN_NOT_FOUND}, BR-084). 탑승 의사 토글(§3.6)과 변경 신청(§3.8)이 같은 기준을 쓴다.
 */
@Component
@RequiredArgsConstructor
class TargetRunLookup {

    private final RunRepository runRepository;

    private final StudentRunResolver studentRunResolver;

    private final BoardingIntentRepository boardingIntentRepository;

    /**
     * 대상 = 확정 전 고정 노선·확정 후 명단에 있는 학생({@link StudentRunResolver#belongsTo}) 또는 그 회차의
     * 탑승 의사를 끈 학생 — ①구간 OFF 학생은 확정 명단에서 빠지지만 여전히 그 회차의 대상이다.
     */
    Run targetRun(Student student, Long runId) {
        Run run = runRepository.findByIdAndAcademyId(runId, student.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        boolean ridingOff = boardingIntentRepository.findByRunIdAndStudentId(run.getId(), student.getId())
                .filter(intent -> !intent.isRiding())
                .isPresent();
        if (!ridingOff && !studentRunResolver.belongsTo(run, student.getId())) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }
}

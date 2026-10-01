package src.backend.boarding.query;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.audit.service.AuditRecorder;
import src.backend.boarding.dto.GuardianPhoneResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.student.repository.GuardianPhone;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 매니저 앱의 보호자 전화 — 명단은 마스킹(L2)을 유지하고 [전화] 를 누를 때만 그 탑승 학생 1명의 보호자 원번호를 준다
 * (Ruling 482·521, API_SPEC §4.2.1).
 *
 * <p>인가는 명단({@link RosterQueryService#managerRoster})과 같은 순서다 — 회차 접근({@link ManagerRunAccess#requireAssignedRun},
 * 없음·타 학원·미배치 모두 {@code 403 FORBIDDEN})을 먼저 보고, 그 회차 명단에 없는 탑승자(없는 id · 다른 회차의 탑승자 ·
 * 명단에서 빠진 {@code absent})는 {@code 404 RIDER_NOT_FOUND} 다(§4.6 과 같은 코드·같은 합류).
 *
 * <p>번호가 응답에 실렸을 때만 감사 1행을 남긴다(SYS-01 · Ruling 333) — 대상은 그 학생, {@code fields} 는
 * {@code guardian_phone}. 같은 매니저·학생·필드의 10분 안 재호출은 묶이고({@link AuditRecorder}, Ruling 445) 명단 조회가 먼저
 * 창을 열었어도 이 접근은 따로 기록된다(묶기 키에 {@code fields} 가 들어 있다). 학생에게 보호자가 여럿이면 명단·관계자 웹과 같은
 * 정렬의 첫 번호다. 클래스에 {@code @Transactional} 을 두지 않는 이유는 {@link RosterQueryService} 와 같다(감사 {@code REQUIRES_NEW}).
 */
@Service
@RequiredArgsConstructor
public class GuardianPhoneQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final RunRiderRepository runRiderRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AuditRecorder auditRecorder;

    public GuardianPhoneResponse guardianPhone(AuthUser requester, Long runId, Long riderId) {
        managerRunAccess.requireAssignedRun(requester, runId);
        RunRider rider = runRiderRepository.findAllByRunIdAndAcademyId(runId, requester.academyId()).stream()
                .filter(candidate -> candidate.getId().equals(riderId) && candidate.getStatus() != RiderStatus.ABSENT)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDER_NOT_FOUND));
        String phone = guardianStudentRepository
                .findGuardianPhonesByAcademyId(requester.academyId(), List.of(rider.getStudentId())).stream()
                .findFirst()
                .map(GuardianPhone::getPhone)
                .orElse(null);
        if (phone != null) {
            auditRecorder.recordDataAccessRead(requester.academyId(), requester.accountId(), "student",
                    rider.getStudentId(),
                    Map.of("student_ids", List.of(String.valueOf(rider.getStudentId())), "fields",
                            List.of("guardian_phone")));
        }
        return new GuardianPhoneResponse(phone);
    }
}

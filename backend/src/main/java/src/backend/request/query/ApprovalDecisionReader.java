package src.backend.request.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.entity.ChangeRequest;

/** 승인 상세에 붙는 결정·배치 인력 묶음({@link ApprovalDetailResponse.Decision}) 조회. */
@Component
@RequiredArgsConstructor
class ApprovalDecisionReader {

    private final AccountRepository accountRepository;

    private final AssignmentRepository assignmentRepository;

    /**
     * 상세에 붙는 결정·배치 인력 묶음(Ruling 812) — 결정 시각·결정자는 요청 행 그대로({@code 대기} 는 둘 다 null, 자동 거절은
     * 결정자 없음), 기사·동승자 이름은 그 회차의 현재 배치다(배치 전이면 null). 학원 조건은 두 조회에 각각 건다.
     */
    ApprovalDetailResponse.Decision decisionOf(ChangeRequest cr, Long runId, Long academyId) {
        String decidedByName = cr.getDecidedBy() == null ? null
                : accountRepository.findByIdAndAcademyId(cr.getDecidedBy(), academyId).map(Account::getName)
                        .orElse(null);
        Map<ManagerRole, String> assignedNames = assignmentRepository.findAssignedManagers(academyId, List.of(runId))
                .stream().collect(Collectors.toMap(AssignedManagerView::role, AssignedManagerView::name,
                        (first, duplicate) -> first));
        return new ApprovalDetailResponse.Decision(cr.getStatus().name().toLowerCase(Locale.ROOT), cr.getDecidedAt(),
                decidedByName, assignedNames.get(ManagerRole.DRIVER), assignedNames.get(ManagerRole.ESCORT));
    }

}

package src.backend.request.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.request.dto.ChangeRequestListResponse;
import src.backend.request.dto.ChangeRequestResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.entity.Student;

/**
 * 학부모의 변경 신청 상태 조회(REQ-03, API_SPEC §3.9) — 신청 이력을 접수 역순으로 보여준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChangeRequestQueryService {

    private final LinkedChildLookup linkedChildLookup;

    private final ChangeRequestRepository changeRequestRepository;

    /** 연결된 자녀의 변경 신청 이력 전부 + 대기 중 건수(홈 배지용, §3.9). */
    public ChangeRequestListResponse list(AuthUser requester, Long studentId) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        List<ChangeRequest> changeRequests = changeRequestRepository
                .findAllByAcademyIdAndStudentIdOrderByRequestedAtDesc(student.getAcademyId(), student.getId());
        long pendingCount = changeRequests.stream()
                .filter(changeRequest -> changeRequest.getStatus() == ChangeRequestStatus.PENDING)
                .count();
        List<ChangeRequestResponse> items = changeRequests.stream().map(ChangeRequestResponse::from).toList();
        return new ChangeRequestListResponse(items, pendingCount);
    }
}

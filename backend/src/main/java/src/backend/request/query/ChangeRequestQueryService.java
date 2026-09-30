package src.backend.request.query;

import java.util.List;

import org.springframework.data.domain.PageRequest;
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

    /** 이력 조회 상한 — 사양(§3.9)에 페이징이 없어 최근 이 건수까지만 싣는다(BR-251). */
    static final int HISTORY_LIMIT = 100;

    /** 연결된 자녀의 최근 변경 신청 이력({@value #HISTORY_LIMIT}건까지) + 전체 대기 중 건수(홈 배지용, §3.9). */
    public ChangeRequestListResponse list(AuthUser requester, Long studentId) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        List<ChangeRequest> changeRequests = changeRequestRepository
                .findByAcademyIdAndStudentIdOrderByRequestedAtDesc(student.getAcademyId(), student.getId(),
                        PageRequest.of(0, HISTORY_LIMIT));
        long pendingCount = changeRequestRepository.countByAcademyIdAndStudentIdAndStatus(student.getAcademyId(),
                student.getId(), ChangeRequestStatus.PENDING);
        List<ChangeRequestResponse> items = changeRequests.stream().map(ChangeRequestResponse::from).toList();
        return new ChangeRequestListResponse(items, pendingCount);
    }
}

package src.backend.account.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.SignupDecisionPayload;
import src.backend.account.dto.SignupDecisionResponse;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;

/**
 * 관계자가 처리하는 가입 승인(AUTH-10·11, API_SPEC §5.2) — 학부모·학생·기사·동승자가 대상이다.
 *
 * <p>메인 관리자 축({@link StaffApprovalCommandService})과 나눠 둔 이유는 <b>갈리는 것이 전부
 * 인가 경계</b>이기 때문이다 — 대상 역할 · 학원 격리 · 레코드 연결(AUTH-11)이 이 축에만 있고,
 * 정원 판정이 저쪽에만 있다. 한 클래스에 두면 조건문 하나를 잘못 지웠을 때 관계자가 자기 후임을
 * 승인하거나 메인 관리자가 연결 없는 학부모 계정을 활성화하게 된다.
 */
@Service
@RequiredArgsConstructor
public class SignupApprovalCommandService {

    private final SignupRequestRepository signupRequestRepository;

    private final SignupAccountLinker signupAccountLinker;

    private final SignupDecision signupDecision;

    /**
     * 가입 요청을 수락·거절한다(§5.2).
     *
     * <p>세 관문을 이 순서로 지난다. ①존재({@code 404 SIGNUP_REQUEST_NOT_FOUND}) ②소속 학원
     * ({@code 403 ACADEMY_SCOPE_VIOLATION}, §1.5) ③승인 주체({@code 403 FORBIDDEN}, §5.2).
     * ②를 ③보다 앞에 두는 이유는, 뒤에 두면 타 학원의 <b>관계자</b> 요청에만 다른 코드가 나가
     * 응답 코드 하나로 남의 학원에 어떤 종류의 요청이 있는지 알 수 있게 되기 때문이다.
     */
    @Transactional
    public SignupDecisionResponse decide(AuthUser requester, Long requestId, SignupDecisionPayload payload) {
        SignupRequest request = signupRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_REQUEST_NOT_FOUND));
        AcademyScope.assertAccessible(requester, request.getAcademyId());
        assertApprovedByStaff(request);

        return signupDecision.close(request, new Decision(payload.accept(), payload.rejectReason()),
                requester.accountId(), account -> signupAccountLinker.link(account, payload.link()));
    }

    /**
     * {@code role=staff} 요청의 승인 주체는 메인 관리자다(§5.2 · C-01) — 이 경로에서는 {@code 403 FORBIDDEN}.
     *
     * <p>역할이 아니라 {@code approver_type} 을 보는 이유는 그것이 가입 시점에 확정돼 행에 적혀 있는
     * 값이라, 승인 주체 규칙이 바뀌어도 목록 조회와 이 판정이 같은 근거를 보기 때문이다.
     */
    private void assertApprovedByStaff(SignupRequest request) {
        if (request.getApproverType() != ApproverType.STAFF) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}

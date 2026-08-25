package src.backend.account.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.AcademyStaffQuota;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.dto.SignupDecisionResponse;
import src.backend.account.dto.StaffDecisionPayload;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 메인 관리자가 처리하는 관계자 가입 승인(ACAD-05, API_SPEC §6.5) — {@code role=staff} 만 대상이다.
 *
 * <p>이 축의 "연결" 은 요청 본문이 지정하는 레코드가 아니라 승인이 만드는 {@code academy_staff} 행
 * 자체다. 그래서 {@link SignupApprovalCommandService} 와 달리 {@code link} 를 받지 않고, 대신
 * 학원당 1명 정원({@link AcademyStaffQuota})이 붙는다.
 *
 * <p>전 학원 범위라 학원 격리 대조가 부재하다(§1.5 예외) — 열어 주는 판정은 컨트롤러의
 * {@code @CanApproveStaff} 한 곳이다.
 */
@Service
@RequiredArgsConstructor
public class StaffApprovalCommandService {

    private final SignupRequestRepository signupRequestRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AcademyStaffQuota academyStaffQuota;

    private final SignupDecision signupDecision;

    /**
     * 관계자 가입 요청을 수락·거절한다(§6.5).
     *
     * <p>관계자 축이 아닌 요청({@code approver_type='staff'})은 {@code 404 SIGNUP_REQUEST_NOT_FOUND}
     * 다 — 이 콘솔의 목록(§6.4)에 애초에 실리지 않는 건이라 "없는 요청" 과 같이 답한다. 통과시키면
     * 메인 관리자가 학부모 요청을 <b>레코드 연결 없이</b> 승인해 AUTH-11 이 통째로 빠진 활성 계정이 생긴다.
     */
    @Transactional
    public SignupDecisionResponse decide(Long deciderId, Long requestId, StaffDecisionPayload payload) {
        SignupRequest request = signupRequestRepository.findById(requestId)
                .filter(candidate -> candidate.getApproverType() == ApproverType.SYSTEM_ADMIN)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_REQUEST_NOT_FOUND));

        return signupDecision.close(request, new Decision(payload.accept(), payload.rejectReason()), deciderId,
                account -> grantStaffSeat(request.getAcademyId(), account.getId()));
    }

    /**
     * 관계자 자리를 준다 — 정원 판정을 {@link AcademyStaffQuota} 가 겸한다(§6.5 {@code STAFF_QUOTA_EXCEEDED}).
     *
     * <p>여기서 재직자 수를 직접 세지 않는 이유는 선검사만으로는 동시 요청 2건을 막지 못하고, 그
     * 나머지 절반(DB 조건부 UNIQUE 위반을 409 로 옮기는 것)이 그 클래스 안에 함께 있기 때문이다.
     */
    private void grantStaffSeat(Long academyId, Long accountId) {
        academyStaffQuota.enforce(academyId,
                () -> academyStaffRepository.save(AcademyStaff.uponApproval(academyId, accountId)));
    }
}

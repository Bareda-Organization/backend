package src.backend.academy.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.StaffAccountDetailResponse;
import src.backend.academy.dto.StaffAccountUpdateRequest;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 관계자 계정 관리(ACAD-06 · O-02, API_SPEC §6.7) — 정보 수정 · 비밀번호 초기화 · 퇴사·재직 전환.
 *
 * <p><b>이 서비스가 지는 계약은 "접근을 끊는 경로는 refresh 토큰을 함께 무효화한다" 이다.</b>
 * {@code RefreshCommandService} 는 {@code Account.assertNotBlocked()} 를 의도적으로 호출하지 않으며,
 * 그 근거를 "접근을 끊는 모든 경로가 이미 토큰을 무효화한다" 에 두고 있다(그 클래스 주석). 퇴사·초기화가
 * 무효화를 빠뜨리면 그 근거가 거짓이 되고 <b>응답은 200 그대로라 어떤 응답 단언도 걸리지 않는다.</b>
 *
 * <p>무효화 대상은 <b>접근 범위를 좁히는 두 경로만</b>이다.
 * <ul>
 *   <li>{@code status=inactive}(퇴사) — 권한을 회수하는 조치라 기존 세션이 남으면 회수가 성립하지 않는다</li>
 *   <li>{@code reset_password}(초기화) — 옛 비밀번호로 얻은 세션이 살아 있으면 초기화의 목적이 소멸한다.
 *       비밀번호 <b>변경</b>(§2.8)이 이미 같은 처리를 한다</li>
 * </ul>
 * 이름·연락처·이메일 수정과 {@code status=active}(재직 복귀)는 대상이 <b>아니다</b> — 접근 범위를
 * 좁히지 않으므로, 무효화하면 관리자가 오타 하나를 고칠 때마다 그 관계자가 재로그인을 요구받는다.
 */
@Service
@RequiredArgsConstructor
public class StaffAccountCommandService {

    private final AccountRepository accountRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AcademyRepository academyRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final AcademyStaffQuota academyStaffQuota;

    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    private final PasswordEncoder passwordEncoder;

    private final Clock clock;

    /**
     * 관계자 계정을 고친다 — 미존재 계정·관계자 행 부재는 {@code 404 ACCOUNT_NOT_FOUND}(§6.7).
     *
     * <p>순서가 계약의 일부다. ①엔티티 변경 ②정원 판정 ③토큰 무효화 순으로 하며 <b>무효화가 마지막</b>이다.
     * {@code revokeAllValidByAccountId} 는 {@code clearAutomatically} 로 영속성 컨텍스트를 비우므로,
     * 그 뒤에 엔티티를 고치면 그 변경이 관리 대상 밖에서 일어나 <b>조용히 사라진다.</b>
     *
     * <p>{@code academy_staff} 행이 없는 계정(승인 대기 중인 관계자)을 {@code 404} 로 돌려보내는 이유는,
     * 그 계정은 아직 어느 학원의 관계자도 아니라 퇴사·재직을 말할 대상 자체가 부재하기 때문이다 —
     * 그 축은 §6.4 승인 큐가 맡는다.
     */
    @Transactional
    public StaffAccountDetailResponse update(Long accountId, StaffAccountUpdateRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        AcademyStaff staff = academyStaffRepository.findByAccountId(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        account.changeProfile(request.name(), request.phone(), request.email());
        String temporaryPassword = resetPasswordIfRequested(account, request);
        boolean employmentRevoked = applyEmploymentStatus(staff, request.status());

        if (temporaryPassword != null || employmentRevoked) {
            refreshTokenRepository.revokeAllValidByAccountId(accountId, OffsetDateTime.now(clock));
        }
        return StaffAccountDetailResponse.from(account, academyName(staff.getAcademyId()), staff.getStatus(),
                temporaryPassword);
    }

    /** 초기화를 요청했으면 새 원문을 만들어 해시만 저장하고 원문을 돌려준다 — 아니면 {@code null}. */
    private String resetPasswordIfRequested(Account account, StaffAccountUpdateRequest request) {
        if (!request.wantsPasswordReset()) {
            return null;
        }
        String temporaryPassword = temporaryPasswordGenerator.generate();
        account.changePassword(passwordEncoder.encode(temporaryPassword));
        return temporaryPassword;
    }

    /**
     * 재직 상태를 옮긴다 — {@code active} 전환은 정원 판정을 거친다({@link AcademyStaffQuota}).
     *
     * <p>정원 판정을 여기서 직접 세지 않고 {@code AcademyStaffQuota} 에 맡기는 이유는 그것이 승인
     * 경로(§6.5)와 <b>같은 판정</b>이어서다. 복제하면 상한 규칙이 한 번 바뀔 때 한쪽만 따라가고, 그
     * 순간 정원이 새는데 양쪽 테스트는 각자 자기 사본을 보며 계속 통과한다.
     *
     * @return 이 전환이 접근을 <b>끊는</b> 방향이면 참 — 호출부가 refresh 무효화 여부를 이 값으로 정한다
     */
    private boolean applyEmploymentStatus(AcademyStaff staff, String requestedStatus) {
        if (requestedStatus == null) {
            return false;
        }
        StaffStatus target = StaffStatus.valueOf(requestedStatus.toUpperCase(Locale.ROOT));
        if (target == staff.getStatus()) {
            return false;
        }
        if (target == StaffStatus.ACTIVE) {
            academyStaffQuota.enforce(staff.getAcademyId(), () -> {
                staff.reinstate();
                return staff;
            });
            return false;
        }
        staff.resign();
        return true;
    }

    /** 학원 이름은 응답 표시값이다 — 학원이 사라진 상태는 FK(RESTRICT)가 막으므로 정상 상태에서는 항상 있다. */
    private String academyName(Long academyId) {
        return academyRepository.findById(academyId).map(Academy::getName).orElse(null);
    }
}

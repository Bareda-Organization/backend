package src.backend.user.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.dto.StudentResponse;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.user.dto.MemberDetailResponse;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/** 구성원(학원 멤버십) 목록·상세 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. */
@Service
public class MemberQueryService {

    private final UserTenantRoleRepository userTenantRoleRepository;
    private final BusRepository busRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public MemberQueryService(UserTenantRoleRepository userTenantRoleRepository,
                              BusRepository busRepository,
                              StudentGuardianRepository studentGuardianRepository) {
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.busRepository = busRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(AuthUser admin, Long tenantId, Role role) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<UserTenantRole> memberships = (role == null)
                ? userTenantRoleRepository.findByTenantId(effectiveTenant)
                : userTenantRoleRepository.findByTenantIdAndRole(effectiveTenant, role);
        return memberships.stream().map(MemberResponse::of).toList();
    }

    /**
     * 구성원 상세 — 멤버십1 + 버스2 + 보호자1 = 쿼리 4회 고정.
     * 참조 현황(배정 버스·담당 학생)을 함께 담아 화면이 "지울 수 있는가"를 한 번에 판단하게 한다(I-7).
     */
    @Transactional(readOnly = true)
    public MemberDetailResponse get(AuthUser admin, Long userId, Long tenantId) {
        UserTenantRole membership = loadAccessibleMembership(admin, userId, tenantId);
        Long effectiveTenant = membership.getTenant().getId();
        List<Bus> asDriver = busRepository.findByDriverIdAndTenantId(userId, effectiveTenant);
        List<Bus> asAttendant = busRepository.findByAttendantIdAndTenantId(userId, effectiveTenant);
        List<StudentGuardian> guarded = studentGuardianRepository.findByGuardianId(userId);
        return MemberDetailResponse.of(membership, asDriver, asAttendant, guarded);
    }

    /**
     * 학부모 기준 역방향 자녀 조회 — 학생↔학부모 연결을 학부모 쪽에서 본다.
     *
     * <p>⚠️ 비활성(퇴원) 학생을 걸러내지 않는다. 관리자가 "이 학부모와 연결된 전부"를 봐야 연결을 정리할 수 있다
     * (I-9 는 명단·배차·시뮬레이션에 걸리는 규칙이지 관계 조회가 아니다). 화면이 배지로 구분한다.
     */
    @Transactional(readOnly = true)
    public List<StudentResponse> studentsOf(AuthUser admin, Long guardianUserId, Long tenantId) {
        UserTenantRole membership = loadAccessibleMembership(admin, guardianUserId, tenantId);
        Long effectiveTenant = membership.getTenant().getId();
        return studentGuardianRepository.findByGuardianId(guardianUserId).stream()
                // 형제가 다른 학원에 다닐 수 있다 — 요청자 학원 것만 돌려준다.
                .filter(sg -> sg.getStudent().getTenant().getId().equals(effectiveTenant))
                .map(sg -> StudentResponse.of(sg.getStudent()))
                .toList();
    }

    /**
     * 요청자 학원의 구성원만 통과시킨다 — 여기가 이 서비스의 유일한 격리 지점이다.
     * ⚠️ userRepository.findById 로 시작하지 않는다: 멤버십을 먼저 찾는 순서가 격리를 구조적으로 보장한다.
     * 다른 학원 구성원은 403 이 아니라 404 다 — 존재 여부조차 알려주지 않는다.
     *
     * <p>tenantId 는 학원 관리자에겐 생략 가능(본인 학원)하고 지정하면 소속 여부를 검증받는다.
     * 플랫폼 관리자는 소속 학원이 없어 <b>반드시 지정</b>해야 한다 — 안 그러면 대상 학원을 정할 수 없다.
     */
    private UserTenantRole loadAccessibleMembership(AuthUser admin, Long userId, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return userTenantRoleRepository.findByUserIdAndTenantId(userId, effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 학원의 구성원이 아닙니다"));
    }
}

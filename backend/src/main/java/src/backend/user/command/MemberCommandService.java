package src.backend.user.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.dto.CreateMemberRequest;
import src.backend.user.dto.MemberResponse;
import src.backend.user.dto.ResetPasswordRequest;
import src.backend.user.dto.UpdateMemberRequest;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 구성원(계정+학원 멤버십) 등록·수정·비밀번호 재설정·멤버십 해제 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다.
 * self-service 가입(AuthCommandService.signup)과 달리, 관리자가 자기 학원 구성원을 프로비저닝하는 경로다.
 *
 * <p>이 서비스는 남의 비밀번호를 바꾸고 역할을 올릴 수 있는 자리라 금지 규칙 4개를 명시적으로 건다 —
 * 학원 격리(멤버십 우선 조회)·본인 수정/해제 금지·PLATFORM_ADMIN 승격 금지·관리자 비번 재설정 금지.
 */
@Service
public class MemberCommandService {

    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final TenantRepository tenantRepository;
    private final BusRepository busRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberCommandService(UserRepository userRepository,
                                UserTenantRoleRepository userTenantRoleRepository,
                                TenantRepository tenantRepository,
                                BusRepository busRepository,
                                StudentGuardianRepository studentGuardianRepository,
                                PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.tenantRepository = tenantRepository;
        this.busRepository = busRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public MemberResponse register(AuthUser admin, CreateMemberRequest req) {
        if (req.role() == Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "플랫폼 관리자는 이 경로로 등록할 수 없습니다");
        }
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, req.tenantId());
        Tenant tenant = tenantRepository.findById(effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        User user = userRepository.save(User.builder()
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(req.phone())
                .build());
        UserTenantRole membership = userTenantRoleRepository.save(UserTenantRole.builder()
                .user(user).tenant(tenant).role(req.role()).build());
        return MemberResponse.of(membership);
    }

    /** 이름·전화·사진·이메일·역할 수정. 전달된 필드만 갱신한다(null = 그대로). */
    @Transactional
    public MemberResponse update(AuthUser admin, Long userId, Long tenantId, UpdateMemberRequest req) {
        UserTenantRole membership = loadAccessibleMembership(admin, userId, tenantId);
        // ① 자기 자신은 이 경로로 못 고친다. 관리자가 스스로를 PARENT 로 바꾸면
        //    그 순간 화면에서 쫓겨나고 되돌릴 권한도 사라진다.
        if (userId.equals(admin.userId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "본인 계정은 이 화면에서 수정할 수 없습니다");
        }
        // ② 권한 상승 차단 — register 와 같은 규칙이다.
        if (req.role() == Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "플랫폼 관리자로 변경할 수 없습니다");
        }
        User user = membership.getUser();
        // ③ 이메일은 로그인 ID다 — 바뀔 때만 중복 검사한다(자기 이메일 그대로면 통과해야 한다).
        //    ⚠️ @Email 은 빈 문자열을 통과시킨다 — 빈 값을 그대로 쓰면 로그인 ID가 지워져 계정이 잠긴다.
        String newEmail = blankToNull(req.email());
        if (newEmail != null && !newEmail.equals(user.getEmail())) {
            if (userRepository.existsByEmail(newEmail)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            user.changeEmail(newEmail);
        }
        // null 은 "그대로". 이름도 not-null 컬럼이라 빈 문자열은 "미전달"로 본다
        // (사진·전화는 nullable 이라 빈 문자열로 지우는 것이 유효한 요청이다).
        user.updateProfile(blankToNull(req.name()), req.phone(), req.photoUrl());
        if (req.role() != null && req.role() != membership.getRole()) {
            ensureRoleNotAlreadyHeld(membership, req.role());
            membership.changeRole(req.role());
        }
        return MemberResponse.of(membership);
    }

    /**
     * 관리자 강제 비밀번호 재설정 — 응답에 새 비밀번호를 되돌려주지 않는다(평문이 로그·캐시에 남는다).
     * ⚠️ 이미 발급된 JWT 는 즉시 만료되지 않는다(토큰 무효화 장치가 없다).
     */
    @Transactional
    public void resetPassword(AuthUser admin, Long userId, Long tenantId, ResetPasswordRequest req) {
        UserTenantRole membership = loadAccessibleMembership(admin, userId, tenantId);
        // 학원 관리자끼리 서로의 비밀번호를 바꿀 수 있으면 계정 탈취 경로가 된다.
        // 이 API 가 다루는 대상은 "관리자가 대신 관리해 주는 계정"뿐이다.
        if (membership.getRole() == Role.ACADEMY_ADMIN || membership.getRole() == Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관리자 계정의 비밀번호는 이 경로로 바꿀 수 없습니다");
        }
        membership.getUser().changePassword(passwordEncoder.encode(req.newPassword()));
    }

    /**
     * 학원 멤버십 해제 — app_user 행은 남긴다(D-O·I-8).
     * 버스 배정·보호자 연결이 남아 있으면 CONFLICT 로 거부하고 <b>무엇이 막는지</b>까지 알려준다(I-7).
     */
    @Transactional
    public void removeMembership(AuthUser admin, Long userId, Long tenantId) {
        UserTenantRole membership = loadAccessibleMembership(admin, userId, tenantId);
        if (userId.equals(admin.userId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "본인 계정은 해제할 수 없습니다");
        }
        Long effectiveTenant = membership.getTenant().getId();
        List<String> blockers = new ArrayList<>();
        busRepository.findByDriverIdAndTenantId(userId, effectiveTenant)
                .forEach(b -> blockers.add(b.getName() + " 기사"));
        busRepository.findByAttendantIdAndTenantId(userId, effectiveTenant)
                .forEach(b -> blockers.add(b.getName() + " 선탑자"));
        studentGuardianRepository.findByGuardianId(userId).stream()
                // 형제가 다른 학원에 다닐 수 있다 — 이 학원의 연결만 해제를 막는다.
                .filter(sg -> sg.getStudent().getTenant().getId().equals(effectiveTenant))
                .forEach(sg -> blockers.add(sg.getStudent().getName() + " 보호자"));
        if (!blockers.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "먼저 다음 연결을 해제해야 합니다: " + String.join(", ", blockers));
        }
        userTenantRoleRepository.delete(membership);   // app_user 행은 남긴다(D-O)
    }

    /**
     * 요청자 학원의 구성원만 통과시킨다 — 여기가 이 서비스의 유일한 격리 지점이다.
     * ⚠️ userRepository.findById 로 시작하지 않는다. 그러면 다른 학원 사용자도 찾아지고,
     * 뒤에 붙이는 검사를 한 번이라도 빠뜨리면 전 학원 계정 조회기가 된다.
     */
    private UserTenantRole loadAccessibleMembership(AuthUser admin, Long userId, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return userTenantRoleRepository.findByUserIdAndTenantId(userId, effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 학원의 구성원이 아닙니다"));
    }

    /** 공백만 있는 값은 "미전달"로 본다 — not-null 컬럼(email·name)을 빈 값으로 덮지 않기 위해서다. */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * unique(user_id, tenant_id, role) 위반을 500 대신 409 로 돌려준다 —
     * 같은 사람이 그 학원에서 이미 그 역할의 멤버십을 따로 갖고 있는 경우다.
     */
    private void ensureRoleNotAlreadyHeld(UserTenantRole membership, Role newRole) {
        Long tenantId = membership.getTenant() != null ? membership.getTenant().getId() : null;
        boolean duplicated = userTenantRoleRepository.findByUserId(membership.getUser().getId()).stream()
                .filter(other -> !other.getId().equals(membership.getId()))
                .anyMatch(other -> other.getRole() == newRole
                        && other.getTenant() != null && other.getTenant().getId().equals(tenantId));
        if (duplicated) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 그 역할의 멤버십을 갖고 있습니다");
        }
    }
}

package src.backend.user.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.dto.ResetPasswordRequest;
import src.backend.user.dto.UpdateMemberRequest;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 구성원 수정·비밀번호 재설정·멤버십 해제 단위 테스트 (BE-12).
 *
 * <p>이 서비스는 남의 비밀번호를 바꾸고 역할을 올릴 수 있는 자리라, 검증하는 것은 "정상 동작"보다
 * <b>금지 규칙 4개</b>다 — 학원 격리(멤버십 우선 조회)·본인 수정 금지·권한 상승 금지·관리자 비번 재설정 금지.
 * 여기에 참조 무결성(I-7)과 물리 삭제 금지(I-8/D-O)를 더한다.
 */
class MemberCommandServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserTenantRoleRepository userTenantRoleRepository = mock(UserTenantRoleRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final MemberCommandService service = new MemberCommandService(
            userRepository, userTenantRoleRepository, tenantRepository,
            busRepository, studentGuardianRepository, passwordEncoder);

    private static final Long TENANT_ID = 1L;
    private static final Long ADMIN_ID = 100L;
    private static final Long TARGET_ID = 7L;

    /**
     * 다른 학원 구성원은 존재 여부조차 알려주지 않는다 — 멤버십을 먼저 찾으므로 NOT_FOUND(404)다.
     * (403 이면 "그 id 의 계정은 있다"는 사실이 새어 나간다)
     */
    @Test
    void update_otherTenantMember_throwsNotFound() {
        given(userTenantRoleRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(admin(), TARGET_ID, profileOnly("새이름")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void update_roleToPlatformAdmin_throwsInvalidInput() {
        givenMembership(TARGET_ID, Role.DRIVER);

        assertThatThrownBy(() -> service.update(admin(), TARGET_ID,
                new UpdateMemberRequest(null, null, null, null, Role.PLATFORM_ADMIN)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 관리자가 스스로를 PARENT 로 강등하면 그 순간 되돌릴 권한까지 사라진다. */
    @Test
    void update_self_throwsInvalidInput() {
        givenMembership(ADMIN_ID, Role.ACADEMY_ADMIN);

        assertThatThrownBy(() -> service.update(admin(), ADMIN_ID,
                new UpdateMemberRequest(null, null, null, null, Role.PARENT)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 이메일이 그대로면 중복 검사를 하지 않는다 — 자기 이메일에 걸려 수정이 막히면 안 된다. */
    @Test
    void update_emailUnchanged_doesNotCheckDuplicate() {
        UserTenantRole membership = givenMembership(TARGET_ID, Role.DRIVER);

        service.update(admin(), TARGET_ID,
                new UpdateMemberRequest("driver@school.com", "정기사", null, null, null));

        verify(userRepository, never()).existsByEmail(anyString());
        assertThat(membership.getUser().getName()).isEqualTo("정기사");
        assertThat(membership.getUser().getPhone()).isEqualTo("010-1111-2222"); // null 은 "그대로"
    }

    /**
     * @Email·@NotBlank 없는 빈 문자열이 로그인 ID(email)와 이름을 지우면 계정이 잠긴다 —
     * 두 컬럼은 not-null 이라 빈 값은 "미전달"로 본다.
     */
    @Test
    void update_blankEmailAndName_areTreatedAsNotProvided() {
        UserTenantRole membership = givenMembership(TARGET_ID, Role.DRIVER);

        service.update(admin(), TARGET_ID, new UpdateMemberRequest("", "  ", null, null, null));

        assertThat(membership.getUser().getEmail()).isEqualTo("driver@school.com");
        assertThat(membership.getUser().getName()).isEqualTo("김기사");
        verify(userRepository, never()).existsByEmail(anyString());
    }

    /** 학원 관리자끼리 서로의 비밀번호를 바꿀 수 있으면 계정 탈취 경로가 된다. */
    @Test
    void resetPassword_targetIsAdmin_throwsInvalidInput() {
        givenMembership(TARGET_ID, Role.ACADEMY_ADMIN);

        assertThatThrownBy(() -> service.resetPassword(admin(), TARGET_ID, new ResetPasswordRequest("newpassword")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 저장되는 값은 반드시 인코딩된 해시다(평문이 DB·응답 어디에도 남지 않는다). */
    @Test
    void resetPassword_targetIsDriver_storesEncodedPassword() {
        UserTenantRole membership = givenMembership(TARGET_ID, Role.DRIVER);
        given(passwordEncoder.encode("newpassword")).willReturn("{bcrypt}hashed");

        service.resetPassword(admin(), TARGET_ID, new ResetPasswordRequest("newpassword"));

        assertThat(membership.getUser().getPassword()).isEqualTo("{bcrypt}hashed");
    }

    /** I-7 — 버스에 기사로 배정된 사람은 해제할 수 없고, 무엇이 막는지 메시지로 알려준다. */
    @Test
    void removeMembership_userAssignedAsDriver_throwsConflict() {
        givenMembership(TARGET_ID, Role.DRIVER);
        given(busRepository.findByDriverIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(List.of(bus(1L, "3호차")));

        assertThatThrownBy(() -> service.removeMembership(admin(), TARGET_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3호차")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /** I-8 · D-O — 지우는 것은 멤버십 행뿐이다. app_user 는 남는다. */
    @Test
    void removeMembership_noReference_deletesOnlyMembership() {
        UserTenantRole membership = givenMembership(TARGET_ID, Role.PARENT);
        given(busRepository.findByDriverIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(List.of());
        given(busRepository.findByAttendantIdAndTenantId(TARGET_ID, TENANT_ID)).willReturn(List.of());
        given(studentGuardianRepository.findByGuardianId(TARGET_ID)).willReturn(List.of());

        service.removeMembership(admin(), TARGET_ID);

        verify(userTenantRoleRepository).delete(membership);
        verify(userRepository, never()).delete(any());
    }

    // ── fixtures ──

    private UserTenantRole givenMembership(Long userId, Role role) {
        UserTenantRole membership = membership(userId, role);
        given(userTenantRoleRepository.findByUserIdAndTenantId(userId, TENANT_ID)).willReturn(Optional.of(membership));
        return membership;
    }

    private UserTenantRole membership(Long userId, Role role) {
        User user = User.builder()
                .email("driver@school.com").password("{bcrypt}old").name("김기사")
                .phone("010-1111-2222").build();
        ReflectionTestUtils.setField(user, "id", userId);
        UserTenantRole membership = UserTenantRole.builder().user(user).tenant(tenant()).role(role).build();
        ReflectionTestUtils.setField(membership, "id", 500L + userId);
        return membership;
    }

    private Bus bus(Long id, String name) {
        Bus bus = Bus.builder().tenant(tenant()).name(name).seatCapacity(25).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private AuthUser admin() {
        return new AuthUser(ADMIN_ID, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }

    private UpdateMemberRequest profileOnly(String name) {
        return new UpdateMemberRequest(null, name, null, null, null);
    }
}

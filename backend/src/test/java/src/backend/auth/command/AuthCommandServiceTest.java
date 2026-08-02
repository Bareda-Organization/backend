package src.backend.auth.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.auth.dto.SignupRequest;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 회원가입 단위 테스트 — 요청 body 의 role 로 권한 상승이 되지 않는지(BE-11)를 우선 검증한다.
 * 관리자 부여 역할은 계정이 만들어지기 전에 거부돼야 한다.
 */
class AuthCommandServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserTenantRoleRepository userTenantRoleRepository = mock(UserTenantRoleRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final AuthCommandService service = new AuthCommandService(
            userRepository, userTenantRoleRepository, tenantRepository, passwordEncoder);

    private static final Long TENANT_ID = 1L;

    @Test
    void signup_asAcademyAdmin_throwsForbidden() {
        assertThatThrownBy(() -> service.signup(request(Role.ACADEMY_ADMIN)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(userRepository);
    }

    @Test
    void signup_asPlatformAdmin_throwsForbidden() {
        assertThatThrownBy(() -> service.signup(request(Role.PLATFORM_ADMIN)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(userRepository);
    }

    @Test
    void signup_asAttendant_throwsForbidden() {
        assertThatThrownBy(() -> service.signup(request(Role.ATTENDANT)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(userRepository);
    }

    @Test
    void signup_asParent_savesUserAndRole() {
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(userRepository.save(any())).willReturn(user(100L));

        service.signup(request(Role.PARENT));

        verify(userTenantRoleRepository).save(any());
    }

    private SignupRequest request(Role role) {
        return new SignupRequest("new.parent@school.com", "password", "김하늘", "010-1234-5678", TENANT_ID, role);
    }

    private User user(Long id) {
        User user = User.builder().email("new.parent@school.com").name("김하늘").password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }
}

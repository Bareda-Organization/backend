package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.security.AuthUser;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationPing;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.repository.spec.LocationRepository;
import src.backend.location.websocket.LocationSessionRegistry;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * 학생 단말 보고의 좌표 출처(origin) 수신 단위 테스트(MON-2) —
 * 버스 쪽 {@link BusLocationCommandServiceTest}와 같은 성질을 학생 경로에서 고정한다.
 * REST {@code POST /api/locations}와 STOMP {@code /app/location} 이 같은 요청 record 를 쓰므로
 * 두 통로 모두 이 경로를 지난다.
 */
class LocationCommandServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long STUDENT_ID = 5L;
    private static final Long USER_ID = 50L;

    private final LocationRepository locationRepository = mock(LocationRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final LocationSessionRegistry sessionRegistry = mock(LocationSessionRegistry.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final LocationCommandService service = new LocationCommandService(
            locationRepository, studentRepository, sessionRegistry, eventPublisher, 30L);

    /** 기존 클라이언트는 {lat, lng} 만 보낸다 — 생략은 GPS 로 간주한다. */
    @Test
    void reportSelf_withoutOrigin_isStoredAsGps() {
        givenStudent();

        service.reportSelf(student(), new LocationReportRequest(37.4998, 127.0245, null));

        assertThat(savedPing().origin()).isEqualTo(LocationOrigin.GPS);
    }

    /** 보고한 출처가 그대로 저장된다 — 예전에는 무엇을 보내든 GPS 로 고정됐다. */
    @Test
    void reportSelf_withMockOrigin_isStoredAsMock() {
        givenStudent();

        service.reportSelf(student(), new LocationReportRequest(37.4998, 127.0245, LocationOrigin.MOCK));

        assertThat(savedPing().origin()).isEqualTo(LocationOrigin.MOCK);
    }

    private LocationPing savedPing() {
        ArgumentCaptor<LocationPing> captor = ArgumentCaptor.forClass(LocationPing.class);
        verify(locationRepository).save(captor.capture());
        return captor.getValue();
    }

    private void givenStudent() {
        given(studentRepository.findByUserId(USER_ID)).willReturn(Optional.of(studentEntity()));
    }

    private Student studentEntity() {
        Student student = Student.builder().tenant(tenant()).userId(USER_ID).name("김민준").build();
        ReflectionTestUtils.setField(student, "id", STUDENT_ID);
        return student;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private AuthUser student() {
        return new AuthUser(USER_ID, "student@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.STUDENT)));
    }
}

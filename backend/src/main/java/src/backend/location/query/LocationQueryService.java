package src.backend.location.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.dto.LocationPing;
import src.backend.location.dto.LocationView;
import src.backend.location.repository.spec.LocationRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 실시간 위치 역할별 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 * CQRS 원칙(§7)에 따라 {@link src.backend.location.command.LocationCommandService}를 호출하지 않는다.
 */
@Service
public class LocationQueryService {

    private final LocationRepository locationRepository;
    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final BusRepository busRepository;

    public LocationQueryService(LocationRepository locationRepository,
                                StudentRepository studentRepository,
                                StudentGuardianRepository studentGuardianRepository,
                                BusRepository busRepository) {
        this.locationRepository = locationRepository;
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.busRepository = busRepository;
    }

    @Transactional(readOnly = true)
    public LocationView getMyLocation(AuthUser student) {
        Student me = studentRepository.findByUserId(student.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생 정보를 찾을 수 없습니다"));
        LocationPing ping = locationRepository.findLatest(me.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "아직 위치 정보가 없습니다"));
        return LocationView.of(me.getName(), ping);
    }

    @Transactional(readOnly = true)
    public List<LocationView> getChildrenLocations(AuthUser parent) {
        List<Student> children = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .toList();
        return viewsFor(children);
    }

    @Transactional(readOnly = true)
    public List<LocationView> getBusLocations(AuthUser driver, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 조회할 수 있습니다");
        }
        return viewsFor(studentRepository.findByAssignedBusIdAndActiveTrue(busId));
    }

    @Transactional(readOnly = true)
    public List<LocationView> getTenantLocations(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return viewsFor(studentRepository.findByTenantIdAndActiveTrue(effectiveTenant));
    }

    /** 학생 목록 → 최신 좌표가 있는 학생만 뷰로 변환(아직 좌표 없는 학생은 제외). */
    private List<LocationView> viewsFor(List<Student> students) {
        return students.stream()
                .flatMap(s -> locationRepository.findLatest(s.getId()).stream()
                        .map(ping -> LocationView.of(s.getName(), ping)))
                .toList();
    }
}

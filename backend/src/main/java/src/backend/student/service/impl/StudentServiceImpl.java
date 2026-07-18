package src.backend.student.service.impl;

import src.backend.student.service.spec.StudentService;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.dto.CreateStudentRequest;
import src.backend.student.dto.LinkGuardianRequest;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentResponse;
import src.backend.student.dto.UpdateStudentAssignmentRequest;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;

/**
 * {@link StudentService} 기본 구현 — 학생 등록(배정·보호자 포함)/목록/상세/재배정/보호자추가.
 * 학원 격리는 TenantGuard 로, 배정 대상(버스·정류장)의 소속 학원 일치는 헬퍼로 검증한다.
 */
@Service
public class StudentServiceImpl implements StudentService {

    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final TenantRepository tenantRepository;
    private final BusRepository busRepository;
    private final StopRepository stopRepository;
    private final UserRepository userRepository;

    public StudentServiceImpl(StudentRepository studentRepository,
                              StudentGuardianRepository studentGuardianRepository,
                              TenantRepository tenantRepository,
                              BusRepository busRepository,
                              StopRepository stopRepository,
                              UserRepository userRepository) {
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.tenantRepository = tenantRepository;
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public StudentDetailResponse create(AuthUser admin, CreateStudentRequest req) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, req.tenantId());
        Tenant tenant = tenantRepository.findById(effectiveTenant)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));

        Bus bus = req.assignedBusId() != null ? loadBusInTenant(req.assignedBusId(), effectiveTenant) : null;
        Stop stop = req.boardingStopId() != null ? loadStopInTenant(req.boardingStopId(), effectiveTenant) : null;

        Student student = studentRepository.save(Student.builder()
                .tenant(tenant)
                .userId(req.userId())
                .name(req.name())
                .assignedBus(bus)
                .boardingStop(stop)
                .build());

        if (req.guardians() != null) {
            for (CreateStudentRequest.GuardianLink link : req.guardians()) {
                studentGuardianRepository.save(StudentGuardian.builder()
                        .student(student)
                        .guardian(loadUser(link.guardianUserId()))
                        .relation(link.relation())
                        .build());
            }
        }
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentResponse> list(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        return studentRepository.findByTenantId(effectiveTenant).stream()
                .map(StudentResponse::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StudentDetailResponse get(AuthUser admin, Long studentId) {
        Student student = loadAccessibleStudent(admin, studentId);
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    @Override
    @Transactional
    public StudentResponse updateAssignment(AuthUser admin, Long studentId, UpdateStudentAssignmentRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        Long tenantId = student.getTenant().getId();
        if (req.assignedBusId() != null) {
            student.assignBus(loadBusInTenant(req.assignedBusId(), tenantId));
        }
        if (req.boardingStopId() != null) {
            student.assignStop(loadStopInTenant(req.boardingStopId(), tenantId));
        }
        return StudentResponse.of(student);
    }

    @Override
    @Transactional
    public StudentDetailResponse addGuardian(AuthUser admin, Long studentId, LinkGuardianRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        studentGuardianRepository.save(StudentGuardian.builder()
                .student(student)
                .guardian(loadUser(req.guardianUserId()))
                .relation(req.relation())
                .build());
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    // ── 내부 헬퍼 ──

    private Student loadAccessibleStudent(AuthUser admin, Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학생을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, student.getTenant().getId()); // 접근 권한 검증
        return student;
    }

    private Bus loadBusInTenant(Long busId, Long tenantId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        if (!bus.getTenant().getId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "버스가 이 학원 소속이 아닙니다");
        }
        return bus;
    }

    private Stop loadStopInTenant(Long stopId, Long tenantId) {
        Stop stop = stopRepository.findById(stopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "정류장을 찾을 수 없습니다"));
        if (!stop.getRoute().getTenant().getId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "정류장이 이 학원 소속이 아닙니다");
        }
        return stop;
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "보호자를 찾을 수 없습니다"));
    }
}

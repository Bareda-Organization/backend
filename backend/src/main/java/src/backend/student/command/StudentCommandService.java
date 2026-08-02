package src.backend.student.command;

import java.time.LocalDate;

import org.springframework.context.ApplicationEventPublisher;
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
import src.backend.student.dto.UpdateDropoffRequest;
import src.backend.student.dto.UpdateStudentAssignmentRequest;
import src.backend.student.dto.UpdateStudentRequest;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.event.StudentAssignmentChangedEvent;
import src.backend.student.event.StudentDropoffChangedEvent;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 학생 등록(배정·보호자 포함)/재배정/보호자추가 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다.
 * 학원 격리는 TenantGuard 로, 배정 대상(버스·정류장)의 소속 학원 일치는 헬퍼로 검증한다.
 */
@Service
public class StudentCommandService {

    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final TenantRepository tenantRepository;
    private final BusRepository busRepository;
    private final StopRepository stopRepository;
    private final UserRepository userRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StudentCommandService(StudentRepository studentRepository,
                                 StudentGuardianRepository studentGuardianRepository,
                                 TenantRepository tenantRepository,
                                 BusRepository busRepository,
                                 StopRepository stopRepository,
                                 UserRepository userRepository,
                                 UserTenantRoleRepository userTenantRoleRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.tenantRepository = tenantRepository;
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
        this.userRepository = userRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
        this.eventPublisher = eventPublisher;
    }

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
                        .guardian(loadGuardianInTenant(link.guardianUserId(), effectiveTenant))
                        .relation(link.relation())
                        .build());
            }
        }
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    @Transactional
    public StudentResponse updateAssignment(AuthUser admin, Long studentId, UpdateStudentAssignmentRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        Long tenantId = student.getTenant().getId();
        Long oldBusId = student.getAssignedBus() != null ? student.getAssignedBus().getId() : null;
        boolean changed = false;
        if (req.assignedBusId() != null) {
            student.assignBus(loadBusInTenant(req.assignedBusId(), tenantId));
            changed = true;
        }
        if (req.boardingStopId() != null) {
            student.assignStop(loadStopInTenant(req.boardingStopId(), tenantId));
            changed = true;
        }
        if (changed) {
            Long newBusId = student.getAssignedBus() != null ? student.getAssignedBus().getId() : null;
            eventPublisher.publishEvent(
                    StudentAssignmentChangedEvent.of(tenantId, studentId, oldBusId, newBusId, LocalDate.now()));
        }
        return StudentResponse.of(student);
    }

    @Transactional
    public StudentResponse updateDropoff(AuthUser admin, Long studentId, UpdateDropoffRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        student.updateDropoff(req.dropoffAddress(), req.dropoffLat(), req.dropoffLng());
        if (student.getAssignedBus() != null) {
            eventPublisher.publishEvent(StudentDropoffChangedEvent.of(
                    student.getTenant().getId(), studentId, student.getAssignedBus().getId(), LocalDate.now()));
        }
        return StudentResponse.of(student);
    }

    /**
     * 인적 정보(이름·전화·사진) 수정. null 필드는 그대로 둔다.
     * ⚠️ 도메인 이벤트를 발행하지 않는다 — 이름·전화·사진은 노선에 영향이 없다.
     * 여기서 재계획을 트리거하면 이름 오타 하나를 고칠 때마다 노선이 다시 배포된다.
     */
    @Transactional
    public StudentResponse updateProfile(AuthUser admin, Long studentId, UpdateStudentRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        student.updateProfile(req.name(), req.phone(), req.photoUrl());
        return StudentResponse.of(student);
    }

    /**
     * 퇴원 처리 — 행을 지우지 않고 active=false 로 바꾼다(D-O·I-8).
     * ⚠️ assignedBus 를 null 로 지우지 않는다. 지우면 "어느 버스에서 빠졌는지"가 사라져 되돌릴 수도,
     * 추적할 수도 없다. 명단에서 빼는 일은 리포지토리의 ...AndActiveTrue 조회가 이미 해 준다(I-9).
     */
    @Transactional
    public StudentResponse deactivate(AuthUser admin, Long studentId) {
        Student student = loadAccessibleStudent(admin, studentId);
        if (!student.isActive()) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 비활성 상태입니다");
        }
        student.deactivate();
        // 배차돼 있던 학생이 빠지면 그 버스의 노선이 낡는다 — 재계획 신호를 보낸다.
        if (student.getAssignedBus() != null) {
            eventPublisher.publishEvent(StudentAssignmentChangedEvent.of(
                    student.getTenant().getId(), studentId,
                    student.getAssignedBus().getId(), null, LocalDate.now()));
        }
        return StudentResponse.of(student);
    }

    @Transactional
    public StudentDetailResponse addGuardian(AuthUser admin, Long studentId, LinkGuardianRequest req) {
        Student student = loadAccessibleStudent(admin, studentId);
        // student_guardian 에 unique(student_id, guardian_id) 가 있어 DB 는 막지만, 그대로 두면 500 이 난다.
        if (studentGuardianRepository.findByStudentIdAndGuardianId(studentId, req.guardianUserId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 연결된 보호자입니다");
        }
        studentGuardianRepository.save(StudentGuardian.builder()
                .student(student)
                .guardian(loadGuardianInTenant(req.guardianUserId(), student.getTenant().getId()))
                .relation(req.relation())
                .build());
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(student.getId()));
    }

    /**
     * 보호자 연결 해제 — student_guardian 행만 지운다.
     * 학부모 계정(app_user)도 학생(student)도 남는다(D-O 는 그 두 테이블을 지키는 규칙이고,
     * 연결 행은 누구의 기록도 참조하지 않는다). 보호자 0명인 학생은 정상 상태라 "마지막 보호자"를 막지 않는다.
     */
    @Transactional
    public StudentDetailResponse removeGuardian(AuthUser admin, Long studentId, Long guardianUserId) {
        Student student = loadAccessibleStudent(admin, studentId);   // 학원 격리는 학생 쪽에서 끝난다
        StudentGuardian link = studentGuardianRepository
                .findByStudentIdAndGuardianId(studentId, guardianUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "연결된 보호자가 아닙니다"));
        studentGuardianRepository.delete(link);
        return StudentDetailResponse.of(student, studentGuardianRepository.findByStudentId(studentId));
    }

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

    /** 그 학원의 PARENT 계정만 보호자로 연결한다 — BusCommandService.loadUserWithRole 과 같은 규칙이다. */
    private User loadGuardianInTenant(Long userId, Long tenantId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "보호자를 찾을 수 없습니다"));
        boolean granted = userTenantRoleRepository.findByUserId(userId).stream()
                .anyMatch(utr -> utr.getRole() == Role.PARENT
                        && utr.getTenant() != null && utr.getTenant().getId().equals(tenantId));
        if (!granted) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 학원의 학부모 계정이 아닙니다(userId=" + userId + ")");
        }
        return user;
    }
}

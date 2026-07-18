package src.backend.global.push;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * "이 학생과 관련된 WebSocket push 대상이 누구인가"를 한 곳에서 구한다.
 * {@code location.projection.LocationPushConsumer}(위치 push)와
 * {@code notification.infrastructure.impl.WebSocketNotificationSender}(알림 push)가
 * 완전히 같은 대상(본인·학부모·담당기사·소속학원)을 필요로 해 로직이 갈라지지 않게 여기 하나로 둔다.
 */
@Component
public class PushTargetResolver {

    private final StudentRepository studentRepository;
    private final StudentGuardianRepository studentGuardianRepository;

    public PushTargetResolver(StudentRepository studentRepository,
                              StudentGuardianRepository studentGuardianRepository) {
        this.studentRepository = studentRepository;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    @Transactional(readOnly = true)
    public Optional<PushTargets> resolve(Long studentId) {
        return studentRepository.findById(studentId).map(student -> {
            List<Long> guardianUserIds = studentGuardianRepository.findByStudentId(studentId).stream()
                    .map(StudentGuardian::getGuardian)
                    .map(guardian -> guardian.getId())
                    .toList();
            Long driverUserId = resolveDriverUserId(student);
            return new PushTargets(student.getName(), student.getUserId(), guardianUserIds,
                    driverUserId, student.getTenant().getId());
        });
    }

    private Long resolveDriverUserId(Student student) {
        if (student.getAssignedBus() == null || student.getAssignedBus().getDriver() == null) {
            return null;
        }
        return student.getAssignedBus().getDriver().getId();
    }

    /**
     * @param studentUserId  학생 본인 로그인 계정(계정 미연결 시 null — 호출부에서 건너뛴다)
     * @param guardianUserIds 학부모 계정 목록(0개 가능)
     * @param driverUserId   담당 기사 계정(미배차 시 null)
     * @param tenantId       소속 학원(관리자 테넌트 토픽 대상)
     */
    public record PushTargets(
            String studentName,
            Long studentUserId,
            List<Long> guardianUserIds,
            Long driverUserId,
            Long tenantId) {
    }
}

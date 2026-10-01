package src.backend.global.retention;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.notification.repository.DeviceTokenRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.entity.Student;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 퇴원 90일이 지난 학생의 개인정보를 파기한다(Ruling 480 ②·520, ERD §7) — 보존 정리 배치({@code RetentionCleanupScheduler})가
 * 컷오프로 고른 학생 id 묶음을 받는다.
 *
 * <p><b>학생 행은 지우지 않고 익명화한다.</b> {@code run_rider} · {@code boarding_intent} · {@code change_request} ·
 * {@code run_forced_addition} 이 {@code ON DELETE RESTRICT} 로 이 행을 참조하고, 승하차 이력은 무기한 보존이라
 * (ERD §7.2) 행을 지우면 이력이 함께 사라진다. 행은 남기고 알아볼 수 있는 값만 바꾼다 — 이름은 {@link Student#ANONYMIZED_NAME},
 * 나머지는 {@code null}.
 *
 * <p>같은 트랜잭션에서 함께 처리하는 것: 요일별 주소·좌표(행 삭제) · 연결 코드(행 삭제) · 변경 요청의 주소·좌표·사유(익명값) ·
 * 학생의 앱 계정(익명화 + 로그인 불가 + 재발급 토큰·푸시 단말 삭제). 처리 결과는 감사 1행으로 남는다 — 행위자가 없는 시스템
 * 처리라 {@code actor_account_id} 가 {@code null}, {@code detail.purged_students} 에 건수가 담긴다. 감사 행도 같은
 * 트랜잭션이라 "파기는 됐는데 기록이 없다" 가 생기지 않는다.
 *
 * <p><b>사진 파일은 트랜잭션보다 먼저 지운다.</b> 학생 사진은 저장소 밖(파일)이라 되돌릴 수 없는데, 파기 대상은 이미 퇴원한
 * 학생이라 파일이 먼저 사라져도 잃는 것이 부재하다. 거꾸로 커밋 뒤에 지우면 그 사이에 프로세스가 끝났을 때 아무도 가리키지 않는
 * 파일이 영영 남고 다시 찾을 방법이 없다 — 파일을 먼저 지우면 트랜잭션이 실패해도 다음 틱이 같은 학생을 다시 잡아 마저 처리한다.
 *
 * <p><b>이 클래스가 {@code global.retention} 에 있는 이유</b> — 학생·계정·알림·요청 4개 모듈의 행을 한 트랜잭션에서 건드린다.
 * {@code student} 안에 두면 {@code notification↔student} 양방향 참조가 생기고(알림은 이벤트로만 부른다는
 * {@code NotificationModuleIsolationTest} 규칙 위반), 계정·요청 쪽에 나눠 두면 한 트랜잭션이 깨진다. 모듈을 가로지르는 보존
 * 정리는 이미 {@link RetentionCleanupScheduler} 가 이 패키지에서 하고 있다.
 *
 * <p>같은 id 를 다시 받아도(이미 익명화) 결과가 같다 — 재실행 멱등성은 조회 조건({@code anonymized_at IS NULL})과 이 서비스의
 * 방어 필터가 함께 보장한다.
 */
@Service
@Slf4j
public class StudentAnonymizationService {

    private final StudentRepository studentRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final LinkCodeRepository linkCodeRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final AccountRepository accountRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final DeviceTokenRepository deviceTokenRepository;

    private final AuditLogRepository auditLogRepository;

    private final PhotoStorage photoStorage;

    private final Clock clock;

    private final TransactionTemplate transaction;

    public StudentAnonymizationService(StudentRepository studentRepository,
            WeeklyAddressRepository weeklyAddressRepository, LinkCodeRepository linkCodeRepository,
            ChangeRequestRepository changeRequestRepository, AccountRepository accountRepository,
            RefreshTokenRepository refreshTokenRepository, DeviceTokenRepository deviceTokenRepository,
            AuditLogRepository auditLogRepository, PhotoStorage photoStorage, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.studentRepository = studentRepository;
        this.weeklyAddressRepository = weeklyAddressRepository;
        this.linkCodeRepository = linkCodeRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.photoStorage = photoStorage;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 받은 학생 id 묶음을 파기한다 — 묶음 크기는 호출부(배치 상한)가 정한다. */
    public void anonymize(List<Long> studentIds) {
        studentRepository.findAllByIdIn(studentIds).stream()
                .filter(student -> student.getAnonymizedAt() == null)
                .map(Student::getPhotoUrl)
                .filter(Objects::nonNull)
                .forEach(photoStorage::delete);
        transaction.executeWithoutResult(status -> purge(studentIds));
    }

    private void purge(List<Long> studentIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Student> students = studentRepository.findAllByIdIn(studentIds).stream()
                .filter(student -> student.getAnonymizedAt() == null)
                .toList();
        if (students.isEmpty()) {
            return;
        }
        List<Long> ids = students.stream().map(Student::getId).toList();
        List<Long> accountIds = students.stream().map(Student::getAccountId).filter(Objects::nonNull).toList();
        // 엔티티 변경을 벌크 쿼리보다 먼저 한다 — 벌크 쿼리는 flush 뒤 영속성 컨텍스트를 비워, 그 뒤에 바꾼 엔티티는 저장되지 않는다
        accountRepository.findAllByIdIn(accountIds)
                .forEach(account -> account.anonymizeForRetention(Student.ANONYMIZED_NAME, now));
        students.forEach(student -> student.anonymize(now));
        if (!accountIds.isEmpty()) {
            refreshTokenRepository.deleteAllByAccountIds(accountIds);
            deviceTokenRepository.deleteAllByAccountIds(accountIds);
        }
        weeklyAddressRepository.deleteAllByStudentIds(ids);
        linkCodeRepository.deleteAllByStudentIds(ids);
        changeRequestRepository.anonymizeAddressesOfStudents(ids, Student.ANONYMIZED_ADDRESS);
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, null, null, null, "student", null,
                Map.of("purged_students", students.size()), now));
        log.info("퇴원 학생 개인정보 파기 — {}명 익명화", students.size());
    }
}

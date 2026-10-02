package src.backend.global.retention;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import src.backend.student.repository.GuardianStudentRepository;
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
 * <p>같은 트랜잭션에서 함께 처리하는 것: 요일별 주소·좌표(행 삭제) · 연결 코드(행 삭제) · <b>보호자 연결(행 삭제, Ruling 610 —
 * 남기면 보호자→자녀→승하차지 조인으로 집 주소가 복원된다. 보호자 본인은 남긴다)</b> · 변경 요청의 주소·좌표·사유(익명값) ·
 * 학생의 앱 계정(익명화 + 로그인 불가 + 재발급 토큰·푸시 단말 삭제). 처리 결과는 감사 1행으로 남는다 — 행위자가 없는 시스템
 * 처리라 {@code actor_account_id} 가 {@code null}, {@code detail.purged_students} 에 건수가 담긴다. 감사 행도 같은
 * 트랜잭션이라 "파기는 됐는데 기록이 없다" 가 생기지 않는다.
 *
 * <p><b>사진 파일은 트랜잭션보다 먼저 지운다.</b> 학생 사진은 저장소 밖(파일)이라 되돌릴 수 없는데, 파기 대상은 이미 퇴원한
 * 학생이라 파일이 먼저 사라져도 잃는 것이 부재하다. 거꾸로 커밋 뒤에 지우면 그 사이에 프로세스가 끝났을 때 아무도 가리키지 않는
 * 파일이 영영 남고 다시 찾을 방법이 없다 — 파일을 먼저 지우면 트랜잭션이 실패해도 다음 틱이 같은 학생을 다시 잡아 마저 처리한다. 파일을 지웠다고 확인하지 못한 학생은 이번 묶음의
 * 파기에서 빼 사진 참조를 남긴다(BR-310 — {@link PhotoStorage#deleteConfirmed}).
 *
 * <p><b>이 클래스가 {@code global.retention} 에 있는 이유</b> — 학생·계정·알림·요청 4개 모듈의 행을 한 트랜잭션에서 건드린다.
 * {@code student} 안에 두면 {@code notification↔student} 양방향 참조가 생기고(알림은 이벤트로만 부른다는
 * {@code NotificationModuleIsolationTest} 규칙 위반), 계정·요청 쪽에 나눠 두면 한 트랜잭션이 깨진다. 모듈을 가로지르는 보존
 * 정리는 이미 {@link RetentionCleanupScheduler} 가 이 패키지에서 하고 있다.
 *
 * <p><b>크기 기준(CODE_CONVENTIONS §20.2)을 넘긴 이유</b> — 생성자 인자 12개는 위 4개 모듈의 저장소·사진 저장소·시계·트랜잭션을 각각 받는 것이고, {@code purge}
 * 본문 25줄 안팎은 한 트랜잭션에서 순서가 정해진 일곱 갈래 삭제·익명화(엔티티 변경이 벌크 쿼리의 영속성 컨텍스트 비우기보다 먼저)라서다. 나누면
 * 그 순서가 메서드 경계에 흩어진다. 갈래별 협력 객체로 쪼개는 것은 모듈을 가로지르는 이 클래스의 존재 이유와 같은 크기의 재설계다.
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

    private final GuardianStudentRepository guardianStudentRepository;

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
            GuardianStudentRepository guardianStudentRepository, ChangeRequestRepository changeRequestRepository, AccountRepository accountRepository,
            RefreshTokenRepository refreshTokenRepository, DeviceTokenRepository deviceTokenRepository,
            AuditLogRepository auditLogRepository, PhotoStorage photoStorage, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.studentRepository = studentRepository;
        this.weeklyAddressRepository = weeklyAddressRepository;
        this.linkCodeRepository = linkCodeRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.photoStorage = photoStorage;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 받은 학생 id 묶음을 파기하고 <b>파기한 학생 수</b>를 돌려준다 — 묶음 크기는 호출부(배치 상한)가 정한다. 못 한 학생은 예외가 아니라 빠진
     * 수로 드러나고(호출부가 지표로 올린다), 조회 조건이 그대로라 다음 틱에 다시 잡힌다.
     *
     * <p>두 가지 이유로 파기하지 못한 학생이 생긴다. ① 사진 파일을 지웠다고 확인하지 못함(BR-310) — 파일이 남은 채 사진 참조만 지우면
     * 다시 찾을 방법이 없어, 참조를 남겨 둔다. ② 한 학생의 처리 실패(BR-311) — 묶음 트랜잭션이 실패하면 학생 한 명씩 다시 시도해 실패한
     * 학생만 건너뛴다. 묶음을 그대로 두면 다음 틱도 같은 id 오름차순 묶음에서 같은 학생이 실패해 뒤의 학생 전원이 파기되지 못한다.
     */
    public int anonymize(List<Long> studentIds) {
        List<Long> readyIds = studentsWithPhotoGone(studentIds);
        return readyIds.isEmpty() ? 0 : purgeIsolatingFailures(readyIds);
    }

    /** 사진이 없거나 사진 파일이 지워졌다고 확인된 학생 id — 파일을 지우지 못한 학생은 로그만 남기고 뺀다. */
    private List<Long> studentsWithPhotoGone(List<Long> studentIds) {
        List<Long> readyIds = new ArrayList<>();
        for (Student student : studentRepository.findAllByIdIn(studentIds)) {
            if (student.getAnonymizedAt() != null) {
                continue;
            }
            String photoUrl = student.getPhotoUrl();
            if (photoUrl == null || photoUrl.isBlank() || photoStorage.deleteConfirmed(photoUrl)) {
                readyIds.add(student.getId());
            } else {
                log.warn("학생 사진 파일을 지우지 못해 파기를 미룬다(다음 틱에 다시 시도) — studentId={}", student.getId());
            }
        }
        return readyIds;
    }

    /** 한 트랜잭션으로 파기하고, 실패하면 학생 한 명씩 다시 시도한다 — 한 명이면 그 학생만 실패로 남는다. */
    private int purgeIsolatingFailures(List<Long> studentIds) {
        try {
            return transaction.execute(status -> purge(studentIds));
        } catch (RuntimeException e) {
            if (studentIds.size() == 1) {
                log.warn("퇴원 학생 파기 실패(다음 틱에 다시 시도) — studentId={}", studentIds.get(0), e);
                return 0;
            }
            log.warn("퇴원 학생 파기 묶음 실패 — {}명을 한 명씩 다시 시도한다", studentIds.size(), e);
            return studentIds.stream().mapToInt(id -> purgeIsolatingFailures(List.of(id))).sum();
        }
    }

    private int purge(List<Long> studentIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Student> students = studentRepository.findAllByIdIn(studentIds).stream()
                .filter(student -> student.getAnonymizedAt() == null)
                .toList();
        if (students.isEmpty()) {
            return 0;
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
        guardianStudentRepository.deleteAllByStudentIds(ids);
        changeRequestRepository.anonymizeAddressesOfStudents(ids, Student.ANONYMIZED_ADDRESS);
        // 시스템 배치라 요청 IP 가 없다(R46-FUBE 의 ip 인자 — 요청 밖은 null)
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, null, null, null, "student", null,
                Map.of("purged_students", students.size()), null, now));
        log.info("퇴원 학생 개인정보 파기 — {}명 익명화", students.size());
        return students.size();
    }
}

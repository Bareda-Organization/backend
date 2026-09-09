package src.backend.request.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.util.ReflectionTestUtils;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.StudentRepository;

/**
 * 자동 거절(Phase 8 T6) 시험이 쓰는 실제 행 — {@code RunConfirmationFixtures}(Phase 7)와 같은 이유로
 * 정상 경로의 팩토리로 쌓는다. {@code public} 인 이유도 같다 — {@code request.scheduler} 패키지의
 * 스케줄러 시험이 이 헬퍼를 재사용해야 해서 패키지 경계를 넘긴다.
 *
 * <p>{@link ChangeRequest#deadlineAt} 은 팩토리·전이 메서드 어디에도 값을 실을 자리가 없다 — 이
 * 컬럼을 채우는 생성 경로(마감 = {@code run.depart_time}, {@code ChangeWindowPolicy} 자바독)가 아직
 * 이 저장소에 존재하지 않기 때문이다(그 경로는 이 태스크의 범위 밖인 별도 태스크의 몫이다). 그래서
 * {@link #pendingChangeRequest} 는 이 프로젝트에서 이미 쓰이는 패턴({@code AccountTest}·
 * {@code ChangeWindowPolicyTest} 의 {@code ReflectionTestUtils})으로 저장 전 필드를 직접 채운다.
 */
public class ChangeRequestAutoRejectFixtures {

    /** 뒷정리 표시 — 스케줄러·동시성 시험이 자기 행만 골라 지우는 데 쓴다. */
    public static final String ACADEMY_NAME = "자동거절시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final AccountRepository accountRepository;

    private final RunRepository runRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    public ChangeRequestAutoRejectFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StudentRepository studentRepository, AccountRepository accountRepository, RunRepository runRepository,
            BoardingIntentRepository boardingIntentRepository, ChangeRequestRepository changeRequestRepository,
            ConfirmedRouteRepository confirmedRouteRepository, RouteVersionRepository routeVersionRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.accountRepository = accountRepository;
        this.runRepository = runRepository;
        this.boardingIntentRepository = boardingIntentRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
    }

    public long academy() {
        String code = "P8T6" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "자동거절" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "11가" + SEQUENCE.incrementAndGet(),
                BusSeating.withDefaultCrew(16))).getId();
    }

    public long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /** 변경 요청 신청 주체(학부모) 계정 — 알림 수신자 조회 대상이다. */
    public long parentAccount(long academyId, String name) {
        String loginId = "부모" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = Account.forSignup(academyId, loginId, "hash", name, "010-0000-0000", null, Role.PARENT);
        return accountRepository.save(account).getId();
    }

    /** idle 회차 1건 — {@code confirmAt} 은 자동 거절 폴링과 무관해 {@code departTime - 30분}으로 고정한다. */
    public long run(long academyId, long busId, OffsetDateTime departTime) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        return runRepository.save(run).getId();
    }

    /** 한도를 이미 소진한 탑승 의사 — 자동 거절이 이 값을 0으로 되돌리는지가 목표 3의 핵심이다. */
    public long spentBoardingIntent(long runId, long studentId, OffsetDateTime createdAt) {
        BoardingIntent intent = BoardingIntent.forRun(runId, studentId, createdAt);
        intent.consumeChangeQuota();
        return boardingIntentRepository.save(intent).getId();
    }

    /**
     * 확정 노선 1버전 — "재최적화 미호출"(목표 3)을 {@code currentVersionId} 불변으로 실측하는 기준선이다.
     *
     * @return 처음 배정된 {@code route_version.id}
     */
    public long confirmedRouteWithVersion(long runId, OffsetDateTime publishedAt) {
        confirmedRouteRepository.save(ConfirmedRoute.forRun(runId, publishedAt));
        RouteVersion version = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 1,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("10.00"), publishedAt, "fp-" + runId,
                "engine-v1", Map.of(), false, null, publishedAt));
        confirmedRouteRepository.assignCurrentVersion(runId, version.getId());
        return version.getId();
    }

    /**
     * 대기 중인 변경 요청 — {@code deadlineAt} 은 공개 API가 없어 {@link ReflectionTestUtils} 로 저장 전에
     * 직접 채운다(클래스 자바독 참고).
     */
    public long pendingChangeRequest(long academyId, long runId, long studentId, long requestedBy,
            OffsetDateTime requestedAt, OffsetDateTime deadlineAt) {
        ChangeRequest request = ChangeRequest.forRequest(academyId, runId, studentId, ChangeRequestSource.CHANGE_REQUEST,
                ChangeRequestType.CANCEL, (short) 2, requestedBy, requestedAt);
        ReflectionTestUtils.setField(request, "deadlineAt", deadlineAt);
        return changeRequestRepository.save(request).getId();
    }
}

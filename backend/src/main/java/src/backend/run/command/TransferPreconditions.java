package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.run.dto.TransferCapacityDetail;
import src.backend.run.dto.TransferRequest;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 버스 간 이동(§5.8) 접수의 조회·판정 단계 — 학생·두 회차 조회, 구간·중복 대기·명단 소속·정원 판정을 맡는다.
 * 트랜잭션 없이 {@link TransferCommandService} 가 순서대로 부른다(외부 지오코딩 전 단계라 DB 커넥션을 쥐지 않는다).
 */
@Component
@RequiredArgsConstructor
class TransferPreconditions {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunTransferRepository runTransferRepository;

    private final ProjectedRosterReader rosterReader;

    private final StudentRepository studentRepository;

    private final Clock clock;

    /** 출발·도착 회차 쌍. */
    record Runs(Run from, Run to) {
    }

    /** 도착 회차의 이동 반영 전 인원과 버스 정원. */
    record TargetCapacity(long currentCount, int capacity) {
    }

    /** {id} 는 학생이다(§5.8 경로) — 존재 비노출(Ruling 163)이라 타 학원 학생도 404. */
    Student findStudent(AuthUser requester, Long studentId) {
        return studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }

    /** 출발·도착 회차를 찾고 서로 다른 회차이며 같은 날짜·방향인지 확인한다. */
    Runs findRuns(AuthUser requester, TransferRequest request) {
        if (request.fromRunId().equals(request.toRunId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Run fromRun = runRepository.findByIdAndAcademyId(request.fromRunId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        // 도착 회차는 타 학원이면 부재를 숨기지 않고 403 으로 답한다(사양 본문 — from 과 다른 판정).
        // 관계자가 자기 화면에서 고른 회차 id 라 "존재하지만 접근 불가" 를 그대로 알려주는 쪽이
        // "왜 안 되는지" 를 알 수 있게 한다는 판단으로 보인다(§7 규칙 16 계열의 절별 예외).
        Run toRun = runRepository.findById(request.toRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, toRun.getAcademyId());
        // §5.8 "같은 학원·같은 날짜·같은 방향" — 어긋나면 오늘 등원 명단에서 빠져 다른 날·반대 방향에 더해진다.
        if (!toRun.getServiceDate().equals(fromRun.getServiceDate())
                || toRun.getDirection() != fromRun.getDirection()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new Runs(fromRun, toRun);
    }

    /** 두 회차가 모두 ①구간인지 확인한다. */
    void assertWindowsOpen(Runs runs) {
        assertWindowOpen(runs.from());
        assertWindowOpen(runs.to());
    }

    /** 이미 대기 중인 이동이 있으면 막는다. */
    void assertNotStaged(AuthUser requester, Student student) {
        // 예정 명단이 대기 중인 출발 이동을 이미 빼므로 중복 대기를 먼저 본다 — 순서가 바뀌면
        // TRANSFER_ALREADY_STAGED 가 STUDENT_NOT_IN_RUN 으로 가려진다.
        if (runTransferRepository.existsStagedByStudentIdAndAcademyId(student.getId(), requester.academyId())) {
            throw new BusinessException(ErrorCode.TRANSFER_ALREADY_STAGED);
        }
    }

    /** 학생이 출발 회차 명단에 있는지 확인하고 그 명단을 돌려준다. */
    ProjectedRoster readFromRoster(Run fromRun, Student student) {
        ProjectedRoster fromRoster = rosterReader.read(fromRun);
        if (!fromRoster.contains(student.getId())) {
            throw new BusinessException(ErrorCode.STUDENT_NOT_IN_RUN);
        }
        return fromRoster;
    }

    /** 도착 회차 버스의 정원에 한 명 더 들어갈 수 있는지 확인하고 이동 반영 전 인원·정원을 돌려준다. */
    TargetCapacity checkTargetCapacity(AuthUser requester, Run toRun, Student student) {
        Bus toBus = busRepository.findByIdAndAcademyId(toRun.getBusId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        ProjectedRoster toRoster = rosterReader.read(toRun);
        if (toRoster.contains(student.getId())) {
            throw new BusinessException(ErrorCode.STUDENT_ALREADY_IN_RUN);
        }
        long toCurrentCount = toRoster.size();
        if (toCurrentCount + 1 > toBus.getStudentCapacity()) {
            throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED,
                    new TransferCapacityDetail(toCurrentCount, toBus.getStudentCapacity()));
        }
        return new TargetCapacity(toCurrentCount, toBus.getStudentCapacity());
    }

    private void assertWindowOpen(Run run) {
        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock));
        if (window != ChangeWindow.IMMEDIATE) {
            // ①구간 전용(§5.8) — 둘 중 한 회차라도 ②구간이면 막는다(사양 본문).
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }
}

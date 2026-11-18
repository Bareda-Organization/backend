package src.backend.exception.report;

import java.util.Map;

import src.backend.account.entity.Account;
import src.backend.boarding.entity.RunRider;
import src.backend.bus.entity.Bus;
import src.backend.manager.entity.Manager;
import src.backend.run.entity.Run;
import src.backend.student.entity.Student;

/** 예외 보고 목록 조립에 필요한 회차·버스·보고자·처리자·탑승자·학생을 id 로 모아 둔 조회 결과. */
record ExceptionReportLookup(
        Map<Long, Run> runsById,
        Map<Long, Bus> busesById,
        Map<Long, Manager> reportersByAccountId,
        Map<Long, Account> handlersById,
        Map<Long, RunRider> ridersById,
        Map<Long, Student> studentsById) {
}

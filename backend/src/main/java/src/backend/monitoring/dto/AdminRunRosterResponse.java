package src.backend.monitoring.dto;

import java.util.List;

/**
 * 메인 관리자 콘솔의 회차별 승하차지·학생 명단(API_SPEC §6.9, O-06, 목표 10·11).
 *
 * <p>{@code photo_url} · {@code student_phone} · {@code guardian_phone} 전부 원문이다 — 마스킹하지
 * 않는다(§6.9 원문 표, 목표 10 앞항). L3 조회이므로 SYS-01 감사 대상이지만(Phase 14 구현 예정) 이
 * 태스크는 감사 로그를 남기지 않는다(p13-task-t2.md §6, 자바독 표시만).
 */
public record AdminRunRosterResponse(List<StopGroup> stops) {

    public record StopGroup(Long stopId, int seq, String name, List<Student> students) {
    }

    public record Student(Long studentId, String name, String photoUrl, String studentPhone, String guardianPhone,
            String status) {
    }
}

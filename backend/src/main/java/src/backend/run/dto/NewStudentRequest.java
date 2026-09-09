package src.backend.run.dto;

/** 강제 추가(RTE-06, API_SPEC §5.7)의 신규 학생 직접 입력 — 이름만 받는다(전체 등록 STU-01 은 별도 경로). */
public record NewStudentRequest(String name) {
}

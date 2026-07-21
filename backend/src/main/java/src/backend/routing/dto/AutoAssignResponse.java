package src.backend.routing.dto;

import java.util.List;

/** 자동 배정(제안) 결과 — 좌표가 없어 배정에서 제외된 학생은 excludedStudentNames 로 보고한다. */
public record AutoAssignResponse(List<RoutePlanResponse> plans, List<String> excludedStudentNames) {
}

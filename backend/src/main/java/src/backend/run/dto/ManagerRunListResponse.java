package src.backend.run.dto;

import java.util.List;

/**
 * 매니저 앱의 담당 회차 목록 봉투(API_SPEC §4.1 {@code GET /manager/runs}) — {@code data} 는
 * 배열이 아니라 {@code items[]} 를 담은 객체다(§4.1 "배정 회차 부재는 빈 items[] 로 반환").
 * {@code ChildListResponse}(§3 학부모 앱 자녀 목록)와 같은 관례.
 */
public record ManagerRunListResponse(List<ManagerRunResponse> items) {

    /** 회차 카드 목록을 {@code items[]} 봉투로 감싼다. */
    public static ManagerRunListResponse from(List<ManagerRunResponse> items) {
        return new ManagerRunListResponse(items);
    }
}

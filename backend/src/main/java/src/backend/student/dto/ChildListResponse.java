package src.backend.student.dto;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.student.repository.LinkedChild;

/**
 * 학부모 앱의 자녀 목록 응답(ATT-03 · API_SPEC §3.1).
 *
 * <p><b>{@code photo_url} 이 부재한 것이 사양이다</b>(§1.12 · ERD {@code student}) — 사진은 매니저
 * 앱 · 관계자 웹 · 메인 관리자 콘솔에만 반환한다. 주소 원문·좌표도 담지 않는다.
 *
 * <p>{@code StudentSummaryResponse}(관계자 웹)를 재사용하지 않고 역할별로 가른 이유가 그것이다
 * (횡단 규칙 8·14) — 한 타입을 공유하면 관계자 화면에 필드가 하나 늘 때 학부모 앱에 자동으로 열린다.
 */
public record ChildListResponse(List<Item> items) {

    public static ChildListResponse from(List<LinkedChild> children) {
        return new ChildListResponse(children.stream().map(Item::from).toList());
    }

    /**
     * 자녀 1명 — 식별과 선택에 필요한 최소값 4개가 §3.1 이 정한 전부다.
     *
     * @param name 알림 문구에 필수로 들어가는 값이라 비어 있을 수 없다(ATT-03)
     */
    public record Item(Long studentId, String name, String className, OffsetDateTime linkedAt) {

        public static Item from(LinkedChild child) {
            return new Item(child.getStudentId(), child.getName(), child.getClassName(), child.getLinkedAt());
        }
    }
}

package src.backend.monitoring.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.dto.StaffAssignmentAckView;

/** 관제 조회들이 함께 쓰는 문자열 · 배치 확인 도우미 — 다섯 곳에 복사돼 있던 소문자 변환과 역할별 이름 찾기. */
class QueryLabelsTest {

    @Test
    void 소문자_변환은_로케일과_무관하다() {
        assertThat(QueryLabels.lower("TO_ACADEMY")).isEqualTo("to_academy");
        assertThat(QueryLabels.lower("TITLE")).isEqualTo("title");
    }

    @Test
    void 역할별_이름과_전화와_확인_여부를_찾는다() {
        List<StaffAssignmentAckView> acks = List.of(
                new StaffAssignmentAckView(1L, ManagerRole.DRIVER, "기사", "010-1111-0000", 7L, 7L),
                new StaffAssignmentAckView(1L, ManagerRole.ESCORT, "동승", "010-2222-0000", 6L, 7L));

        assertThat(QueryLabels.nameOf(acks, ManagerRole.DRIVER)).isEqualTo("기사");
        assertThat(QueryLabels.nameOf(acks, ManagerRole.ESCORT)).isEqualTo("동승");
        assertThat(QueryLabels.phoneOf(acks, ManagerRole.ESCORT)).isEqualTo("010-2222-0000");
        assertThat(QueryLabels.ackedOf(acks, ManagerRole.DRIVER)).isTrue();
        assertThat(QueryLabels.ackedOf(acks, ManagerRole.ESCORT)).isFalse();
    }

    @Test
    void 없는_역할은_이름과_전화가_null_이고_확인은_거짓이다() {
        List<StaffAssignmentAckView> onlyDriver = List.of(
                new StaffAssignmentAckView(1L, ManagerRole.DRIVER, "기사", "010-1111-0000", 7L, 7L));

        assertThat(QueryLabels.nameOf(onlyDriver, ManagerRole.ESCORT)).isNull();
        assertThat(QueryLabels.phoneOf(onlyDriver, ManagerRole.ESCORT)).isNull();
        assertThat(QueryLabels.ackedOf(onlyDriver, ManagerRole.ESCORT)).isFalse();
    }
}

package src.backend.routing.assign.spec;

import java.util.Objects;

/** 왜 배정되지 않았는지 — 화면이 이유를 표시해야 관계자가 손으로 고칠 수 있다. */
public record AssignRejection(long managerId, RejectReason reason) {

    public AssignRejection {
        Objects.requireNonNull(reason, "거절 사유가 없다");
    }
}

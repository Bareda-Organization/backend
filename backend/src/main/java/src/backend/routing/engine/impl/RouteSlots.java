package src.backend.routing.engine.impl;

import java.util.ArrayList;
import java.util.List;

import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 고정 순번을 미리 잡아 둔 자리표 — 최적화는 <b>비어 있는 자리만</b> 건드린다.
 *
 * <p>"고정 정차지를 뺀 뒤 계산하고 나중에 끼워 넣는" 방식을 쓰지 않은 이유는 끼워 넣는 자리가
 * 이동을 만들기 때문이다. 자리를 먼저 잡아 두면 고정 자리는 어느 단계에서도 후보에 오르지 않아,
 * 고정을 지키는 일이 별도 보정 단계 없이 성립한다.
 */
final class RouteSlots {

    private final Slot[] plan;
    private final boolean[] fixed;

    private RouteSlots(int size) {
        this.plan = new Slot[size];
        this.fixed = new boolean[size];
    }

    /** 고정 순번이 자리 수를 벗어나거나 겹치면 {@link IllegalArgumentException}. */
    static RouteSlots of(RouteOrderInput input) {
        RouteSlots slots = new RouteSlots(input.totalStopCount());
        for (FixedStop fixedStop : input.fixedStops()) {
            slots.reserve(fixedStop);
        }
        return slots;
    }

    int size() {
        return plan.length;
    }

    boolean isFixed(int index) {
        return fixed[index];
    }

    Slot at(int index) {
        return plan[index];
    }

    void place(int index, Slot slot) {
        plan[index] = slot;
    }

    /** {@code from}~{@code to} 구간을 뒤집는다 — 2-opt 의 유일한 변형 수단이다. */
    void reverse(int from, int to) {
        for (int left = from, right = to; left < right; left++, right--) {
            Slot swap = plan[left];
            plan[left] = plan[right];
            plan[right] = swap;
        }
    }

    StopOrder toStopOrder() {
        List<OrderedStop> sequence = new ArrayList<>(plan.length);
        for (int index = 0; index < plan.length; index++) {
            Slot slot = plan[index];
            sequence.add(new OrderedStop(slot.stopId(), slot.waypointId(), index + 1, slot.point()));
        }
        return new StopOrder(sequence);
    }

    private void reserve(FixedStop fixedStop) {
        int index = fixedStop.seq() - 1;
        if (index >= plan.length) {
            throw new IllegalArgumentException(
                    "고정 순번이 자리 수를 벗어난다: " + fixedStop.seq() + " > " + plan.length);
        }
        if (fixed[index]) {
            throw new IllegalArgumentException("고정 순번이 겹친다: " + fixedStop.seq());
        }
        plan[index] = new Slot(null, fixedStop.waypointId(), fixedStop.point());
        fixed[index] = true;
    }
}

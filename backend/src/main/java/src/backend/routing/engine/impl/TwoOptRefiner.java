package src.backend.routing.engine.impl;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.RouteOrderInput;

/**
 * 초기 해에서 <b>구간을 뒤집어</b> 거리를 줄인다 — 최근접 이웃이 남기는 교차를 걷어내는 단계다.
 *
 * <p>고정 자리를 <b>넘어서</b> 뒤집지 않는다. 뒤집기는 구간 안의 모든 자리를 옮기므로, 고정 자리를
 * 품은 구간을 한 번이라도 뒤집으면 관리자가 지정한 순번이 사라진다(ARCHITECTURE §8.2).
 *
 * <p>매 회차마다 <b>가장 많이 줄이는</b> 한 수만 두고 다시 훑는다. 찾는 즉시 두는 방식보다 느리지만,
 * 훑는 차례가 결과를 바꾸지 않아 같은 입력이 언제나 같은 순서를 낸다.
 */
final class TwoOptRefiner {

    /** 부동소수 잔차를 개선으로 오인해 같은 구간을 무한히 뒤집는 것을 막는 문턱(m). */
    private static final double MIN_GAIN_METERS = 1e-9;

    private TwoOptRefiner() {}

    static void refine(RouteSlots slots, RouteOrderInput input, int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            Reversal best = bestReversal(slots, input);
            if (best.gainMeters() <= MIN_GAIN_METERS) {
                return;
            }
            slots.reverse(best.from(), best.to());
        }
    }

    private static Reversal bestReversal(RouteSlots slots, RouteOrderInput input) {
        Reversal best = Reversal.none();
        for (int from = 0; from < slots.size() - 1; from++) {
            best = betterOf(best, bestReversalStartingAt(slots, input, from));
        }
        return best;
    }

    private static Reversal bestReversalStartingAt(
            RouteSlots slots, RouteOrderInput input, int from) {
        if (slots.isFixed(from)) {
            return Reversal.none();
        }
        Reversal best = Reversal.none();
        for (int to = from + 1; to < slots.size() && !slots.isFixed(to); to++) {
            best = betterOf(best, new Reversal(from, to, gainMetersOf(slots, input, from, to)));
        }
        return best;
    }

    /** 이득이 같으면 앞선 구간을 고른다 — 훑는 차례가 아니라 값이 정하도록 남긴다. */
    private static Reversal betterOf(Reversal current, Reversal candidate) {
        return candidate.gainMeters() > current.gainMeters() ? candidate : current;
    }

    /** 구간을 뒤집으면 양 끝 두 변만 갈린다 — 안쪽 변은 방향만 바뀌고 길이가 같다. */
    private static double gainMetersOf(
            RouteSlots slots, RouteOrderInput input, int from, int to) {
        GeoPoint before = TourCost.pointAt(slots, input, from - 1);
        GeoPoint first = TourCost.pointAt(slots, input, from);
        GeoPoint last = TourCost.pointAt(slots, input, to);
        GeoPoint after = TourCost.pointAt(slots, input, to + 1);
        return before.distanceMetersTo(first) + last.distanceMetersTo(after)
                - before.distanceMetersTo(last) - first.distanceMetersTo(after);
    }

    /** 뒤집기 후보 한 건 — {@code gainMeters} 가 0 이하면 둘 수를 못 찾았다는 뜻이다. */
    private record Reversal(int from, int to, double gainMeters) {

        private static Reversal none() {
            return new Reversal(-1, -1, 0d);
        }
    }
}

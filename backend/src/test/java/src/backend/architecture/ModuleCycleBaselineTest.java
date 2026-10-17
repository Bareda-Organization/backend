package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * 모듈 3개 이상이 고리를 이루는 순환을 막는다(AR-4) — {@link ModuleMutualDependencyTest} 는 서로 import 하는 <b>쌍</b>만 보므로 A→B→C→A 는
 * 초록이다. 모듈 import 그래프에서 서로 닿는 묶음(강연결 요소) 안의 간선은 전부 어떤 고리 위에 있으므로, 그 간선 집합을 현재 값으로 고정하고
 * <b>새 간선이 묶음 안에 생기면 실패</b>한다(새 고리). 기준치에만 있고 이미 끊어진 간선은 실패시키지 않고 경고로 알린다 — 줄어드는 쪽은 막지
 * 않는다. 줄어들면 아래 목록에서 지운다.
 */
class ModuleCycleBaselineTest {

    /** 현재 순환 묶음 안의 간선 {@code 앞→뒤}(2026-10-17, student→routing 제거 뒤) — 이 목록에 없는 간선이 묶음 안에 생기면 새 고리다. */
    private static final Set<String> BASELINE_CYCLE_EDGES = Set.of(
            "academy→account", "academy→run", "academy→student",
            "account→academy", "account→audit", "account→manager", "account→student",
            "audit→academy", "audit→account",
            "boarding→academy", "boarding→audit", "boarding→exception", "boarding→manager", "boarding→routing", "boarding→run", "boarding→student",
            "exception→academy", "exception→account", "exception→boarding", "exception→location", "exception→manager", "exception→run", "exception→student",
            "location→routing", "location→run", "location→student",
            "manager→run",
            "request→academy", "request→account", "request→boarding", "request→manager", "request→routing", "request→run", "request→student",
            "routing→academy", "routing→boarding", "routing→manager", "routing→request", "routing→run", "routing→student",
            "run→academy", "run→account", "run→audit", "run→boarding", "run→manager", "run→request", "run→routing", "run→student",
            "student→academy", "student→account", "student→audit", "student→boarding", "student→location", "student→manager", "student→request", "student→run");

    @Test
    void 순환_묶음_안에_기준치에_없는_새_간선이_생기지_않는다() throws IOException {
        Set<String> actual = cycleEdges(ModuleMutualDependencyTest.importedModulesByModule());

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(BASELINE_CYCLE_EDGES);
        Set<String> stale = new TreeSet<>(BASELINE_CYCLE_EDGES);
        stale.removeAll(actual);
        if (!stale.isEmpty()) {
            System.err.println("[ModuleCycleBaselineTest] 이미 끊어진 간선이 기준치에 남아 있다 — 목록에서 지운다: " + stale);
        }
        assertThat(added).as("순환 묶음 안의 새 간선 — 새 고리다. 한쪽을 이벤트·포트로 끊는다(허용 방향은 ARCHITECTURE §3.3)").isEmpty();
    }

    /** 서로 닿는 묶음(크기 2 이상) 안의 간선 {@code 앞→뒤} 전부. */
    private static Set<String> cycleEdges(Map<String, Set<String>> graph) {
        Map<String, Integer> component = stronglyConnectedComponents(graph);
        Map<Integer, Integer> sizes = new HashMap<>();
        component.values().forEach(id -> sizes.merge(id, 1, Integer::sum));
        Set<String> edges = new TreeSet<>();
        graph.forEach((from, targets) -> targets.stream()
                .filter(to -> component.get(from).equals(component.get(to)) && sizes.get(component.get(from)) > 1)
                .forEach(to -> edges.add(from + "→" + to)));
        return edges;
    }

    /** Tarjan — 모듈 → 묶음 번호. */
    private static Map<String, Integer> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Set<String> nodes = new TreeSet<>(graph.keySet());
        graph.values().forEach(nodes::addAll);
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Integer> component = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] counter = {0, 0};
        for (String node : nodes) {
            if (!index.containsKey(node)) {
                visit(node, graph, index, low, component, stack, counter);
            }
        }
        return component;
    }

    private static void visit(String node, Map<String, Set<String>> graph, Map<String, Integer> index,
            Map<String, Integer> low, Map<String, Integer> component, Deque<String> stack, int[] counter) {
        index.put(node, counter[0]);
        low.put(node, counter[0]++);
        stack.push(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (!index.containsKey(next)) {
                visit(next, graph, index, low, component, stack, counter);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (!component.containsKey(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            List<String> members = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                members.add(member);
                component.put(member, counter[1]);
            } while (!member.equals(node));
            counter[1]++;
        }
    }
}

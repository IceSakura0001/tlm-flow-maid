package dev.tlmprogrammable.flow;

import java.util.*;
import static dev.tlmprogrammable.flow.FlowGraph.*;

/** Pure diagram operations; never mutate execution edges, settings or node identities. */
public final class FlowDiagram {
    public static final int WIDTH = 166, HEIGHT = 48;
    public enum Layout {
        VERTICAL("上下主干分支"), HORIZONTAL("左右主干分支"), COMPACT("紧凑分层");
        public final String label;
        Layout(String label) { this.label = label; }
    }
    public record Point(int x, int y) { }
    private FlowDiagram() { }
    public static FlowGraph move(FlowGraph graph, Set<String> ids, int dx, int dy) {
        return new FlowGraph(graph.id(), graph.name(), graph.nodes().stream().map(node -> ids.contains(node.id())
                ? node.at(node.x() + dx, node.y() + dy) : node).toList(), graph.edges());
    }
    public static FlowGraph restorePositions(FlowGraph graph, Map<String, Point> positions) {
        return new FlowGraph(graph.id(), graph.name(), graph.nodes().stream().map(node -> {
            Point point = positions.get(node.id()); return point == null ? node : node.at(point.x(), point.y());
        }).toList(), graph.edges());
    }
    public static FlowGraph arrange(FlowGraph graph, Layout layout) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        graph.edges().stream().sorted(Comparator.comparingInt(edge -> edge.port() == Port.NO ? 1 : 0))
                .forEach(edge -> children.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to()));
        Map<String, Integer> state = new HashMap<>(), lanes = new HashMap<>();
        Set<String> back = new HashSet<>(); int[] nextLane = {0};
        String start = graph.nodes().stream().filter(node -> node.kind() == Kind.START).findFirst().orElseThrow().id();
        visit(start, 0, children, state, lanes, back, nextLane);
        for (Node node : graph.nodes()) if (!state.containsKey(node.id())) visit(node.id(), ++nextLane[0], children, state, lanes, back, nextLane);
        Map<String, Integer> indegree = new HashMap<>(), ranks = new HashMap<>();
        graph.nodes().forEach(node -> { indegree.put(node.id(), 0); ranks.put(node.id(), 0); });
        for (Edge edge : graph.edges()) if (!back.contains(edge.from() + "/" + edge.to())) indegree.merge(edge.to(), 1, Integer::sum);
        ArrayDeque<String> queue = new ArrayDeque<>();
        graph.nodes().forEach(node -> { if (indegree.get(node.id()) == 0) queue.add(node.id()); });
        while (!queue.isEmpty()) {
            String parent = queue.removeFirst();
            for (String child : children.getOrDefault(parent, List.of())) {
                if (back.contains(parent + "/" + child)) continue;
                ranks.merge(child, ranks.get(parent) + 1, Math::max);
                if (indegree.merge(child, -1, Integer::sum) == 0) queue.add(child);
            }
        }
        Map<String, Point> positions = new HashMap<>();
        Map<Integer, List<Node>> layers = new TreeMap<>();
        graph.nodes().forEach(node -> layers.computeIfAbsent(ranks.get(node.id()), key -> new ArrayList<>()).add(node));
        for (var layer : layers.entrySet()) {
            layer.getValue().sort(Comparator.comparingInt(node -> lanes.get(node.id())));
            for (int index = 0; index < layer.getValue().size(); index++) {
                Node node = layer.getValue().get(index); int rank = layer.getKey(), lane = lanes.get(node.id());
                Point point = switch (layout) {
                    case VERTICAL -> new Point(300 + lane * 228, 96 + rank * 120);
                    case HORIZONTAL -> new Point(300 + rank * 252, 96 + lane * 108);
                    case COMPACT -> new Point(300 + index * 192, 96 + rank * 84);
                };
                positions.put(node.id(), point);
            }
        }
        return restorePositions(graph, positions);
    }
    private static void visit(String id, int lane, Map<String, List<String>> children, Map<String, Integer> state,
                              Map<String, Integer> lanes, Set<String> back, int[] nextLane) {
        state.put(id, 1); lanes.put(id, lane); boolean first = true;
        for (String child : children.getOrDefault(id, List.of())) {
            if (state.getOrDefault(child, 0) == 1) { back.add(id + "/" + child); continue; }
            if (!state.containsKey(child)) { visit(child, first ? lane : ++nextLane[0], children, state, lanes, back, nextLane); first = false; }
        }
        state.put(id, 2);
    }
    public static int outputX(Node node, Port port) {
        return node.x() + (node.kind().family() == Family.CONDITION ? (port == Port.YES ? WIDTH / 3 : WIDTH * 2 / 3) : WIDTH / 2);
    }
    /** Bounded orthogonal A* on obstacle boundary coordinates. Empty means no safe route; never draw through a card. */
    public static List<Point> route(FlowGraph graph, Edge edge, boolean avoid) {
        Node from = graph.nodes().stream().filter(node -> node.id().equals(edge.from())).findFirst().orElseThrow();
        Node to = graph.nodes().stream().filter(node -> node.id().equals(edge.to())).findFirst().orElseThrow();
        Point start = new Point(outputX(from, edge.port()), from.y() + HEIGHT), end = new Point(to.x() + WIDTH / 2, to.y());
        if (!avoid) { int bend = start.y() + (end.y() - start.y()) / 2; return List.of(start, new Point(start.x(), bend), new Point(end.x(), bend), end); }
        Point exit = new Point(start.x(), start.y() + 12), entry = new Point(end.x(), end.y() - 12);
        TreeSet<Integer> xs = new TreeSet<>(), ys = new TreeSet<>();
        xs.add(exit.x()); xs.add(entry.x()); ys.add(exit.y()); ys.add(entry.y());
        for (Node node : graph.nodes()) { xs.add(node.x() - 12); xs.add(node.x() + WIDTH + 12); ys.add(node.y() - 12); ys.add(node.y() + HEIGHT + 12); }
        int[] xx = xs.stream().mapToInt(Integer::intValue).toArray(), yy = ys.stream().mapToInt(Integer::intValue).toArray();
        int columns = xx.length, rows = yy.length, size = columns * rows;
        int first = Arrays.binarySearch(yy, exit.y()) * columns + Arrays.binarySearch(xx, exit.x());
        int last = Arrays.binarySearch(yy, entry.y()) * columns + Arrays.binarySearch(xx, entry.x());
        long[] costs = new long[size]; int[] previous = new int[size]; Arrays.fill(costs, Long.MAX_VALUE); Arrays.fill(previous, -1);
        record Search(int id, long cost, long score) { }
        PriorityQueue<Search> open = new PriorityQueue<>(Comparator.comparingLong(Search::score)); costs[first] = 0; open.add(new Search(first, 0, 0));
        int budget = 12000;
        while (!open.isEmpty() && budget-- > 0) {
            Search current = open.remove(); int id = current.id(); if (current.cost() != costs[id]) continue;
            if (id == last) break;
            int cx = id % columns, cy = id / columns;
            for (int direction = 0; direction < 4; direction++) {
                int nx = cx + (direction == 0 ? -1 : direction == 1 ? 1 : 0), ny = cy + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                if (nx < 0 || nx >= columns || ny < 0 || ny >= rows) continue;
                Point a = new Point(xx[cx], yy[cy]), b = new Point(xx[nx], yy[ny]);
                if (intersects(graph, a, b, null, null)) continue;
                int next = ny * columns + nx;
                long distance = Math.abs((long) a.x() - b.x()) + Math.abs((long) a.y() - b.y());
                long cost = current.cost() + distance;
                if (cost >= costs[next]) continue;
                costs[next] = cost; previous[next] = id;
                open.add(new Search(next, cost, cost + Math.abs((long) xx[nx] - entry.x()) + Math.abs((long) yy[ny] - entry.y())));
            }
        }
        if (costs[last] == Long.MAX_VALUE || intersects(graph, start, exit, from.id(), null) || intersects(graph, entry, end, to.id(), null)) return List.of();
        List<Point> reverse = new ArrayList<>();
        for (int at = last; at != -1; at = previous[at]) reverse.add(new Point(xx[at % columns], yy[at / columns]));
        Collections.reverse(reverse); List<Point> points = new ArrayList<>(); points.add(start); points.addAll(reverse); points.add(end);
        // Remove redundant collinear points for rendering and accurate line hit testing.
        for (int i = points.size() - 2; i > 0; i--) {
            Point a = points.get(i - 1), b = points.get(i), c = points.get(i + 1);
            if (a.x() == b.x() && b.x() == c.x() || a.y() == b.y() && b.y() == c.y()) points.remove(i);
        }
        return List.copyOf(points);
    }
    public static boolean intersects(FlowGraph graph, Point a, Point b, String ignoreA, String ignoreB) {
        for (Node node : graph.nodes()) {
            if (node.id().equals(ignoreA) || node.id().equals(ignoreB)) continue;
            if (a.x() == b.x()) {
                if (a.x() > node.x() - 4 && a.x() < node.x() + WIDTH + 4
                        && Math.max(a.y(), b.y()) > node.y() - 4 && Math.min(a.y(), b.y()) < node.y() + HEIGHT + 4) return true;
            } else if (a.y() > node.y() - 4 && a.y() < node.y() + HEIGHT + 4
                    && Math.max(a.x(), b.x()) > node.x() - 4 && Math.min(a.x(), b.x()) < node.x() + WIDTH + 4) return true;
        }
        return false;
    }
}

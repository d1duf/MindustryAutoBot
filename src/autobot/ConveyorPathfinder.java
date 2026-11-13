package autobot;

import arc.math.geom.Point2;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.PriorityQueue;

public class ConveyorPathfinder {

    public static class RouteResult {
        public final Seq<BuildPlan> plans = new Seq<>();
        public final Seq<Point2> conveyorTiles = new Seq<>();
        public int conveyorsCount = 0;
        public int junctionsCount = 0;
    }

    private static class Node implements Comparable<Node> {
        int x, y;
        float g, f;
        Node parent;
        boolean isJunction;

        Node(int x, int y, float g, float f, Node parent, boolean isJunction) {
            this.x = x;
            this.y = y;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.isJunction = isJunction;
        }

        @Override
        public int compareTo(Node other) {
            return Float.compare(this.f, other.f);
        }
    }

    public static RouteResult planRouteFromOutlet(
            int outletX, int outletY, Point2 incomingDir,
            CoreBuild core, IntSet plannedTiles,
            LogisticsManager.ConveyorLine targetLine) {

        boolean mergeIntoExisting = !targetLine.tiles.isEmpty();
        IntSet targetLineSet = new IntSet();
        if (mergeIntoExisting) {
            for (Point2 p : targetLine.tiles) {
                targetLineSet.add(pack(p.x, p.y));
            }
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        IntMap<Node> allNodes = new IntMap<>();

        Point2 heuristicGoal = mergeIntoExisting
                ? findClosestPoint(outletX, outletY, targetLine.tiles)
                : new Point2(core.tileX(), core.tileY());

        int[] dirX = {1, 0, -1, 0};
        int[] dirY = {0, 1, 0, -1};

        for (int i = 0; i < 4; i++) {
            if (incomingDir != null && dirX[i] == incomingDir.x && dirY[i] == incomingDir.y) {
                continue;
            }
            int sx = outletX + dirX[i];
            int sy = outletY + dirY[i];

            if (isFreeTile(sx, sy, plannedTiles)) {
                float h = Math.abs(sx - heuristicGoal.x) + Math.abs(sy - heuristicGoal.y);
                Node start = new Node(sx, sy, 0f, h, null, false);
                open.add(start);
                allNodes.put(pack(sx, sy), start);
            }
        }

        Node endNode = null;
        int iterations = 0;

        // Увеличен лимит итераций до 25000 для обхода длинных стен и коридоров
        while (!open.isEmpty() && iterations < 25000) {
            iterations++;
            Node curr = open.poll();

            if (mergeIntoExisting) {
                if (!curr.isJunction && isAdjacentToSet(curr.x, curr.y, targetLineSet)) {
                    endNode = curr;
                    break;
                }
            } else {
                if (!curr.isJunction && isAdjacentToCore(curr.x, curr.y, core)) {
                    endNode = curr;
                    break;
                }
            }

            for (int i = 0; i < 4; i++) {
                int nx = curr.x + dirX[i];
                int ny = curr.y + dirY[i];

                if (curr.isJunction && curr.parent != null) {
                    int prevDirX = curr.x - curr.parent.x;
                    int prevDirY = curr.y - curr.parent.y;
                    if (dirX[i] != prevDirX || dirY[i] != prevDirY) {
                        continue;
                    }
                }

                if (isFreeTile(nx, ny, plannedTiles)) {
                    float stepCost = 1.0f;
                    if (curr.parent != null && (nx - curr.x != curr.x - curr.parent.x || ny - curr.y != curr.y - curr.parent.y)) {
                        stepCost += 0.3f;
                    }

                    if (!mergeIntoExisting && isInCoreBuffer(nx, ny, core, 3) && !isDirectCoreApproach(nx, ny, core)) {
                        stepCost += 4.0f;
                    }

                    float newG = curr.g + stepCost;
                    int key = pack(nx, ny);
                    Node existing = allNodes.get(key);
                    if (existing == null || newG < existing.g) {
                        float h = Math.abs(nx - heuristicGoal.x) + Math.abs(ny - heuristicGoal.y);
                        Node next = new Node(nx, ny, newG, newG + h, curr, false);
                        allNodes.put(key, next);
                        open.add(next);
                    }
                } else if (!curr.isJunction && canCrossWithJunction(nx, ny, dirX[i], dirY[i], plannedTiles, targetLineSet)) {
                    float newG = curr.g + 4.5f;
                    int key = pack(nx, ny);
                    Node existing = allNodes.get(key);
                    if (existing == null || newG < existing.g) {
                        float h = Math.abs(nx - heuristicGoal.x) + Math.abs(ny - heuristicGoal.y);
                        Node next = new Node(nx, ny, newG, newG + h, curr, true);
                        allNodes.put(key, next);
                        open.add(next);
                    }
                }
            }
        }

        if (endNode == null) {
            return null;
        }

        Seq<Node> path = new Seq<>();
        Node trace = endNode;
        while (trace != null) {
            path.add(trace);
            trace = trace.parent;
        }
        path.reverse();

        RouteResult result = new RouteResult();

        for (int i = 0; i < path.size; i++) {
            Node node = path.get(i);
            plannedTiles.add(pack(node.x, node.y));

            if (node.isJunction) {
                result.plans.add(new BuildPlan(node.x, node.y, 0, Blocks.junction));
                result.junctionsCount++;
            } else {
                int rot;
                if (i < path.size - 1) {
                    Node next = path.get(i + 1);
                    rot = getRotation(node.x, node.y, next.x, next.y);
                } else if (mergeIntoExisting) {
                    rot = getRotationToSet(node.x, node.y, targetLineSet);
                } else {
                    rot = getRotationToCore(node.x, node.y, core);
                }
                result.plans.add(new BuildPlan(node.x, node.y, rot, Blocks.conveyor));
                result.conveyorTiles.add(new Point2(node.x, node.y));
                result.conveyorsCount++;
            }
        }

        return result;
    }

    public static boolean isInCoreBuffer(int x, int y, CoreBuild core, int radius) {
        int size = core.block.size;
        int halfLow = (size - 1) / 2;
        int halfHigh = size / 2;
        int minX = core.tileX() - halfLow - radius;
        int maxX = core.tileX() + halfHigh + radius;
        int minY = core.tileY() - halfLow - radius;
        int maxY = core.tileY() + halfHigh + radius;
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    private static boolean isDirectCoreApproach(int x, int y, CoreBuild core) {
        int size = core.block.size;
        int halfLow = (size - 1) / 2;
        int halfHigh = size / 2;
        int cMinX = core.tileX() - halfLow;
        int cMaxX = core.tileX() + halfHigh;
        int cMinY = core.tileY() - halfLow;
        int cMaxY = core.tileY() + halfHigh;
        return (x >= cMinX && x <= cMaxX) || (y >= cMinY && y <= cMaxY);
    }

    private static boolean isFreeTile(int x, int y, IntSet plannedTiles) {
        if (plannedTiles.contains(pack(x, y))) return false;
        Tile t = Vars.world.tile(x, y);
        if (t == null) return false;
        if (t.build != null && t.team() == Vars.player.team()) return false;
        return Build.validPlace(Blocks.conveyor, Vars.player.team(), x, y, 0);
    }

    private static boolean canCrossWithJunction(int x, int y, int dx, int dy, IntSet plannedTiles, IntSet targetLineSet) {
        if (plannedTiles.contains(pack(x, y))) return false;
        if (targetLineSet.contains(pack(x, y))) return false;

        Tile t = Vars.world.tile(x, y);
        if (t == null || t.team() != Vars.player.team() || !(t.block() instanceof Conveyor)) {
            return false;
        }

        int existingRot = t.build.rotation;
        boolean existingHorizontal = (existingRot == 0 || existingRot == 2);
        boolean movingHorizontal = (dx != 0);
        if (existingHorizontal == movingHorizontal) {
            return false;
        }

        int afterX = x + dx;
        int afterY = y + dy;
        return isFreeTile(afterX, afterY, plannedTiles);
    }

    private static Point2 findClosestPoint(int x, int y, Seq<Point2> points) {
        Point2 best = points.first();
        int bestDist = Integer.MAX_VALUE;
        for (Point2 p : points) {
            int d = Math.abs(p.x - x) + Math.abs(p.y - y);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean isAdjacentToSet(int x, int y, IntSet set) {
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        for (int i = 0; i < 4; i++) {
            if (set.contains(pack(x + dx[i], y + dy[i]))) {
                return true;
            }
        }
        return false;
    }

    private static int getRotationToSet(int x, int y, IntSet set) {
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        for (int i = 0; i < 4; i++) {
            if (set.contains(pack(x + dx[i], y + dy[i]))) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isAdjacentToCore(int x, int y, CoreBuild core) {
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        for (int i = 0; i < 4; i++) {
            Tile t = Vars.world.tile(x + dx[i], y + dy[i]);
            if (t != null && t.build == core) {
                return true;
            }
        }
        return false;
    }

    private static int getRotationToCore(int x, int y, CoreBuild core) {
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        for (int i = 0; i < 4; i++) {
            Tile t = Vars.world.tile(x + dx[i], y + dy[i]);
            if (t != null && t.build == core) {
                return i;
            }
        }
        return 0;
    }

    private static int getRotation(int x1, int y1, int x2, int y2) {
        if (x2 > x1) return 0;
        if (y2 > y1) return 1;
        if (x2 < x1) return 2;
        return 3;
    }

    private static int pack(int x, int y) {
        return (x << 16) | (y & 0xFFFF);
    }
}
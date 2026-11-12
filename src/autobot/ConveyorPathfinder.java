package autobot;

import arc.math.geom.Point2;
import arc.struct.IntMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.PriorityQueue;

public class ConveyorPathfinder {

    private static class Node implements Comparable<Node> {
        int x, y;
        float g, f;
        Node parent;

        Node(int x, int y, float g, float f, Node parent) {
            this.x = x;
            this.y = y;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node other) {
            return Float.compare(this.f, other.f);
        }
    }

    /**
     * Ищет путь алгоритмом A* от границ бура до границ ядра и ставит планы конвейеров.
     */
    public static boolean buildConveyorLine(int drillX, int drillY, int drillSize, CoreBuild core, Unit unit) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        IntMap<Node> allNodes = new IntMap<>();

        int coreX = core.tileX();
        int coreY = core.tileY();

        // Добавляем все свободные клетки по периметру бура как стартовые точки
        for (int dx = -1; dx <= drillSize; dx++) {
            for (int dy = -1; dy <= drillSize; dy++) {
                // Пропускаем внутренность бура и углы по диагонали
                boolean isCorner = (dx == -1 || dx == drillSize) && (dy == -1 || dy == drillSize);
                boolean isInside = (dx >= 0 && dx < drillSize) && (dy >= 0 && dy < drillSize);
                if (isCorner || isInside) continue;

                int sx = drillX + dx;
                int sy = drillY + dy;
                if (canPlaceConveyor(sx, sy)) {
                    float h = Math.abs(sx - coreX) + Math.abs(sy - coreY);
                    Node startNode = new Node(sx, sy, 0, h, null);
                    open.add(startNode);
                    allNodes.put(pack(sx, sy), startNode);
                }
            }
        }

        int[] dirX = {1, 0, -1, 0}; // 0: право, 1: верх, 2: лево, 3: низ
        int[] dirY = {0, 1, 0, -1};

        Node endNode = null;
        int iterations = 0;

        while (!open.isEmpty() && iterations < 4000) {
            iterations++;
            Node current = open.poll();

            // Проверяем, коснулись ли мы ядра (соседняя клетка является частью ядра)
            if (isAdjacentToCore(current.x, current.y, core)) {
                endNode = current;
                break;
            }

            for (int i = 0; i < 4; i++) {
                int nx = current.x + dirX[i];
                int ny = current.y + dirY[i];

                // Не заходим на территорию самого планируемого бура
                if (nx >= drillX && nx < drillX + drillSize && ny >= drillY && ny < drillY + drillSize) {
                    continue;
                }

                if (!canPlaceConveyor(nx, ny)) continue;

                float newG = current.g + 1f;
                int key = pack(nx, ny);
                Node existing = allNodes.get(key);

                if (existing == null || newG < existing.g) {
                    float h = Math.abs(nx - coreX) + Math.abs(ny - coreY);
                    Node next = new Node(nx, ny, newG, newG + h, current);
                    allNodes.put(key, next);
                    open.add(next);
                }
            }
        }

        if (endNode == null) return false;

        // Восстанавливаем путь от ядра к буру
        Seq<Point2> path = new Seq<>();
        Node curr = endNode;
        while (curr != null) {
            path.add(new Point2(curr.x, curr.y));
            curr = curr.parent;
        }
        path.reverse();

        // Ставим конвейеры с правильным поворотом
        for (int i = 0; i < path.size; i++) {
            Point2 p = path.get(i);
            int rot;
            if (i < path.size - 1) {
                Point2 next = path.get(i + 1);
                rot = getRotation(p.x, p.y, next.x, next.y);
            } else {
                // Последний конвейер смотрит прямо в ядро
                rot = getRotationToCore(p.x, p.y, core);
            }
            unit.addBuild(new BuildPlan(p.x, p.y, rot, Blocks.conveyor));
        }

        return true;
    }

    private static boolean canPlaceConveyor(int x, int y) {
        Tile t = Vars.world.tile(x, y);
        if (t == null) return false;
        return Build.validPlace(Blocks.conveyor, Vars.player.team(), x, y, 0);
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
        if (x2 > x1) return 0; // Право
        if (y2 > y1) return 1; // Верх
        if (x2 < x1) return 2; // Лево
        return 3;              // Низ
    }

    private static int pack(int x, int y) {
        return (x << 16) | (y & 0xFFFF);
    }
}
package autobot;

import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class MiningManager {
    private static final float BASE_TILE_RATE = 0.09f;
    private static final float MAX_VEIN_CAP_RATE = 10.4f;

    private static final int COST_DRILL_COPPER = 12;
    private static final int COST_BRIDGE_COPPER = 6;
    private static final int COST_BRIDGE_LEAD = 6;
    private static final int COST_CONVEYOR_COPPER = 1;
    private static final int COST_JUNCTION_COPPER = 3;

    public static class VeinDevelopmentProject {
        public final Item targetOre;
        public final Seq<BuildPlan> allPlans = new Seq<>();
        public final int copperCost;
        public final int leadCost;
        public final float expectedRate;
        public final String summary;

        public LogisticsManager.ConveyorLine line1;
        public ConveyorPathfinder.RouteResult route1;
        public float rate1;

        public LogisticsManager.ConveyorLine line2;
        public ConveyorPathfinder.RouteResult route2;
        public float rate2;

        public VeinDevelopmentProject(Item targetOre, int copperCost, int leadCost, float expectedRate, String summary) {
            this.targetOre = targetOre;
            this.copperCost = copperCost;
            this.leadCost = leadCost;
            this.expectedRate = expectedRate;
            this.summary = summary;
        }

        public void commitToUnit(Unit unit) {
            if (line1 != null && route1 != null) {
                line1.tiles.addAll(route1.conveyorTiles);
                line1.currentRate += rate1;
            }
            if (line2 != null && route2 != null) {
                line2.tiles.addAll(route2.conveyorTiles);
                line2.currentRate += rate2;
            }
            for (BuildPlan plan : allPlans) {
                unit.addBuild(plan);
            }
            BotLogger.info("Committed blueprint: " + summary + " [Cost: " + copperCost + " Copper, " + leadCost + " Lead]");
        }
    }

    private static class VeinCandidate {
        final Seq<Tile> tiles;
        final float minDstToCore;

        VeinCandidate(Seq<Tile> tiles, float minDstToCore) {
            this.tiles = tiles;
            this.minDstToCore = minDstToCore;
        }
    }

    public static float calculateProductionRate(Item targetOre) {
        float totalRate = 0f;
        Team team = Vars.player.team();

        for (Tile tile : Vars.world.tiles) {
            if (tile.build instanceof DrillBuild && tile.team() == team && tile.build.tile == tile) {
                Drill drill = (Drill) tile.block();
                int oreTiles = countOreTiles(tile, drill, targetOre);
                if (oreTiles > 0) {
                    totalRate += oreTiles * BASE_TILE_RATE;
                }
            }
        }
        return totalRate;
    }

    public static VeinDevelopmentProject planNextVein(Item targetOre, CoreBuild core, float neededRemainingRate) {
        Seq<VeinCandidate> candidates = findCandidateVeins(targetOre, core);
        if (candidates.isEmpty()) {
            return null;
        }

        float effectiveMaxRate = Math.min(MAX_VEIN_CAP_RATE, neededRemainingRate + 1.0f);

        // Перебираем жилы строго от самой близкой к более далёким.
        // Если ближайшая жила заблокирована стенами или к ней нет пути, пробуем следующую!
        for (VeinCandidate candidateVein : candidates) {
            VeinDevelopmentProject project = tryPlanForVein(candidateVein.tiles, targetOre, core, effectiveMaxRate);
            if (project != null) {
                return project;
            }
        }

        return null;
    }

    private static VeinDevelopmentProject tryPlanForVein(Seq<Tile> vein, Item targetOre, CoreBuild core, float effectiveMaxRate) {
        IntSet areaSet = new IntSet();
        Seq<Tile> expandedArea = new Seq<>();
        for (Tile t : vein) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Tile near = t.nearby(dx, dy);
                    if (near != null && !near.solid() && areaSet.add(near.pos())) {
                        expandedArea.add(near);
                    }
                }
            }
        }

        Point2 dirToCore = getPrimaryDirectionToCore(vein.first(), core);

        VeinPlan bestPlan = null;
        for (int ox = 0; ox < 3; ox++) {
            for (int oy = 0; oy < 3; oy++) {
                VeinPlan candidate = buildPinwheelPlan(expandedArea, targetOre, ox, oy, dirToCore, core, effectiveMaxRate);
                if (candidate != null && (bestPlan == null || candidate.oreTilesCovered > bestPlan.oreTilesCovered)) {
                    bestPlan = candidate;
                }
            }
        }

        if (bestPlan == null || bestPlan.drills.isEmpty()) {
            return null;
        }

        float veinRate = bestPlan.oreTilesCovered * BASE_TILE_RATE;

        IntSet plannedTiles = new IntSet();
        for (Point2 d : bestPlan.drills) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    plannedTiles.add(pack(d.x + dx, d.y + dy));
                }
            }
        }
        for (Point2 b : bestPlan.bridges) {
            plannedTiles.add(pack(b.x, b.y));
        }

        Point2 outlet = bestPlan.outlet;
        Point2 incomingDir = null;
        for (Point2 b : bestPlan.bridges) {
            Point2 target = bestPlan.bridgeTarget.get(pack(b.x, b.y));
            if (target != null && target.x == outlet.x && target.y == outlet.y) {
                int dx = Integer.compare(b.x, outlet.x);
                int dy = Integer.compare(b.y, outlet.y);
                incomingDir = new Point2(dx, dy);
                break;
            }
        }

        int totalConveyors = 0;
        int totalJunctions = 0;

        LogisticsManager.ConveyorLine line1;
        ConveyorPathfinder.RouteResult route1;
        float rate1;

        LogisticsManager.ConveyorLine line2 = null;
        ConveyorPathfinder.RouteResult route2 = null;
        float rate2 = 0f;

        if (veinRate <= 6.5f) {
            rate1 = veinRate;
            line1 = LogisticsManager.getOrCreateTargetLine(targetOre, rate1);
            route1 = ConveyorPathfinder.planRouteFromOutlet(outlet.x, outlet.y, incomingDir, core, plannedTiles, line1);
            if (route1 == null) {
                return null;
            }
            totalConveyors += route1.conveyorsCount;
            totalJunctions += route1.junctionsCount;
        } else {
            rate1 = 5.0f;
            rate2 = veinRate - 5.0f;

            line1 = LogisticsManager.getOrCreateTargetLine(targetOre, rate1);
            route1 = ConveyorPathfinder.planRouteFromOutlet(outlet.x, outlet.y, incomingDir, core, plannedTiles, line1);
            if (route1 == null) {
                return null;
            }
            totalConveyors += route1.conveyorsCount;
            totalJunctions += route1.junctionsCount;

            line2 = LogisticsManager.getOrCreateTargetLine(targetOre, rate2);
            route2 = ConveyorPathfinder.planRouteFromOutlet(outlet.x, outlet.y, incomingDir, core, plannedTiles, line2);
            if (route2 != null) {
                totalConveyors += route2.conveyorsCount;
                totalJunctions += route2.junctionsCount;
            }
        }

        int drillsCount = bestPlan.drills.size;
        int bridgesCount = bestPlan.bridges.size;

        int totalCopperCost = (drillsCount * COST_DRILL_COPPER)
                + (bridgesCount * COST_BRIDGE_COPPER)
                + (totalConveyors * COST_CONVEYOR_COPPER)
                + (totalJunctions * COST_JUNCTION_COPPER);

        int totalLeadCost = bridgesCount * COST_BRIDGE_LEAD;

        String summary = targetOre.name + " vein at (" + outlet.x + "," + outlet.y + ") ("
                + drillsCount + " drills, " + bridgesCount + " bridges, "
                + totalConveyors + " conveyors, " + totalJunctions + " junctions, +" + String.format("%.2f", veinRate) + "/s)";

        VeinDevelopmentProject project = new VeinDevelopmentProject(targetOre, totalCopperCost, totalLeadCost, veinRate, summary);
        project.line1 = line1;
        project.route1 = route1;
        project.rate1 = rate1;
        project.line2 = line2;
        project.route2 = route2;
        project.rate2 = rate2;

        for (Point2 d : bestPlan.drills) {
            project.allPlans.add(new BuildPlan(d.x, d.y, 0, Blocks.mechanicalDrill));
        }
        for (Point2 b : bestPlan.bridges) {
            Point2 target = bestPlan.bridgeTarget.get(pack(b.x, b.y));
            Point2 config = (target != null) ? new Point2(target.x - b.x, target.y - b.y) : new Point2(0, 0);
            project.allPlans.add(new BuildPlan(b.x, b.y, 0, Blocks.itemBridge, config));
        }
        if (route1 != null) {
            project.allPlans.addAll(route1.plans);
        }
        if (route2 != null) {
            project.allPlans.addAll(route2.plans);
        }

        return project;
    }

    public static Tile findNearestOreTile(Item targetOre, CoreBuild core) {
        Tile best = null;
        float bestDst = Float.MAX_VALUE;
        int radius = 120;
        int startX = Math.max(1, core.tileX() - radius);
        int endX = Math.min(Vars.world.width() - 2, core.tileX() + radius);
        int startY = Math.max(1, core.tileY() - radius);
        int endY = Math.min(Vars.world.height() - 2, core.tileY() + radius);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                Tile t = Vars.world.tile(x, y);
                // Проверяем !t.solid(), чтобы бот не пытался копать руду под стеной
                if (t != null && !t.solid() && t.drop() == targetOre && t.build == null) {
                    float d = Mathf.dst(x, y, core.tileX(), core.tileY());
                    if (d < bestDst) {
                        bestDst = d;
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    private static class VeinPlan {
        Seq<Point2> drills = new Seq<>();
        Seq<Point2> bridges = new Seq<>();
        IntMap<Point2> bridgeTarget = new IntMap<>();
        Point2 outlet;
        int oreTilesCovered = 0;
    }

    private static VeinPlan buildPinwheelPlan(Seq<Tile> area, Item targetOre, int ox, int oy, Point2 dirToCore, CoreBuild core, float maxRateCap) {
        Team team = Vars.player.team();
        Seq<Point2> candidateDrills = new Seq<>();
        IntSet bridgeSet = new IntSet();
        Seq<Point2> candidateBridges = new Seq<>();

        for (Tile t : area) {
            int sx = t.x + ox;
            int sy = t.y + oy;

            if (isPinwheelDrillTile(sx, sy)) {
                // Уменьшили буфер ядра с 3 до 1, чтобы не пропускать близкие к ядру жилы
                if (isDrillInCoreBuffer(t.x, t.y, core, 1)) continue;

                if (Build.validPlace(Blocks.mechanicalDrill, team, t.x, t.y, 0)) {
                    int ores = countOreTiles(t, (Drill) Blocks.mechanicalDrill, targetOre);
                    if (ores >= 1) {
                        candidateDrills.add(new Point2(t.x, t.y));
                    }
                }
            } else if (isPinwheelBridgeTile(sx, sy)) {
                if (ConveyorPathfinder.isInCoreBuffer(t.x, t.y, core, 1)) continue;

                if (Build.validPlace(Blocks.itemBridge, team, t.x, t.y, 0)) {
                    if (bridgeSet.add(pack(t.x, t.y))) {
                        candidateBridges.add(new Point2(t.x, t.y));
                    }
                }
            }
        }

        if (candidateDrills.isEmpty() || candidateBridges.isEmpty()) return null;

        // 1. Сортируем буры по близости к ядру и плотности руды
        candidateDrills.sort((a, b) -> {
            int oa = countOreTiles(Vars.world.tile(a.x, a.y), (Drill) Blocks.mechanicalDrill, targetOre);
            int ob = countOreTiles(Vars.world.tile(b.x, b.y), (Drill) Blocks.mechanicalDrill, targetOre);
            if (oa != ob) return Integer.compare(ob, oa);
            return Float.compare(Mathf.dst(a.x, a.y, core.tileX(), core.tileY()), Mathf.dst(b.x, b.y, core.tileX(), core.tileY()));
        });

        // 2. Отбираем буры, у которых есть соседний мост
        Seq<Point2> selectedDrills = new Seq<>();
        IntSet activeBridgeNodes = new IntSet();
        int totalOreCovered = 0;

        for (Point2 d : candidateDrills) {
            if (totalOreCovered * BASE_TILE_RATE >= maxRateCap) {
                break;
            }

            Point2 adjBridge = null;
            for (int dx = -1; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    boolean isCorner = (dx == -1 || dx == 2) && (dy == -1 || dy == 2);
                    boolean isInside = (dx >= 0 && dx < 2) && (dy >= 0 && dy < 2);
                    if (isCorner || isInside) continue;

                    int bx = d.x + dx;
                    int by = d.y + dy;
                    if (bridgeSet.contains(pack(bx, by))) {
                        if (adjBridge == null || activeBridgeNodes.contains(pack(bx, by))) {
                            adjBridge = new Point2(bx, by);
                        }
                    }
                }
            }

            if (adjBridge != null) {
                selectedDrills.add(d);
                activeBridgeNodes.add(pack(adjBridge.x, adjBridge.y));
                Tile drillTile = Vars.world.tile(d.x, d.y);
                totalOreCovered += countOreTiles(drillTile, (Drill) Blocks.mechanicalDrill, targetOre);
            }
        }

        if (selectedDrills.isEmpty()) return null;

        // 3. Выбираем лучший активный мост (ближайший к ядру, у которого есть свободный выход для конвейера)
        Point2 outerMost = null;
        float bestOuterDist = Float.MAX_VALUE;
        for (Point2 b : candidateBridges) {
            if (!activeBridgeNodes.contains(pack(b.x, b.y))) continue;
            float dist = Mathf.dst(b.x, b.y, core.tileX(), core.tileY());
            if (!hasFreeNeighborForConveyor(b.x, b.y, selectedDrills)) {
                dist += 50f; // Штраф, если мост зажат в стенах/бурах
            }
            if (dist < bestOuterDist) {
                bestOuterDist = dist;
                outerMost = b;
            }
        }
        if (outerMost == null) return null;

        // Пытаемся вынести outlet на 3 тайла в сторону ядра (или в любую свободную сторону, если там стена)
        Point2 outlet = findBestOutletExtension(outerMost, dirToCore, core, team, selectedDrills);
        if (!outlet.equals(outerMost)) {
            bridgeSet.add(pack(outlet.x, outlet.y));
        }

        // 4. Строим минимальное BFS-дерево от outlet для связывания selectedDrills
        IntMap<Point2> parentMap = new IntMap<>();
        Queue<Point2> queue = new Queue<>();
        IntSet reachableBridges = new IntSet();

        reachableBridges.add(pack(outlet.x, outlet.y));
        queue.addLast(outlet);

        int[] stepX = {3, -3, 0, 0};
        int[] stepY = {0, 0, 3, -3};

        while (!queue.isEmpty()) {
            Point2 curr = queue.removeFirst();
            for (int i = 0; i < 4; i++) {
                int nx = curr.x + stepX[i];
                int ny = curr.y + stepY[i];
                int nKey = pack(nx, ny);

                if (bridgeSet.contains(nKey) && !reachableBridges.contains(nKey)) {
                    reachableBridges.add(nKey);
                    parentMap.put(nKey, curr);
                    queue.addLast(new Point2(nx, ny));
                }
            }
        }

        // 5. Собираем финальный набор буров и мостов
        Seq<Point2> finalDrills = new Seq<>();
        IntSet neededBridges = new IntSet();
        neededBridges.add(pack(outlet.x, outlet.y));
        int finalOreCovered = 0;

        for (Point2 d : selectedDrills) {
            Point2 bestBridgeForDrill = null;
            for (int dx = -1; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    boolean isCorner = (dx == -1 || dx == 2) && (dy == -1 || dy == 2);
                    boolean isInside = (dx >= 0 && dx < 2) && (dy >= 0 && dy < 2);
                    if (isCorner || isInside) continue;

                    int bx = d.x + dx;
                    int by = d.y + dy;
                    int bKey = pack(bx, by);
                    if (reachableBridges.contains(bKey)) {
                        if (bestBridgeForDrill == null || neededBridges.contains(bKey)) {
                            bestBridgeForDrill = new Point2(bx, by);
                        }
                    }
                }
            }

            if (bestBridgeForDrill != null) {
                finalDrills.add(d);
                Tile drillTile = Vars.world.tile(d.x, d.y);
                finalOreCovered += countOreTiles(drillTile, (Drill) Blocks.mechanicalDrill, targetOre);

                Point2 trace = bestBridgeForDrill;
                while (trace != null) {
                    int tKey = pack(trace.x, trace.y);
                    neededBridges.add(tKey);
                    trace = parentMap.get(tKey);
                }
            }
        }

        if (finalDrills.isEmpty()) return null;

        VeinPlan plan = new VeinPlan();
        plan.drills = finalDrills;
        plan.outlet = outlet;
        plan.bridgeTarget = parentMap;
        plan.oreTilesCovered = finalOreCovered;

        neededBridges.each(key -> {
            int x = key >> 16;
            int y = (short) (key & 0xFFFF);
            plan.bridges.add(new Point2(x, y));
        });

        return plan;
    }

    /**
     * Ищет лучшую точку для выносного моста (outlet), проверяя сначала направление к ядру,
     * а если там стена или буры — другие свободные направления.
     */
    private static Point2 findBestOutletExtension(Point2 outerMost, Point2 dirToCore, CoreBuild core, Team team, Seq<Point2> selectedDrills) {
        int[][] dirs = {
                {dirToCore.x, dirToCore.y},
                {dirToCore.y, dirToCore.x},
                {-dirToCore.y, -dirToCore.x},
                {-dirToCore.x, -dirToCore.y}
        };

        for (int[] d : dirs) {
            int ox = outerMost.x + d[0] * 3;
            int oy = outerMost.y + d[1] * 3;
            if (!ConveyorPathfinder.isInCoreBuffer(ox, oy, core, 1)
                    && Build.validPlace(Blocks.itemBridge, team, ox, oy, 0)
                    && !isInsideAnyDrill(new Point2(ox, oy), selectedDrills)
                    && hasFreeNeighborForConveyor(ox, oy, selectedDrills)) {
                return new Point2(ox, oy);
            }
        }
        return outerMost;
    }

    private static boolean hasFreeNeighborForConveyor(int x, int y, Seq<Point2> drills) {
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        Team team = Vars.player.team();
        for (int i = 0; i < 4; i++) {
            int nx = x + dx[i];
            int ny = y + dy[i];
            if (!isInsideAnyDrill(new Point2(nx, ny), drills)
                    && Build.validPlace(Blocks.conveyor, team, nx, ny, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDrillInCoreBuffer(int x, int y, CoreBuild core, int buffer) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                if (ConveyorPathfinder.isInCoreBuffer(x + dx, y + dy, core, buffer)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPinwheelDrillTile(int x, int y) {
        int mx = Math.floorMod(x, 6);
        int my = Math.floorMod(y, 6);

        switch (mx) {
            case 0:
            case 2:
                return Math.floorMod(my - 1, 6) == 0;
            case 1:
                return Math.floorMod(my - 3, 6) == 0 || Math.floorMod(my - 3, 6) == 2;
            case 3:
            case 5:
                return Math.floorMod(my - 4, 6) == 0;
            case 4:
                return my == 0 || my == 2;
        }
        return false;
    }

    private static boolean isPinwheelBridgeTile(int x, int y) {
        return Math.floorMod(x, 3) == 0 && Math.floorMod(y, 3) == 0;
    }

    private static boolean isInsideAnyDrill(Point2 p, Seq<Point2> drills) {
        for (Point2 d : drills) {
            if (p.x >= d.x && p.x < d.x + 2 && p.y >= d.y && p.y < d.y + 2) {
                return true;
            }
        }
        return false;
    }

    private static Point2 getPrimaryDirectionToCore(Tile from, CoreBuild core) {
        int dx = core.tileX() - from.x;
        int dy = core.tileY() - from.y;
        if (Math.abs(dx) >= Math.abs(dy)) {
            return new Point2(dx >= 0 ? 1 : -1, 0);
        } else {
            return new Point2(0, dy >= 0 ? 1 : -1);
        }
    }

    private static int countOreTiles(Tile tile, Drill drill, Item targetOre) {
        if (tile == null) return 0;
        int count = 0;
        for (Tile other : tile.getLinkedTilesAs(drill, new Seq<>())) {
            if (drill.canMine(other) && drill.getDrop(other) == targetOre) {
                count++;
            }
        }
        return count;
    }

    /**
     * Находит ВСЕ незанятые жилы на карте (или в большом радиусе), игнорируя тайлы под стенами,
     * и сортирует их строго по минимальному расстоянию от края жилы до ядра.
     */
    private static Seq<VeinCandidate> findCandidateVeins(Item targetOre, CoreBuild core) {
        IntSet visited = new IntSet();
        Seq<VeinCandidate> candidates = new Seq<>();

        int searchRadius = 200;
        int startX = Math.max(1, core.tileX() - searchRadius);
        int endX = Math.min(Vars.world.width() - 2, core.tileX() + searchRadius);
        int startY = Math.max(1, core.tileY() - searchRadius);
        int endY = Math.min(Vars.world.height() - 2, core.tileY() + searchRadius);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                Tile t = Vars.world.tile(x, y);
                // Игнорируем тайлы под стенами (t.solid())
                if (t == null || t.solid() || t.drop() != targetOre || visited.contains(t.pos())) continue;

                Seq<Tile> vein = new Seq<>();
                Queue<Tile> queue = new Queue<>();
                queue.addLast(t);
                visited.add(t.pos());
                boolean alreadyHasDrill = false;
                float minDstToCore = Float.MAX_VALUE;

                while (!queue.isEmpty()) {
                    Tile curr = queue.removeFirst();
                    vein.add(curr);
                    if (curr.build instanceof DrillBuild) {
                        alreadyHasDrill = true;
                    }

                    float d = Mathf.dst(curr.x, curr.y, core.tileX(), core.tileY());
                    if (d < minDstToCore) {
                        minDstToCore = d;
                    }

                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            Tile next = Vars.world.tile(curr.x + dx, curr.y + dy);
                            if (next != null && !next.solid() && next.drop() == targetOre && !visited.contains(next.pos())) {
                                visited.add(next.pos());
                                queue.addLast(next);
                            }
                        }
                    }
                }

                // Позволяем разрабатывать даже небольшие жилы (от 2 тайлов), если они близко
                if (alreadyHasDrill || vein.size < 2) continue;

                candidates.add(new VeinCandidate(vein, minDstToCore));
            }
        }

        // Сортируем строго по реальной дистанции до ближайшего края жилы
        candidates.sort((a, b) -> Float.compare(a.minDstToCore, b.minDstToCore));
        return candidates;
    }

    private static int pack(int x, int y) {
        return (x << 16) | (y & 0xFFFF);
    }
}
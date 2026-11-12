package autobot;

import arc.math.Mathf;
import arc.math.geom.Point2;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class MiningManager {

    /**
     * Считает количество уже построенных буров команды на указанной руде.
     */
    public static int countDrillsOn(Item targetOre) {
        int count = 0;
        for (Tile tile : Vars.world.tiles) {
            if (tile.build instanceof DrillBuild && tile.team() == Vars.player.team()) {
                // Проверяем главный тайл постройки, чтобы не считать 2x2 блок 4 раза
                if (tile.build.tile == tile && tile.drop() == targetOre) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Находит ближайшую свободную жилу 2x2, ставит план бура и ведет конвейер к ядру.
     */
    public static boolean buildNextDrill(Item targetOre, CoreBuild core, Unit unit) {
        Point2 bestSpot = findBestOreSpot(targetOre, core);
        if (bestSpot == null) {
            return false;
        }

        // Строим маршрут конвейеров от периметра бура до периметра ядра
        boolean pathBuilt = ConveyorPathfinder.buildConveyorLine(bestSpot.x, bestSpot.y, 2, core, unit);
        if (!pathBuilt) {
            return false;
        }

        // Добавляем план постройки самого механического бура (размер 2x2)
        unit.addBuild(new BuildPlan(bestSpot.x, bestSpot.y, 0, Blocks.mechanicalDrill));
        return true;
    }

    private static Point2 findBestOreSpot(Item targetOre, CoreBuild core) {
        Point2 best = null;
        float bestDist = Float.MAX_VALUE;
        int searchRadius = 60; // Радиус поиска в тайлах вокруг ядра

        int startX = Math.max(2, core.tileX() - searchRadius);
        int endX = Math.min(Vars.world.width() - 3, core.tileX() + searchRadius);
        int startY = Math.max(2, core.tileY() - searchRadius);
        int endY = Math.min(Vars.world.height() - 3, core.tileY() + searchRadius);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                // Проверяем, можно ли поставить блок 2x2 в координаты (x, y)
                if (!Build.validPlace(Blocks.mechanicalDrill, Vars.player.team(), x, y, 0)) {
                    continue;
                }

                // Считаем, сколько тайлов руды покроет бур 2x2
                int oreTiles = countOreIn2x2(x, y, targetOre);
                if (oreTiles < 2) continue; // Минимум 2 тайла руды под буром

                float dst = Mathf.dst(x, y, core.tileX(), core.tileY()) - (oreTiles * 3f);
                if (dst < bestDist) {
                    bestDist = dst;
                    best = new Point2(x, y);
                }
            }
        }
        return best;
    }

    private static int countOreIn2x2(int x, int y, Item targetOre) {
        int count = 0;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                Tile t = Vars.world.tile(x + dx, y + dy);
                if (t != null && t.drop() == targetOre) {
                    count++;
                }
            }
        }
        return count;
    }
}
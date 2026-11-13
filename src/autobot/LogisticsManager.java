package autobot;

import arc.math.geom.Point2;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;

public class LogisticsManager {

    public static class ConveyorLine {
        public final Item item;
        public final Seq<Point2> tiles = new Seq<>();
        public float currentRate = 0f; // Текущая загрузка линии в ед/сек (макс 5.0)

        public ConveyorLine(Item item) {
            this.item = item;
        }

        public float remainingCapacity() {
            return Math.max(0f, 5.0f - currentRate);
        }
    }

    // Храним до 2 магистралей на каждый базовый ресурс (2 меди + 2 свинца = 4 входа в ядро)
    private static final ObjectMap<Item, Seq<ConveyorLine>> linesByItem = new ObjectMap<>();

    public static void reset() {
        linesByItem.clear();
    }

    public static Seq<ConveyorLine> getLines(Item item) {
        return linesByItem.get(item, Seq::new);
    }

    /**
     * Проверяет, есть ли на карте существующие конвейеры этого ресурса, если бот перезапускался.
     */
    public static void syncWithWorld() {
        // Удаляем разрушенные тайлы из трекинга
        for (Seq<ConveyorLine> list : linesByItem.values()) {
            for (ConveyorLine line : list) {
                line.tiles.retainAll(p -> {
                    Tile t = Vars.world.tile(p.x, p.y);
                    return t != null && t.block() instanceof Conveyor && t.team() == Vars.player.team();
                });
            }
            list.retainAll(l -> !l.tiles.isEmpty());
        }
    }

    /**
     * Подбирает лучшую линию для подключения потока:
     * либо существующую недогруженную линию (чтобы добить её до 5.0/s), либо создаёт новую (если линий < 2).
     */
    public static ConveyorLine getOrCreateTargetLine(Item item, float incomingRate) {
        syncWithWorld();
        Seq<ConveyorLine> lines = getLines(item);

        // 1. Ищем существующую линию, куда влезет хотя бы часть потока (осталось место > 0.3/s)
        for (ConveyorLine line : lines) {
            if (line.remainingCapacity() > 0.3f && !line.tiles.isEmpty()) {
                return line;
            }
        }

        // 2. Если линий меньше 2 — разрешаем создать новую магистраль в ядро
        if (lines.size < 2) {
            ConveyorLine newLine = new ConveyorLine(item);
            lines.add(newLine);
            return newLine;
        }

        // 3. Если обе магистрали уже есть (даже если почти полные), возвращаем наименее загруженную
        ConveyorLine leastLoaded = lines.first();
        for (ConveyorLine line : lines) {
            if (line.currentRate < leastLoaded.currentRate) {
                leastLoaded = line;
            }
        }
        return leastLoaded;
    }

    /**
     * Проверяет, занят ли тайл любой постройкой нашей команды (конвейер, бур, мост и т.д.).
     */
    public static boolean isTileOccupiedByOurBuilding(int x, int y) {
        Tile t = Vars.world.tile(x, y);
        if (t == null) return true;
        return t.build != null && t.team() == Vars.player.team();
    }
}
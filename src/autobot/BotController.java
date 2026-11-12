package autobot;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.gen.Unit;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class BotController {
    public static boolean enabled = false;
    private static float actionTimer = 0f;

    public static void setEnabled(boolean state) {
        enabled = state;
        if (enabled) {
            BotUI.notify("[green]AutoBot enabled: Starting mining operations[]");
        } else {
            // Очищаем очередь строительства при выключении
            if (Vars.player != null && Vars.player.unit() != null) {
                Vars.player.unit().clearBuilding();
            }
            BotUI.notify("[scarlet]AutoBot disabled[]");
        }
    }

    public static void update() {
        Unit unit = Vars.player.unit();
        CoreBuild core = Vars.player.core();
        if (unit == null || core == null) return;

        // Проверяем логику раз в 1.5 секунды (90 тиков), чтобы не нагружать игру
        actionTimer += Time.delta;
        if (actionTimer < 90f) return;
        actionTimer = 0f;

        // Если юнит игрока сейчас занят строительством — ждем завершения
        if (!unit.plans.isEmpty()) {
            return;
        }

        // Проверяем, хватает ли меди на базовый бур (12 меди) + запас на конвейеры
        int copperCount = core.items.get(Items.copper);
        if (copperCount < 20) {
            return;
        }

        // Сначала строим 3 бура на медь, затем 3 бура на свинец
        int copperDrills = MiningManager.countDrillsOn(Items.copper);
        if (copperDrills < 3) {
            if (MiningManager.buildNextDrill(Items.copper, core, unit)) {
                BotUI.notify("[accent]Building Copper Drill (" + (copperDrills + 1) + "/3)[]");
            }
            return;
        }

        int leadDrills = MiningManager.countDrillsOn(Items.lead);
        if (leadDrills < 3) {
            if (MiningManager.buildNextDrill(Items.lead, core, unit)) {
                BotUI.notify("[accent]Building Lead Drill (" + (leadDrills + 1) + "/3)[]");
            }
        }
    }
}
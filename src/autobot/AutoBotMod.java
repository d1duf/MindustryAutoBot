package autobot;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.mod.Mod;

public class AutoBotMod extends Mod {

    public AutoBotMod() {
        Events.on(ClientLoadEvent.class, e -> {
            BotUI.build();
        });

        // Основной игровой цикл обновления (срабатывает каждый кадр)
        Events.run(Trigger.update, () -> {
            if (Vars.state.isGame() && BotController.enabled) {
                BotController.update();
            }
        });
    }
}
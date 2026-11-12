package autobot;

import arc.Events;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.Styles;

public class AutoBotMod extends Mod {
    // Состояние бота (включен / выключен)
    public static boolean botEnabled = false;
    private TextButton toggleButton;

    public AutoBotMod() {
        // Ждем полной загрузки клиента и интерфейса игры
        Events.on(ClientLoadEvent.class, e -> {
            buildUI();
        });
    }

    private void buildUI() {
        // Создаем таблицу в основном HUD-слое игры
        Vars.ui.hudGroup.fill(cont -> {
            cont.name = "autobot-hud";
            // Размещаем в левом верхнем углу со сдвигом вниз, чтобы не перекрывать меню волн/ресурсов
            cont.top().left().marginTop(140f).marginLeft(10f);

            // Создаем кнопку с иконкой и текстом
            toggleButton = cont.button("Бот: ВЫКЛ", Icon.play, Styles.togglet, () -> {
                botEnabled = !botEnabled;
                updateButtonState();

                if (botEnabled) {
                    // Выводим стандартную плашку-уведомление вверху экрана
                    Vars.ui.showInfoToast("[green]Бот активирован! Все системы в норме.[]", 3f);
                } else {
                    Vars.ui.showInfoToast("[scarlet]Бот остановлен.[]", 2f);
                }
            }).size(180f, 45f).get();

            // Кнопка видна только когда игрок находится на карте (в игре), а не в главном меню
            cont.visible(() -> Vars.state.isGame());
        });
    }

    private void updateButtonState() {
        if (toggleButton != null) {
            toggleButton.setText(botEnabled ? "Бот: ВКЛ" : "Бот: ВЫКЛ");
            toggleButton.setChecked(botEnabled);
        }
    }
}
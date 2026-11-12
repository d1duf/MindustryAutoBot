package autobot;

import arc.scene.ui.TextButton;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;

public class BotUI {
    private static TextButton toggleButton;

    public static void build() {
        Vars.ui.hudGroup.fill(cont -> {
            cont.name = "autobot-hud";
            cont.top().left().marginTop(140f).marginLeft(10f);

            toggleButton = cont.button("Bot: OFF", Icon.play, Styles.togglet, () -> {
                BotController.setEnabled(!BotController.enabled);
                updateState();
            }).size(180f, 45f).get();

            cont.visible(() -> Vars.state.isGame());
        });
    }

    public static void updateState() {
        if (toggleButton != null) {
            boolean active = BotController.enabled;
            toggleButton.setText(active ? "Bot: ON" : "Bot: OFF");
            toggleButton.setChecked(active);
        }
    }

    public static void notify(String message) {
        Vars.ui.showInfoToast(message, 3f);
    }
}
package autobot;

import arc.util.Log;

public class BotLogger {
    private static String lastState = "";

    /**
     * Пишет в консоль Mindustry (~) только при смене мысли/состояния, чтобы не спамить.
     */
    public static void state(String message) {
        if (!message.equals(lastState)) {
            lastState = message;
            Log.info("[AutoBot] " + message);
        }
    }

    public static void info(String message) {
        Log.info("[AutoBot] " + message);
    }
}
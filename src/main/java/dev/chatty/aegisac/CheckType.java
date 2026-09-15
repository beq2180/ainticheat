package dev.chatty.aegisac;

public enum CheckType {
    SPEED("speed", "Speed"),
    FLY("fly", "Fly"),
    NOCLIP("noclip", "NoClip"),
    TIMER("timer", "Timer"),
    REACH("reach", "Reach"),
    KILLAURA("killaura", "KillAura"),
    AUTOCLICKER("autoclicker", "AutoClicker"),
    FASTBREAK("fastbreak", "FastBreak"),
    NUKER("nuker", "Nuker"),
    SCAFFOLD("scaffold", "Scaffold"),
    ANTIKB("antikb", "AntiKB"),
    XRAY("xray", "X-Ray");

    private final String key;
    private final String display;

    CheckType(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }
}

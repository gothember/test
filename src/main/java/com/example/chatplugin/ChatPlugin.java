package com.example.chatplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class ChatPlugin extends JavaPlugin {

    private int localChatRadius;
    private String globalChatPrefix;
    private String localChatFormat;
    private String globalChatFormat;

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        // Load configuration
        loadConfig();

        getLogger().info("ChatPlugin has been enabled!");
        getLogger().info(String.format("Local chat radius set to: %d", localChatRadius));
        getLogger().info(String.format("Global chat prefix set to: '%s'", globalChatPrefix));

        // Listener registration will be done in a subsequent step
    }

    @Override
    public void onDisable() {
        getLogger().info("ChatPlugin has been disabled.");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        localChatRadius = config.getInt("local-chat.radius", 100);
        globalChatPrefix = config.getString("global-chat.prefix", "!");
        localChatFormat = config.getString("format.local", "&7[Local] <%player%&r&7> %message%");
        globalChatFormat = config.getString("format.global", "&e[Global] <%player%&r&e> %message%");
    }

    // Getters for config values to be used by the listener
    public int getLocalChatRadius() {
        return localChatRadius;
    }

    public String getGlobalChatPrefix() {
        return globalChatPrefix;
    }
    
    public String getLocalChatFormat() {
        return localChatFormat;
    }

    public String getGlobalChatFormat() {
        return globalChatFormat;
    }
}

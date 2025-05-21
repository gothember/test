package com.example.chatplugin;

import org.bukkit.ChatColor; // Added for onCommand messages
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
// import org.bukkit.entity.Player; // No longer directly used in this class
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main class for the ChatPlugin.
 * Handles plugin initialization, configuration loading, command registration (reload),
 * PlaceholderAPI integration check, and management of chat features like anti-spam and blocked words.
 */
public class ChatPlugin extends JavaPlugin {

    // Configuration fields for chat settings
    private int localChatRadius;
    private String globalChatPrefix;
    private String localChatFormat;
    private String globalChatFormat;

    // Configuration fields for anti-spam settings
    private boolean antiSpamEnabled;
    private int antiSpamMessageLimit;
    private int antiSpamTimePeriodSeconds;
    private int antiSpamCooldownSeconds;
    private String antiSpamWarningMessage;
    private String antiSpamCooldownOverMessage;

    // Configuration fields for blocked words filter
    private boolean blockedWordsEnabled;
    private List<String> blockedWordsList; // Stored in lowercase for case-insensitive matching
    private String blockedWordsPlayerWarning;
    private String blockedWordsAdminNotification;

    private boolean placeholderApiAvailable = false;

    /**
     * Called when the plugin is enabled.
     * Sets up configuration, registers listeners, and logs plugin status.
     */
    @Override
    public void onEnable() {
        setupConfiguration();
        registerListeners();
        getLogger().info("ChatPlugin has been enabled!");
    }

    /**
     * Initializes configuration by saving the default config, loading values,
     * and logging current settings.
     */
    private void setupConfiguration() {
        saveDefaultConfig(); // Creates config.yml from resources if it doesn't exist
        loadConfigValues();  // Loads values from config.yml into plugin fields
        logStatusMessages(); // Logs the status of loaded configurations
    }
    
    /**
     * Registers event listeners for the plugin.
     * Currently registers only the ChatListener.
     */
    private void registerListeners(){
        // Note: Bukkit's default /reload command re-registers listeners for many plugins.
        // For our custom /cpreload, ChatListener is designed to fetch live config values via getters,
        // so re-instantiating or re-registering ChatListener is not strictly necessary for it to pick up changes.
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        getLogger().info("ChatListener registered.");
    }

    /**
     * Logs the current status of major configurable features to the console.
     * This includes chat settings, PlaceholderAPI status, anti-spam, and blocked words filter.
     */
    private void logStatusMessages() {
        getLogger().info(String.format("Local chat radius set to: %d blocks", localChatRadius));
        getLogger().info(String.format("Global chat prefix set to: '%s'", globalChatPrefix));
        if (placeholderApiAvailable) {
            getLogger().info("PlaceholderAPI found! Placeholders in chat formats will be enabled.");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholders in chat formats will not be processed.");
        }
        if (antiSpamEnabled) {
            getLogger().info(String.format("Anti-spam enabled: %d messages / %d seconds. Cooldown: %d seconds.",
                             antiSpamMessageLimit, antiSpamTimePeriodSeconds, antiSpamCooldownSeconds));
        } else {
            getLogger().info("Anti-spam is disabled."); // Added else condition for clarity
        }
        if (blockedWordsEnabled) {
            getLogger().info(String.format("Blocked words filter enabled. %d words loaded.", blockedWordsList.size()));
        } else {
            getLogger().info("Blocked words filter is disabled.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("ChatPlugin has been disabled.");
    }

    /**
     * Loads or reloads configuration settings from the config.yml file into the plugin's fields.
     * This method is called on plugin startup and by the reload command.
     * It ensures that all configurable aspects of the plugin are updated with the latest values.
     */
    public void loadConfigValues() {
        FileConfiguration config = getConfig();

        // Chat settings
        localChatRadius = config.getInt("local-chat.radius", 100);
        globalChatPrefix = config.getString("global-chat.prefix", "!");
        localChatFormat = config.getString("format.local", "&7[Local] [%vault_prefix%&r&7%player%&r&7%vault_suffix%&r&7] %message%");
        globalChatFormat = config.getString("format.global", "&e[Global] [%vault_prefix%&r&e%player%&r&e%vault_suffix%&r&e] %message%");

        // Anti-spam settings
        antiSpamEnabled = config.getBoolean("anti-spam.enabled", true);
        antiSpamMessageLimit = config.getInt("anti-spam.message-limit", 3);
        antiSpamTimePeriodSeconds = config.getInt("anti-spam.time-period-seconds", 5);
        antiSpamCooldownSeconds = config.getInt("anti-spam.cooldown-seconds", 10);
        antiSpamWarningMessage = config.getString("anti-spam.spam-warning-message", "&cPlease don't spam! Wait %cooldown% seconds.");
        antiSpamCooldownOverMessage = config.getString("anti-spam.cooldown-over-message", "&aYou can chat again.");

        // Blocked words filter settings
        blockedWordsEnabled = config.getBoolean("blocked-words.enabled", true);
        // Load the list of blocked words and convert them to lowercase for efficient, case-insensitive matching.
        blockedWordsList = config.getStringList("blocked-words.list").stream()
                                .map(String::toLowerCase) 
                                .collect(Collectors.toList());
        blockedWordsPlayerWarning = config.getString("blocked-words.player-warning-message", "&cYou used a blocked word! Please be respectful.");
        blockedWordsAdminNotification = config.getString("blocked-words.admin-notification-message", "&c[Alert] Player %player% tried to use a blocked word: %word%");
        
        // PlaceholderAPI check
        placeholderApiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * Handles plugin commands.
     * Currently supports `/cpreload` for reloading the plugin's configuration from disk.
     * @param sender The entity who sent the command (e.g., Player or ConsoleCommandSender).
     * @param command The command that was executed.
     * @param label The alias of the command used.
     * @param args The arguments passed with the command.
     * @return true if the command was handled by this plugin, false otherwise.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cpreload")) {
            // Check if the sender has the required permission to reload the configuration.
            if (!sender.hasPermission("chatplugin.reload")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true; // Command handled (permission denied)
            }
            
            // Perform the reload operations:
            // 1. reloadConfig(): Bukkit's method to reload the config.yml from disk.
            this.reloadConfig();      
            // 2. loadConfigValues(): Custom method to load the new values from the config into plugin fields.
            this.loadConfigValues();  
            
            // ChatListener uses getters, so it will automatically use new config values.
            // No need to re-register or re-initialize ChatListener for config changes.

            sender.sendMessage(ChatColor.GREEN + "ChatPlugin configuration has been reloaded.");
            getLogger().info("Configuration reloaded by " + sender.getName() + ".");
            logStatusMessages(); // Log the new status of configurations to console for verification.
            return true; // Command handled successfully
        }
        return false; // Command not recognized or handled by this plugin
    }

    // Getter methods for chat settings
    public int getLocalChatRadius() { return localChatRadius; }
    public String getGlobalChatPrefix() { return globalChatPrefix; }
    public String getLocalChatFormat() { return localChatFormat; }
    public String getGlobalChatFormat() { return globalChatFormat; }

    // Getter methods for anti-spam settings
    public boolean isAntiSpamEnabled() { return antiSpamEnabled; }
    public int getAntiSpamMessageLimit() { return antiSpamMessageLimit; }
    public int getAntiSpamTimePeriodSeconds() { return antiSpamTimePeriodSeconds; }
    public int getAntiSpamCooldownSeconds() { return antiSpamCooldownSeconds; }
    public String getAntiSpamWarningMessage() { return antiSpamWarningMessage; }
    public String getAntiSpamCooldownOverMessage() { return antiSpamCooldownOverMessage; }

    // Getter methods for blocked words filter settings
    /** @return True if the blocked words filter is enabled, false otherwise. */
    public boolean isBlockedWordsEnabled() { return blockedWordsEnabled; }
    /** @return The list of configured blocked words (all in lowercase). */
    public List<String> getBlockedWordsList() { return blockedWordsList; }
    /** @return The warning message template for players who use a blocked word. */
    public String getBlockedWordsPlayerWarning() { return blockedWordsPlayerWarning; }
    /** @return The notification message template for staff when a blocked word is used. */
    public String getBlockedWordsAdminNotification() { return blockedWordsAdminNotification; }

    // Getter for PlaceholderAPI status
    public boolean isPlaceholderApiAvailable() { return placeholderApiAvailable; }
}

package com.example.chatplugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Main class for the ChatPlugin.
 * Handles plugin initialization, configuration loading, command registration (reload),
 * PlaceholderAPI integration check, and management of chat features like anti-spam, 
 * blocked words, and IP/link filtering.
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
    private List<String> blockedWordsList; 
    private String blockedWordsPlayerWarning;
    private String blockedWordsAdminNotification;

    // Configuration fields for Link/IP filter
    private boolean linkIpFilterEnabled;
    private Pattern ipPattern;         // Compiled regex for IP detection
    private Pattern linkPattern;       // Compiled regex for link detection (case-insensitive)
    private List<String> allowedDomains; // Lowercase list of allowed domains for link filter
    private String linkIpFilterPlayerWarning;
    private String linkIpFilterAdminNotification;

    private boolean placeholderApiAvailable = false;

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
        saveDefaultConfig(); 
        loadConfigValues();  
        logStatusMessages(); 
    }
    
    /**
     * Registers event listeners for the plugin.
     */
    private void registerListeners(){
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        getLogger().info("ChatListener registered.");
    }

    /**
     * Logs the current status of major configurable features to the console,
     * including chat settings, PlaceholderAPI, anti-spam, blocked words, and link/IP filter.
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
             getLogger().info("Anti-spam is disabled.");
        }
        if (blockedWordsEnabled) {
            getLogger().info(String.format("Blocked words filter enabled. %d words loaded.", 
                             (blockedWordsList != null ? blockedWordsList.size() : 0)));
        } else {
            getLogger().info("Blocked words filter disabled.");
        }
        if (linkIpFilterEnabled) {
            getLogger().info("Link/IP filter enabled.");
            if (allowedDomains != null && !allowedDomains.isEmpty()) {
                getLogger().info(String.format("Allowed domains for link filter: %s", String.join(", ", allowedDomains)));
            } else {
                getLogger().info("No domains explicitly allowed for link filter (all links will be blocked unless regex is very specific or filter disabled).");
            }
        } else {
            getLogger().info("Link/IP filter disabled.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("ChatPlugin has been disabled.");
    }

    /**
     * Loads or reloads configuration settings from the config.yml file into the plugin's fields.
     * This includes chat settings, anti-spam, blocked words, and the link/IP filter.
     * Regex patterns are compiled here, and lists are processed (e.g., to lowercase).
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
        blockedWordsList = config.getStringList("blocked-words.list").stream()
                                .map(String::toLowerCase)
                                .collect(Collectors.toList());
        blockedWordsPlayerWarning = config.getString("blocked-words.player-warning-message", "&cYou used a blocked word! Please be respectful.");
        blockedWordsAdminNotification = config.getString("blocked-words.admin-notification-message", "&c[Alert] Player %player% tried to use a blocked word: %word%");

        // Load Link/IP filter settings
        linkIpFilterEnabled = config.getBoolean("link-ip-filter.enabled", true);
        try {
            ipPattern = Pattern.compile(config.getString("link-ip-filter.ip-regex", "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"));
            linkPattern = Pattern.compile(config.getString("link-ip-filter.link-regex", "([a-zA-Z0-9]+(-[a-zA-Z0-9]+)*\\.)+[a-zA-Z]{2,}(:[0-9]{1,5})?(/[^ \\s]*)?"), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            getLogger().severe("Failed to compile IP/Link regex patterns from config: " + e.getMessage());
            ipPattern = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"); 
            linkPattern = Pattern.compile("([a-zA-Z0-9]+(-[a-zA-Z0-9]+)*\\.)+[a-zA-Z]{2,}(:[0-9]{1,5})?(/[^ \\s]*)?", Pattern.CASE_INSENSITIVE);
            linkIpFilterEnabled = false; 
            getLogger().warning("Link/IP filter has been disabled due to invalid regex in config. Please check your regex patterns.");
        }
        allowedDomains = config.getStringList("link-ip-filter.allowed-domains").stream()
                                .map(String::toLowerCase) 
                                .collect(Collectors.toList());
        linkIpFilterPlayerWarning = config.getString("link-ip-filter.player-warning-message", "&cPlease do not send links or IP addresses in chat.");
        linkIpFilterAdminNotification = config.getString("link-ip-filter.admin-notification-message", "&c[Alert] Player %player% tried to send a(n) %type%: %content%");
        
        placeholderApiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cpreload")) {
            if (!sender.hasPermission("chatplugin.reload")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }
            this.reloadConfig();      
            this.loadConfigValues();  
            sender.sendMessage(ChatColor.GREEN + "ChatPlugin configuration has been reloaded.");
            getLogger().info("Configuration reloaded by " + sender.getName() + ".");
            logStatusMessages();
            return true;
        }
        return false;
    }

    // Getters for chat settings
    public int getLocalChatRadius() { return localChatRadius; }
    public String getGlobalChatPrefix() { return globalChatPrefix; }
    public String getLocalChatFormat() { return localChatFormat; }
    public String getGlobalChatFormat() { return globalChatFormat; }

    // Getters for anti-spam settings
    public boolean isAntiSpamEnabled() { return antiSpamEnabled; }
    public int getAntiSpamMessageLimit() { return antiSpamMessageLimit; }
    public int getAntiSpamTimePeriodSeconds() { return antiSpamTimePeriodSeconds; }
    public int getAntiSpamCooldownSeconds() { return antiSpamCooldownSeconds; }
    public String getAntiSpamWarningMessage() { return antiSpamWarningMessage; }
    public String getAntiSpamCooldownOverMessage() { return antiSpamCooldownOverMessage; }

    // Getters for blocked words filter settings
    public boolean isBlockedWordsEnabled() { return blockedWordsEnabled; }
    public List<String> getBlockedWordsList() { return blockedWordsList; }
    public String getBlockedWordsPlayerWarning() { return blockedWordsPlayerWarning; }
    public String getBlockedWordsAdminNotification() { return blockedWordsAdminNotification; }

    // Getters for Link/IP filter settings
    /** @return True if the Link/IP address filter is enabled in the config. */
    public boolean isLinkIpFilterEnabled() { return linkIpFilterEnabled; }
    /** @return Compiled regex Pattern for detecting IP addresses. */
    public Pattern getIpPattern() { return ipPattern; }
    /** @return Compiled regex Pattern for detecting links (case-insensitive). */
    public Pattern getLinkPattern() { return linkPattern; }
    /** @return List of lowercase domain names that are exempt from the link filter. */
    public List<String> getAllowedDomains() { return allowedDomains; }
    /** @return The warning message for players attempting to send a link or IP address. */
    public String getLinkIpFilterPlayerWarning() { return linkIpFilterPlayerWarning; }
    /** @return The notification message format for admins when a link or IP is detected. */
    public String getLinkIpFilterAdminNotification() { return linkIpFilterAdminNotification; }

    /** @return True if PlaceholderAPI is available on the server. */
    public boolean isPlaceholderApiAvailable() { return placeholderApiAvailable; }
}

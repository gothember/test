package com.example.chatplugin;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Listener for player chat events.
 * Handles all chat message processing including:
 * - Blocked words filter (with bypass permission).
 * - IP Address / Link filter (with bypass permission and allowed domains).
 * - Anti-spam mechanism (with bypass permission).
 * - Chat type (Global/Local) permission checks.
 * - PlaceholderAPI integration for dynamic content.
 * - Message formatting using ChatFormatter.
 */
public class ChatListener implements Listener {

    private final ChatPlugin plugin;
    private final Map<UUID, LinkedList<Long>> playerMessageTimestamps = new HashMap<>();
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();

    public ChatListener(ChatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles player chat messages at HIGH priority.
     * ignoreCancelled = false means it respects cancellations from plugins at NORMAL or lower priorities
     * if they also use ignoreCancelled = false. Our own filters will cancel the event if triggered.
     * @param event The AsyncPlayerChatEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID playerId = sender.getUniqueId();
        String originalMessage = event.getMessage();

        // --- Blocked Words Filter ---
        // Checks for blocked words unless player has the "chatplugin.bypass.blockedwords" permission.
        if (plugin.isBlockedWordsEnabled() && !sender.hasPermission("chatplugin.bypass.blockedwords")) {
            String lowerCaseMessage = originalMessage.toLowerCase(); // For case-insensitive check
            for (String blockedWord : plugin.getBlockedWordsList()) { // List is already lowercase
                if (lowerCaseMessage.contains(blockedWord)) {
                    // Calls helper to cancel event, warn player, and notify staff
                    handleBlockedContent(event, sender, "blocked word", blockedWord,
                                           plugin.getBlockedWordsPlayerWarning(),
                                           plugin.getBlockedWordsAdminNotification().replace("%word%", blockedWord));
                    return; // Message blocked, stop further processing by this listener
                }
            }
        }

        // --- IP/Link Detection Filter ---
        // Detects IP addresses and links unless player has the "chatplugin.bypass.iplinkcheck" permission.
        // This filter runs after the blocked words filter.
        if (plugin.isLinkIpFilterEnabled() && !sender.hasPermission("chatplugin.bypass.iplinkcheck")) {
            // Check for IP addresses using the configured regex pattern
            Matcher ipMatcher = plugin.getIpPattern().matcher(originalMessage);
            if (ipMatcher.find()) {
                String detectedIp = ipMatcher.group(0); // The matched IP address
                // Calls helper to handle consequences
                handleBlockedContent(event, sender, "IP address", detectedIp,
                                       plugin.getLinkIpFilterPlayerWarning(),
                                       plugin.getLinkIpFilterAdminNotification().replace("%type%", "IP address").replace("%content%", detectedIp));
                return; // Message blocked, stop further processing
            }

            // Check for links using the configured regex pattern (case-insensitive by default in ChatPlugin)
            Matcher linkMatcher = plugin.getLinkPattern().matcher(originalMessage);
            if (linkMatcher.find()) {
                String detectedLink = linkMatcher.group(0); // The matched link
                boolean isAllowed = false;
                // Check if the domain of the detected link is in the list of allowed domains.
                // Allowed domains are stored in lowercase in ChatPlugin.
                for (String allowedDomain : plugin.getAllowedDomains()) {
                    if (detectedLink.toLowerCase().contains(allowedDomain)) { // Case-insensitive check against allowed domains
                        isAllowed = true;
                        break; // Link is allowed, no need to check further allowed domains
                    }
                }
                if (!isAllowed) {
                    // Link is not in the allowed list, so block it.
                    // Calls helper to handle consequences
                    handleBlockedContent(event, sender, "link", detectedLink,
                                           plugin.getLinkIpFilterPlayerWarning(),
                                           plugin.getLinkIpFilterAdminNotification().replace("%type%", "link").replace("%content%", detectedLink));
                    return; // Message blocked, stop further processing
                }
            }
        }

        // --- Anti-Spam Check ---
        // Applies anti-spam rules unless player has the "chatplugin.bypass.antispam" permission.
        if (plugin.isAntiSpamEnabled() && !sender.hasPermission("chatplugin.bypass.antispam")) {
            long currentTime = System.currentTimeMillis();
            if (playerCooldowns.containsKey(playerId)) {
                long cooldownEndTime = playerCooldowns.get(playerId);
                if (currentTime < cooldownEndTime) {
                    long timeLeft = (cooldownEndTime - currentTime) / 1000;
                    String warningMsg = plugin.getAntiSpamWarningMessage().replace("%cooldown%", String.valueOf(timeLeft + 1));
                    sender.sendMessage(ChatFormatter.formatMessage(warningMsg));
                    event.setCancelled(true);
                    return;
                } else {
                    playerCooldowns.remove(playerId);
                    // Cooldown over message is primarily handled by the BukkitRunnable to avoid duplicates.
                    // If desired, could send a message here if the runnable hasn't fired yet.
                }
            }
            playerMessageTimestamps.putIfAbsent(playerId, new LinkedList<>());
            LinkedList<Long> timestamps = playerMessageTimestamps.get(playerId);
            timestamps.add(currentTime);
            timestamps.removeIf(time -> time < currentTime - (plugin.getAntiSpamTimePeriodSeconds() * 1000L));
            if (timestamps.size() > plugin.getAntiSpamMessageLimit()) {
                final long cooldownEndTime = currentTime + (plugin.getAntiSpamCooldownSeconds() * 1000L);
                playerCooldowns.put(playerId, cooldownEndTime);
                timestamps.clear();
                String warningMsg = plugin.getAntiSpamWarningMessage().replace("%cooldown%", String.valueOf(plugin.getAntiSpamCooldownSeconds()));
                sender.sendMessage(ChatFormatter.formatMessage(warningMsg));
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (playerCooldowns.containsKey(playerId) && playerCooldowns.get(playerId) == cooldownEndTime) {
                             if (System.currentTimeMillis() >= cooldownEndTime) {
                                playerCooldowns.remove(playerId);
                                if(sender.isOnline()){
                                   sender.sendMessage(ChatFormatter.formatMessage(plugin.getAntiSpamCooldownOverMessage()));
                                }
                            }
                        }
                    }
                }.runTaskLater(plugin, plugin.getAntiSpamCooldownSeconds() * 20L);
                event.setCancelled(true);
                return;
            }
        }

        // --- Chat Type Permissions & Formatting ---
        // If the message passed all filters (or bypasses were used), proceed.
        // Cancel original event as we are handling chat formatting and sending manually.
        event.setCancelled(true);

        String messageContent;
        String chatFormat;
        boolean isGlobalChat;
        String globalPrefix = plugin.getGlobalChatPrefix();

        // Determine chat type (global or local) based on prefix and check permissions.
        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            // Player intends to send a global message.
            // Check for "chatplugin.globalchat" permission.
            if (!sender.hasPermission("chatplugin.globalchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use global chat.");
                return; // Stop processing; player lacks permission for intended chat type.
            }
            messageContent = originalMessage.substring(globalPrefix.length());
            chatFormat = plugin.getGlobalChatFormat();
            isGlobalChat = true;
        } else {
            // Message is local by default.
            // Check for "chatplugin.localchat" permission.
            if (!sender.hasPermission("chatplugin.localchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use local chat.");
                return; // Stop processing; player lacks permission for local chat.
            }
            messageContent = originalMessage;
            chatFormat = plugin.getLocalChatFormat();
            isGlobalChat = false;
        }

        // --- Message Formatting and Distribution ---
        String formattedContent = ChatFormatter.formatMessage(messageContent);
        String prePlaceholderMessage = chatFormat.replace("%player%", sender.getName())
                                             .replace("%message%", formattedContent);

        String postPlaceholderMessage;
        if (plugin.isPlaceholderApiAvailable()) {
            postPlaceholderMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            postPlaceholderMessage = prePlaceholderMessage;
        }

        String finalMessage = ChatFormatter.formatMessage(postPlaceholderMessage);

        if (isGlobalChat) {
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                recipient.sendMessage(finalMessage);
            }
            Bukkit.getConsoleSender().sendMessage(finalMessage);
        } else { // Local chat
            Bukkit.getConsoleSender().sendMessage(finalMessage);
            int localRadius = plugin.getLocalChatRadius();
            double localRadiusSquared = localRadius * localRadius;
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (recipient.getWorld().equals(sender.getWorld())) {
                    if (sender.equals(recipient)) {
                        recipient.sendMessage(finalMessage);
                        continue;
                    }
                    if (recipient.getLocation().distanceSquared(sender.getLocation()) <= localRadiusSquared) {
                        recipient.sendMessage(finalMessage);
                    }
                }
            }
        }
    }

    /**
     * Helper method to handle actions when a message is blocked by a filter (words, IP, link).
     * This method centralizes the logic for cancelling the event, warning the player,
     * and notifying staff members who have the appropriate permission.
     * @param event The AsyncPlayerChatEvent that is being processed.
     * @param sender The player who sent the potentially offending message.
     * @param type A string describing the type of content blocked (e.g., "blocked word", "IP address", "link").
     * @param content The actual blocked content/text that triggered the filter.
     * @param playerWarning The warning message to be sent to the player (already formatted or raw).
     * @param adminNotificationFormat The format string for the notification to admins.
     *        This string should already have specific placeholders like %word% or %type%/%content% filled by the caller.
     *        It may still contain %player% which will be replaced here.
     */
    private void handleBlockedContent(AsyncPlayerChatEvent event, Player sender, String type, String content, String playerWarning, String adminNotificationFormat) {
        event.setCancelled(true); // Stop the message from being broadcast further.

        // Send the configured warning message to the player.
        if (playerWarning != null && !playerWarning.isEmpty()) {
            sender.sendMessage(ChatFormatter.formatMessage(playerWarning));
        }

        // Prepare and send the notification to staff.
        if (adminNotificationFormat != null && !adminNotificationFormat.isEmpty()) {
            String adminNotification = adminNotificationFormat; // Base notification string
            // Replace %player% placeholder if present. Other specific placeholders (%word%, %type%, %content%)
            // are expected to be pre-filled by the calling filter logic.
            if (adminNotification.contains("%player%")) {
                 adminNotification = adminNotification.replace("%player%", sender.getName());
            }

            // Ensure the final admin notification is color-formatted.
            final String finalAdminNotification = ChatFormatter.formatMessage(adminNotification);

            // Send to online players who have the "chatplugin.notifyblockedword" permission.
            // This single permission is used for all filter notifications for simplicity.
            Bukkit.getOnlinePlayers().forEach(onlinePlayer -> {
                if (onlinePlayer.hasPermission("chatplugin.notifyblockedword")) {
                    onlinePlayer.sendMessage(finalAdminNotification);
                }
            });
            // Log the notification to the server console for record-keeping.
            plugin.getLogger().info(finalAdminNotification);
        }
    }
}

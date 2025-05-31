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
import java.util.regex.Pattern;

/**
 * Listener for player chat events.
 * Handles all chat message processing including:
 * - Blocked words filter (with bypass permission).
 * - IP Address / Link filter (with bypass permission and allowed domains).
 * - Anti-spam mechanism (with bypass permission).
 * - Chat type (Global/Local) permission checks.
 * - Player mentions (highlighting names with prefix and color).
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
     * ignoreCancelled = false means it respects cancellations from plugins at NORMAL or lower priorities.
     * Processing order: Filters -> Anti-Spam -> Player Mentions -> Permissions & Final Formatting.
     * @param event The AsyncPlayerChatEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID playerId = sender.getUniqueId();
        String originalMessage = event.getMessage();
        String messageContent = originalMessage; // This string will be modified by filters and mentions

        // --- Blocked Words Filter ---
        if (plugin.isBlockedWordsEnabled() && !sender.hasPermission("chatplugin.bypass.blockedwords")) {
            String lowerCaseCurrentMessage = messageContent.toLowerCase();
            for (String blockedWord : plugin.getBlockedWordsList()) {
                if (lowerCaseCurrentMessage.contains(blockedWord)) {
                    handleBlockedContent(event, sender, "blocked word", blockedWord,
                                           plugin.getBlockedWordsPlayerWarning(),
                                           plugin.getBlockedWordsAdminNotification().replace("%word%", blockedWord));
                    return;
                }
            }
        }

        // --- IP/Link Detection Filter ---
        if (plugin.isLinkIpFilterEnabled() && !sender.hasPermission("chatplugin.bypass.iplinkcheck")) {
            Matcher ipMatcher = plugin.getIpPattern().matcher(messageContent);
            if (ipMatcher.find()) {
                String detectedIp = ipMatcher.group(0);
                handleBlockedContent(event, sender, "IP address", detectedIp,
                                       plugin.getLinkIpFilterPlayerWarning(),
                                       plugin.getLinkIpFilterAdminNotification().replace("%type%", "IP address").replace("%content%", detectedIp));
                return;
            }
            Matcher linkMatcher = plugin.getLinkPattern().matcher(messageContent);
            if (linkMatcher.find()) {
                String detectedLink = linkMatcher.group(0);
                boolean isAllowed = plugin.getAllowedDomains().stream()
                                        .anyMatch(allowedDomain -> detectedLink.toLowerCase().contains(allowedDomain));
                if (!isAllowed) {
                    handleBlockedContent(event, sender, "link", detectedLink,
                                           plugin.getLinkIpFilterPlayerWarning(),
                                           plugin.getLinkIpFilterAdminNotification().replace("%type%", "link").replace("%content%", detectedLink));
                    return;
                }
            }
        }

        // --- Anti-Spam Check ---
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

        // --- Player Mentions Processing ---
        // This section modifies 'messageContent' to highlight player names with a prefix and color.
        // It runs after filters and anti-spam checks.
        if (plugin.isPlayerMentionsEnabled()) {
            String mentionPrefix = plugin.getPlayerMentionsPrefix();
            String mentionHexColor = plugin.getPlayerMentionsHexColor();
            // boolean requireOnline = plugin.getPlayerMentionsRequireOnline(); // Implicitly true due to iterating Bukkit.getOnlinePlayers()

            StringBuffer messageAfterMentions = new StringBuffer(messageContent);

            // Iterate through all online players to check if they are mentioned.
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                // Players cannot mention themselves.
                if (onlinePlayer.equals(sender)) {
                    continue;
                }

                String playerName = onlinePlayer.getName();
                // Construct a regex pattern to find the mention: "prefix" + "playerName".
                // Pattern.quote ensures the prefix and player name are treated literally.
                // \\b ensures that the player's name is matched as a whole word (bounded by non-word characters).
                // Pattern.CASE_INSENSITIVE ensures that "PlayerName", "playername", etc., are all matched.
                Pattern mentionPattern = Pattern.compile(
                    Pattern.quote(mentionPrefix) + "\\Q" + playerName + "\\E\\b",
                    Pattern.CASE_INSENSITIVE
                );

                // Match against the current state of the message content (after potential previous replacements).
                Matcher mentionMatcher = mentionPattern.matcher(messageAfterMentions.toString());
                StringBuffer currentPassBuffer = new StringBuffer(); // Buffer for replacements in this specific player's iteration.

                while (mentionMatcher.find()) {
                    String fullMentionFound = mentionMatcher.group(0); // The exact text matched (e.g., "@PlayerName")
                    // The replacement string prepends the configured HEX color to the found mention.
                    // ChatFormatter will later translate this HEX color string.
                    String replacement = mentionHexColor + fullMentionFound;
                    // Use Matcher.quoteReplacement to handle any special characters in the replacement string itself (though unlikely here).
                    mentionMatcher.appendReplacement(currentPassBuffer, Matcher.quoteReplacement(replacement));
                }
                mentionMatcher.appendTail(currentPassBuffer);
                // Update messageAfterMentions with the changes from this player's iteration.
                messageAfterMentions = currentPassBuffer;
            }
            messageContent = messageAfterMentions.toString(); // Store the final content after all players have been processed.
        }


        // --- Chat Type Permissions & Final Formatting ---
        // All filters and modifications to messageContent are done. Now determine chat type and format.
        event.setCancelled(true);

        String finalOutputMessage; // This will hold the fully formatted message string
        String chatFormatToUse;
        boolean isGlobalChat;
        String globalPrefix = plugin.getGlobalChatPrefix();
        // actualContentToFormat is the message content after all filters and mentions.
        String actualContentToFormat = messageContent;

        // Determine if the message is global or local based on the *original* message's prefix.
        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            if (!sender.hasPermission("chatplugin.globalchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use global chat.");
                return;
            }
            // If the original message started with the global prefix,
            // we need to ensure the prefix is removed from our (potentially modified) actualContentToFormat.
            // This handles cases where mentions might interact with the prefix.
            if (actualContentToFormat.toLowerCase().startsWith(globalPrefix.toLowerCase())) {
                 actualContentToFormat = actualContentToFormat.substring(globalPrefix.length());
            }
            chatFormatToUse = plugin.getGlobalChatFormat();
            isGlobalChat = true;
        } else {
            if (!sender.hasPermission("chatplugin.localchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use local chat.");
                return;
            }
            chatFormatToUse = plugin.getLocalChatFormat();
            isGlobalChat = false;
        }

        // Step 1: Apply ChatFormatter to the actual content part (which includes mentions).
        String coloredContent = ChatFormatter.formatMessage(actualContentToFormat);

        // Step 2: Insert the colored content into the chat format string.
        String prePlaceholderMessage = chatFormatToUse.replace("%player%", sender.getName())
                                             .replace("%message%", coloredContent);

        // Step 3: Apply PlaceholderAPI placeholders to the combined string.
        if (plugin.isPlaceholderApiAvailable()) {
            finalOutputMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            finalOutputMessage = prePlaceholderMessage;
        }

        // Step 4: Final pass of ChatFormatter on the entire message (format string colors + PAPI output).
        finalOutputMessage = ChatFormatter.formatMessage(finalOutputMessage);

        // Distribute the final message.
        if (isGlobalChat) {
            Bukkit.getOnlinePlayers().forEach(recipient -> recipient.sendMessage(finalOutputMessage));
            Bukkit.getConsoleSender().sendMessage(finalOutputMessage);
        } else { // Local chat
            Bukkit.getConsoleSender().sendMessage(finalOutputMessage);
            int localRadius = plugin.getLocalChatRadius();
            double localRadiusSquared = localRadius * localRadius;
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (recipient.getWorld().equals(sender.getWorld())) {
                    // Send to self or if within radius
                    if (sender.equals(recipient) || recipient.getLocation().distanceSquared(sender.getLocation()) <= localRadiusSquared) {
                        recipient.sendMessage(finalOutputMessage);
                    }
                }
            }
        }
    }

    /**
     * Helper method to handle actions when a message is blocked by a filter (words, IP, link).
     * Cancels the event, warns the player, and notifies staff.
     * @param event The AsyncPlayerChatEvent.
     * @param sender The player who sent the message.
     * @param type Type of content blocked (e.g., "blocked word"). Used in admin notifications.
     * @param content The actual blocked content/text. Used in admin notifications.
     * @param playerWarning Warning message for the player.
     * @param adminNotificationFormat Admin notification format string. Specific placeholders like %word% or %type%/%content%
     *        should be pre-filled by the caller. This method handles replacing %player% if present.
     */
    private void handleBlockedContent(AsyncPlayerChatEvent event, Player sender, String type, String content, String playerWarning, String adminNotificationFormat) {
        event.setCancelled(true); // Stop the message.
        // Warn the player.
        if (playerWarning != null && !playerWarning.isEmpty()) {
            sender.sendMessage(ChatFormatter.formatMessage(playerWarning));
        }
        // Notify staff.
        if (adminNotificationFormat != null && !adminNotificationFormat.isEmpty()) {
            String adminNotification = adminNotificationFormat;
            // Replace %player% placeholder. Other placeholders are expected to be filled by the calling code.
            if (adminNotification.contains("%player%")) {
                 adminNotification = adminNotification.replace("%player%", sender.getName());
            }
            final String finalAdminNotification = ChatFormatter.formatMessage(adminNotification); // Apply colors to the notification.
            // Send to online staff with permission.
            Bukkit.getOnlinePlayers().forEach(onlinePlayer -> {
                if (onlinePlayer.hasPermission("chatplugin.notifyblockedword")) {
                    onlinePlayer.sendMessage(finalAdminNotification);
                }
            });
            plugin.getLogger().info(finalAdminNotification); // Log to console.
        }
    }
}

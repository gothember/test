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
 * - Player mentions (highlighting names in chat).
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
     * Filters (blocked words, IP/link), anti-spam, and permission checks are applied before
     * player mentions and final formatting.
     * @param event The AsyncPlayerChatEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID playerId = sender.getUniqueId();
        String originalMessage = event.getMessage();

        // --- Filters (Blocked Words, IP/Link) ---
        // These run first. If a message is blocked, further processing is skipped.
        if (plugin.isBlockedWordsEnabled() && !sender.hasPermission("chatplugin.bypass.blockedwords")) {
            String lowerCaseMessage = originalMessage.toLowerCase();
            for (String blockedWord : plugin.getBlockedWordsList()) {
                if (lowerCaseMessage.contains(blockedWord)) {
                    handleBlockedContent(event, sender, "blocked word", blockedWord,
                                           plugin.getBlockedWordsPlayerWarning(),
                                           plugin.getBlockedWordsAdminNotification().replace("%word%", blockedWord));
                    return;
                }
            }
        }

        if (plugin.isLinkIpFilterEnabled() && !sender.hasPermission("chatplugin.bypass.iplinkcheck")) {
            Matcher ipMatcher = plugin.getIpPattern().matcher(originalMessage);
            if (ipMatcher.find()) {
                String detectedIp = ipMatcher.group(0);
                handleBlockedContent(event, sender, "IP address", detectedIp,
                                       plugin.getLinkIpFilterPlayerWarning(),
                                       plugin.getLinkIpFilterAdminNotification().replace("%type%", "IP address").replace("%content%", detectedIp));
                return;
            }
            Matcher linkMatcher = plugin.getLinkPattern().matcher(originalMessage);
            if (linkMatcher.find()) {
                String detectedLink = linkMatcher.group(0);
                boolean isAllowed = false;
                for (String allowedDomain : plugin.getAllowedDomains()) {
                    if (detectedLink.toLowerCase().contains(allowedDomain)) {
                        isAllowed = true;
                        break;
                    }
                }
                if (!isAllowed) {
                    handleBlockedContent(event, sender, "link", detectedLink,
                                           plugin.getLinkIpFilterPlayerWarning(),
                                           plugin.getLinkIpFilterAdminNotification().replace("%type%", "link").replace("%content%", detectedLink));
                    return;
                }
            }
        }

        // --- Anti-Spam Check ---
        // Applied if the message was not caught by previous filters.
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
                    // Cooldown over message mainly handled by BukkitRunnable to avoid duplicates.
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

        // --- Chat Type Permissions & Initial Message Content ---
        // If we reach here, the message has passed all filters and anti-spam checks.
        event.setCancelled(true); // We are manually handling formatting and distribution.

        String messageContent; // The actual text part of the message, after prefix removal if any.
        String chatFormat;
        boolean isGlobalChat;
        String globalPrefix = plugin.getGlobalChatPrefix();

        // Determine chat type (global or local) and verify permissions.
        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            if (!sender.hasPermission("chatplugin.globalchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use global chat.");
                return;
            }
            messageContent = originalMessage.substring(globalPrefix.length());
            chatFormat = plugin.getGlobalChatFormat();
            isGlobalChat = true;
        } else {
            if (!sender.hasPermission("chatplugin.localchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use local chat.");
                return;
            }
            messageContent = originalMessage;
            chatFormat = plugin.getLocalChatFormat();
            isGlobalChat = false;
        }

        // --- Player Mentions Processing ---
        // This modifies `messageContent` to highlight mentioned player names.
        // It runs after filters and anti-spam, on the actual content of the message.
        if (plugin.isPlayerMentionsEnabled()) {
            String mentionPrefix = plugin.getPlayerMentionsPrefix();
            String mentionColor = plugin.getPlayerMentionsHexColor(); // HEX color string (e.g., "&#RRGGBB")
            boolean requireOnline = plugin.getPlayerMentionsRequireOnline();

            // Iterate through online players to find and colorize mentions.
            // Note: If requireOnline is false, this logic would ideally check against all known player names,
            // but Bukkit's API doesn't easily provide this. This implementation focuses on online players.
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String playerName = onlinePlayer.getName();

                // Define the pattern for mentioning this specific player.
                // It looks for the mention prefix followed by the player's name.
                // Uses Pattern.quote for the prefix and player name to treat them literally in regex,
                // and Pattern.CASE_INSENSITIVE for the player name part.
                Pattern mentionPattern = Pattern.compile(
                    Pattern.quote(mentionPrefix) + // Literal prefix (e.g., "@")
                    "(" + Pattern.quote(playerName) + ")", // Capture the player's name
                    Pattern.CASE_INSENSITIVE // Match player name case-insensitively
                );
                Matcher matcher = mentionPattern.matcher(messageContent);

                // If a mention is found, replace it with the colored version.
                // The ChatFormatter will later translate the HEX color string.
                if (matcher.find()) {
                    // group(0) is the full match (e.g., "@PlayerName")
                    String fullMatch = matcher.group(0);
                    // The replacement string colors the full match.
                    String replacement = mentionColor + fullMatch;
                    // Using replaceAll in case a player is mentioned multiple times.
                    messageContent = matcher.replaceAll(replacement);
                }
            }
        }

        // --- Final Message Formatting and Distribution ---
        // Apply base color formatting to the message content (which may now include highlighted mentions).
        String formattedContent = ChatFormatter.formatMessage(messageContent);

        // Insert the processed message content into the selected chat format string.
        String prePlaceholderMessage = chatFormat.replace("%player%", sender.getName())
                                             .replace("%message%", formattedContent);

        // Apply PlaceholderAPI placeholders if available.
        String postPlaceholderMessage;
        if (plugin.isPlaceholderApiAvailable()) {
            postPlaceholderMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            postPlaceholderMessage = prePlaceholderMessage;
        }

        // Final color formatting for the entire message (including format string colors and PAPI placeholders).
        String finalMessage = ChatFormatter.formatMessage(postPlaceholderMessage);

        // Distribute the message globally or locally.
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
     * Cancels the event, warns the player, and notifies staff.
     * @param event The AsyncPlayerChatEvent.
     * @param sender The player who sent the message.
     * @param type Type of content blocked (e.g., "blocked word").
     * @param content The actual blocked content.
     * @param playerWarning Warning message for the player.
     * @param adminNotificationFormat Admin notification format string (may contain %player%).
     *        Specific placeholders like %word% or %type%/%content% should be pre-filled by the caller.
     */
    private void handleBlockedContent(AsyncPlayerChatEvent event, Player sender, String type, String content, String playerWarning, String adminNotificationFormat) {
        event.setCancelled(true);
        if (playerWarning != null && !playerWarning.isEmpty()) {
            sender.sendMessage(ChatFormatter.formatMessage(playerWarning));
        }
        if (adminNotificationFormat != null && !adminNotificationFormat.isEmpty()) {
            String adminNotification = adminNotificationFormat;
            if (adminNotification.contains("%player%")) { // Replace %player% if present
                 adminNotification = adminNotification.replace("%player%", sender.getName());
            }
            final String finalAdminNotification = ChatFormatter.formatMessage(adminNotification);
            Bukkit.getOnlinePlayers().forEach(onlinePlayer -> {
                if (onlinePlayer.hasPermission("chatplugin.notifyblockedword")) {
                    onlinePlayer.sendMessage(finalAdminNotification);
                }
            });
            plugin.getLogger().info(finalAdminNotification);
        }
    }
}

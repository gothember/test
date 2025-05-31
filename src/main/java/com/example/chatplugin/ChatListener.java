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
        String messageContent = originalMessage;

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
        if (plugin.isPlayerMentionsEnabled()) {
            String mentionPrefix = plugin.getPlayerMentionsPrefix();
            String mentionHexColor = plugin.getPlayerMentionsHexColor();
            StringBuffer messageAfterMentions = new StringBuffer(messageContent);
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.equals(sender)) {
                    continue;
                }
                String playerName = onlinePlayer.getName();
                Pattern mentionPattern = Pattern.compile(
                    Pattern.quote(mentionPrefix) + "\\Q" + playerName + "\\E\\b",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher mentionMatcher = mentionPattern.matcher(messageAfterMentions.toString());
                StringBuffer currentPassBuffer = new StringBuffer();
                while (mentionMatcher.find()) {
                    String fullMentionFound = mentionMatcher.group(0);
                    String replacement = mentionHexColor + fullMentionFound;
                    mentionMatcher.appendReplacement(currentPassBuffer, Matcher.quoteReplacement(replacement));
                }
                mentionMatcher.appendTail(currentPassBuffer);
                messageAfterMentions = currentPassBuffer;
            }
            messageContent = messageAfterMentions.toString();
        }

        // --- Chat Type Permissions & Final Formatting ---
        event.setCancelled(true);

        String finalOutputMessage;
        String chatFormatToUse;
        boolean isGlobalChat;
        String globalPrefix = plugin.getGlobalChatPrefix();
        String actualContentToFormat = messageContent;

        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            if (!sender.hasPermission("chatplugin.globalchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use global chat.");
                return;
            }
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

        String coloredContent = ChatFormatter.formatMessage(actualContentToFormat);
        String prePlaceholderMessage = chatFormatToUse.replace("%player%", sender.getName())
                                             .replace("%message%", coloredContent);

        if (plugin.isPlaceholderApiAvailable()) {
            finalOutputMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            finalOutputMessage = prePlaceholderMessage;
        }

        finalOutputMessage = ChatFormatter.formatMessage(finalOutputMessage);

        // Distribute the final message.
        if (isGlobalChat) {
            // Use an effectively final variable for the lambda expression below.
            // Variables used in lambdas must be final or effectively final.
            final String messageToSend = finalOutputMessage;
            Bukkit.getOnlinePlayers().forEach(recipient -> recipient.sendMessage(messageToSend));
            Bukkit.getConsoleSender().sendMessage(messageToSend); // Send to console as well
        } else { // Local chat
            // Console sees all local chat
            Bukkit.getConsoleSender().sendMessage(finalOutputMessage);
            int localRadius = plugin.getLocalChatRadius();
            double localRadiusSquared = localRadius * localRadius;
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (recipient.getWorld().equals(sender.getWorld())) {
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
        event.setCancelled(true);
        if (playerWarning != null && !playerWarning.isEmpty()) {
            sender.sendMessage(ChatFormatter.formatMessage(playerWarning));
        }
        if (adminNotificationFormat != null && !adminNotificationFormat.isEmpty()) {
            String adminNotification = adminNotificationFormat;
            if (adminNotification.contains("%player%")) {
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

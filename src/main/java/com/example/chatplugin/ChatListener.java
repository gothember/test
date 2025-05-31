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
 * - Player mentions (highlighting names with prefix and color, avoiding double prefixes).
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
        // This section modifies `messageContent` to highlight player names.
        // It iterates through online players, checks if their name is mentioned (case-insensitively, as a whole word),
        // and then applies configured coloring and prefix, avoiding double prefixes if already typed by the sender.
        if (plugin.isPlayerMentionsEnabled()) {
            String mentionPrefix = plugin.getPlayerMentionsPrefix();
            String mentionHexColor = plugin.getPlayerMentionsHexColor();

            // Iterate over a copy of the online players list to prevent ConcurrentModificationException
            // if players log in or out during this processing block.
            List<Player> onlinePlayers = new LinkedList<>(Bukkit.getOnlinePlayers());

            for (Player mentionedPlayer : onlinePlayers) {
                // Players cannot mention themselves.
                if (mentionedPlayer.equals(sender)) {
                    continue;
                }

                String playerName = mentionedPlayer.getName();
                // Regex to find the player's name (case-insensitive, whole word).
                // Pattern.quote (\Q...\E) ensures the player's name is treated literally.
                // \b denotes a word boundary, preventing partial matches (e.g., "Player" in "SuperPlayer").
                Pattern namePattern = Pattern.compile("\\b\\Q" + playerName + "\\E\\b", Pattern.CASE_INSENSITIVE);

                // Match against the current version of messageContent. This is important because messageContent
                // is updated after each player's mentions are processed, allowing subsequent players' mentions
                // to be found in a string that already includes highlights from previous players.
                Matcher nameMatcher = namePattern.matcher(messageContent);

                StringBuffer messageBufferForThisPlayer = new StringBuffer(); // Used to reconstruct messageContent with mentions for this player.
                int lastAppendPosition = 0; // Tracks the end of the last processed match.

                while (nameMatcher.find(lastAppendPosition)) {
                    // Append the portion of messageContent before the current match.
                    messageBufferForThisPlayer.append(messageContent, lastAppendPosition, nameMatcher.start());

                    String actualFoundName = nameMatcher.group(0); // The exact matched name string (e.g., "PlayerB", "playerb").
                    boolean prefixIsAlreadyPresent = false;

                    // Check if the configured mentionPrefix (e.g., "@") is already present immediately before the found name.
                    // This check is only performed if the mentionPrefix is not empty.
                    if (!mentionPrefix.isEmpty() && nameMatcher.start() >= mentionPrefix.length()) {
                        // Extract the substring that would be the prefix.
                        String potentialPrefix = messageContent.substring(nameMatcher.start() - mentionPrefix.length(), nameMatcher.start());
                        if (potentialPrefix.equals(mentionPrefix)) {
                            prefixIsAlreadyPresent = true;
                        }
                    }

                    if (prefixIsAlreadyPresent) {
                        // The player already typed the prefix (e.g., "@PlayerName").
                        // We color the existing prefix and the name.
                        // The substring includes the already-typed prefix and the matched name.
                        messageBufferForThisPlayer.append(mentionHexColor + messageContent.substring(nameMatcher.start() - mentionPrefix.length(), nameMatcher.end()));
                    } else {
                        // The player typed just the name (e.g., "PlayerName").
                        // We add the configured prefix and then color both the prefix and the name.
                        messageBufferForThisPlayer.append(mentionHexColor + mentionPrefix + actualFoundName);
                    }
                    lastAppendPosition = nameMatcher.end(); // Update position for the next find operation from this point.
                }
                // Append any remaining part of messageContent after the last match for this player.
                messageBufferForThisPlayer.append(messageContent.substring(lastAppendPosition));
                // Update messageContent with the changes from this player's iteration.
                // This new messageContent will be used for the next player in the outer loop.
                messageContent = messageBufferForThisPlayer.toString();
            }
        }

        // --- Chat Type Permissions & Final Formatting ---
        // All filters and content modifications (like mentions) are now complete.
        event.setCancelled(true); // Cancel the original event; we're manually handling distribution.

        String finalOutputMessage;
        String chatFormatToUse;
        boolean isGlobalChat;
        String globalPrefix = plugin.getGlobalChatPrefix();
        // actualContentToFormat is the message content after all filters and mention processing.
        String actualContentToFormat = messageContent;

        // Determine chat type based on *original* message's prefix and check permissions.
        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            if (!sender.hasPermission("chatplugin.globalchat")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use global chat.");
                return;
            }
            // Remove prefix from actualContentToFormat if it's still there (e.g., wasn't part of a mention).
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

        // Step 1: Format the (mention-highlighted) content with ChatFormatter (colors, etc.).
        String coloredContent = ChatFormatter.formatMessage(actualContentToFormat);

        // Step 2: Insert the formatted content into the chat format string.
        String prePlaceholderMessage = chatFormatToUse.replace("%player%", sender.getName())
                                             .replace("%message%", coloredContent);

        // Step 3: Apply PlaceholderAPI placeholders.
        if (plugin.isPlaceholderApiAvailable()) {
            finalOutputMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            finalOutputMessage = prePlaceholderMessage;
        }

        // Step 4: Final pass of ChatFormatter for any colors in the format string or from PAPI.
        finalOutputMessage = ChatFormatter.formatMessage(finalOutputMessage);

        // Distribute the final message.
        if (isGlobalChat) {
            // Use an effectively final variable for the lambda expression below.
            // Variables used in lambdas must be final or effectively final.
            final String messageToSend = finalOutputMessage;
            Bukkit.getOnlinePlayers().forEach(recipient -> recipient.sendMessage(messageToSend));
            Bukkit.getConsoleSender().sendMessage(messageToSend);
        } else { // Local chat
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

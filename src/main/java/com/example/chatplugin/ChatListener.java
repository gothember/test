package com.example.chatplugin;

import me.clip.placeholderapi.PlaceholderAPI; 
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.Bukkit;
// import org.bukkit.ChatColor; // No longer directly used here, ChatFormatter handles colors
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List; // Added import for List, used in blocked words check
import java.util.Map;
import java.util.UUID;

/**
 * Listener for player chat events.
 * Handles chat message processing including:
 * - Blocked words filter (runs first).
 * - Anti-spam mechanism.
 * - Local/global chat distinction.
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
     * Handles player chat messages.
     * EventPriority is HIGH to ensure this listener acts before or after others as intended.
     * ignoreCancelled = true allows it to act even if a lower-priority plugin cancels the event,
     * which can be useful for logging or overriding cancellations for moderation purposes.
     * @param event The AsyncPlayerChatEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        UUID playerId = sender.getUniqueId();
        String originalMessage = event.getMessage();

        // --- Blocked Words Filter ---
        // This section checks if the message contains any configured blocked words.
        // It runs before anti-spam and chat formatting to ensure blocked messages are handled first.
        if (plugin.isBlockedWordsEnabled()) {
            String lowerCaseMessage = originalMessage.toLowerCase(); // For case-insensitive matching.
            List<String> blockedWords = plugin.getBlockedWordsList(); // Fetches list of lowercase blocked words from ChatPlugin.
            
            for (String blockedWord : blockedWords) {
                // Uses a simple 'contains' check. For whole-word-only matching, regex (e.g., \\bword\\b) would be more robust
                // but 'contains' is simpler and catches variations more broadly.
                if (lowerCaseMessage.contains(blockedWord)) {
                    event.setCancelled(true); // Block the message from being sent to other players.
                    
                    // Send warning to the player who used a blocked word.
                    String playerWarning = plugin.getBlockedWordsPlayerWarning();
                    if (playerWarning != null && !playerWarning.isEmpty()) {
                        sender.sendMessage(ChatFormatter.formatMessage(playerWarning));
                    }

                    // Notify admins/staff (players with 'chatplugin.notifyblockedword' permission).
                    String adminNotificationFormat = plugin.getBlockedWordsAdminNotification();
                    if (adminNotificationFormat != null && !adminNotificationFormat.isEmpty()) {
                        String adminNotification = adminNotificationFormat
                                                     .replace("%player%", sender.getName()) // Placeholder for player's name
                                                     .replace("%word%", blockedWord);      // Placeholder for the detected word

                        // Send to online players with the specific permission.
                        Bukkit.getOnlinePlayers().forEach(onlinePlayer -> {
                            if (onlinePlayer.hasPermission("chatplugin.notifyblockedword")) {
                                onlinePlayer.sendMessage(ChatFormatter.formatMessage(adminNotification));
                            }
                        });
                        // Also log the attempt to the server console for record-keeping.
                        // Formatting the console message for consistency and color codes if any.
                        plugin.getLogger().info(ChatFormatter.formatMessage(adminNotification)); 
                    }
                    return; // Stop further processing of this message by this listener (anti-spam, formatting, etc.).
                }
            }
        }

        // --- Anti-Spam Check --- 
        // This section implements message rate limiting to prevent spam.
        if (plugin.isAntiSpamEnabled()) {
            long currentTime = System.currentTimeMillis();
            // Check if player is currently on cooldown
            if (playerCooldowns.containsKey(playerId)) {
                long cooldownEndTime = playerCooldowns.get(playerId);
                if (currentTime < cooldownEndTime) { // Still on cooldown
                    long timeLeft = (cooldownEndTime - currentTime) / 1000;
                    String warningMsg = plugin.getAntiSpamWarningMessage().replace("%cooldown%", String.valueOf(timeLeft + 1));
                    sender.sendMessage(ChatFormatter.formatMessage(warningMsg));
                    event.setCancelled(true); // Message is blocked due to active cooldown
                    return;
                } else { // Cooldown expired
                    playerCooldowns.remove(playerId);
                    if (sender.isOnline()) { // Notify if cooldown ended and they are sending a new message
                        sender.sendMessage(ChatFormatter.formatMessage(plugin.getAntiSpamCooldownOverMessage()));
                    }
                }
            }
            // Record message timestamp and check message rate
            playerMessageTimestamps.putIfAbsent(playerId, new LinkedList<>());
            LinkedList<Long> timestamps = playerMessageTimestamps.get(playerId);
            timestamps.add(currentTime);
            timestamps.removeIf(time -> time < currentTime - (plugin.getAntiSpamTimePeriodSeconds() * 1000L));

            if (timestamps.size() > plugin.getAntiSpamMessageLimit()) { // Exceeded message limit
                final long cooldownEndTime = currentTime + (plugin.getAntiSpamCooldownSeconds() * 1000L);
                playerCooldowns.put(playerId, cooldownEndTime);
                timestamps.clear(); // Clear message history as they are now on cooldown
                String warningMsg = plugin.getAntiSpamWarningMessage().replace("%cooldown%", String.valueOf(plugin.getAntiSpamCooldownSeconds()));
                sender.sendMessage(ChatFormatter.formatMessage(warningMsg));
                
                // Schedule task to notify when cooldown is over and remove from cooldown map
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
                event.setCancelled(true); // Message blocked due to spamming
                return;
            }
        }

        // --- Chat Formatting and Distribution ---
        // If the message was not blocked by filters or anti-spam, proceed with formatting and sending.
        event.setCancelled(true); // We are manually handling distribution, so cancel original event.

        String messageContent;
        String chatFormat;
        boolean isGlobal;
        String globalPrefix = plugin.getGlobalChatPrefix();

        // Determine if message is global or local based on prefix
        if (!globalPrefix.isEmpty() && originalMessage.startsWith(globalPrefix)) {
            messageContent = originalMessage.substring(globalPrefix.length());
            chatFormat = plugin.getGlobalChatFormat();
            isGlobal = true;
        } else {
            messageContent = originalMessage;
            chatFormat = plugin.getLocalChatFormat();
            isGlobal = false;
        }

        // Format the actual message content (e.g., color codes within the message itself)
        String formattedContent = ChatFormatter.formatMessage(messageContent);
        
        // Replace internal placeholders like %player% and %message% in the chosen format string
        String prePlaceholderMessage = chatFormat.replace("%player%", sender.getName())
                                             .replace("%message%", formattedContent);

        // Apply PlaceholderAPI placeholders if the API is available
        String postPlaceholderMessage;
        if (plugin.isPlaceholderApiAvailable()) {
            postPlaceholderMessage = PlaceholderAPI.setPlaceholders(sender, prePlaceholderMessage);
        } else {
            postPlaceholderMessage = prePlaceholderMessage;
        }

        // Final color formatting for the entire message string (including format and PAPI placeholders)
        String finalMessage = ChatFormatter.formatMessage(postPlaceholderMessage);

        // Distribute the message
        if (isGlobal) { // Global message
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                recipient.sendMessage(finalMessage);
            }
            Bukkit.getConsoleSender().sendMessage(finalMessage); // Also to console
        } else { // Local message
            Bukkit.getConsoleSender().sendMessage(finalMessage); // Also to console
            int localRadius = plugin.getLocalChatRadius();
            double localRadiusSquared = localRadius * localRadius; // For efficient distance checking
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (recipient.getWorld().equals(sender.getWorld())) { // Only players in the same world
                    if (sender.equals(recipient)) { // Send to self
                        recipient.sendMessage(finalMessage);
                        continue;
                    }
                    // Check if recipient is within local chat radius
                    if (recipient.getLocation().distanceSquared(sender.getLocation()) <= localRadiusSquared) { 
                        recipient.sendMessage(finalMessage);
                    }
                }
            }
        }
    }
}

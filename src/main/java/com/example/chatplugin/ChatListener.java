package com.example.chatplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.Bukkit;

public class ChatListener implements Listener {

    private final ChatPlugin plugin;
    private final String globalChatPrefix = "!"; // Will be configurable

    public ChatListener(ChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        String originalMessage = event.getMessage();

        event.setCancelled(true); // Cancel the original event

        String messageContent;
        String chatPrefix;
        boolean isGlobal;

        if (originalMessage.startsWith(globalChatPrefix)) {
            messageContent = originalMessage.substring(globalChatPrefix.length());
            chatPrefix = "[Global]";
            isGlobal = true;
        } else {
            messageContent = originalMessage;
            chatPrefix = "[Local]";
            isGlobal = false;
        }

        // Apply color formatting
        String formattedContent = ChatFormatter.formatMessage(messageContent);
        String finalMessage = String.format("%s <%s> %s", chatPrefix, sender.getName(), formattedContent);

        if (isGlobal) {
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                recipient.sendMessage(finalMessage);
            }
            Bukkit.getConsoleSender().sendMessage(finalMessage);
        } else {
            // Local Chat
            Bukkit.getConsoleSender().sendMessage(finalMessage); // Send to console regardless of distance
            for (Player recipient : Bukkit.getOnlinePlayers()) {
                if (recipient.getWorld().equals(sender.getWorld())) {
                    double distance = recipient.getLocation().distance(sender.getLocation());
                    // Radius will be configurable, 100 for now
                    if (distance <= 100) { 
                        recipient.sendMessage(finalMessage);
                    }
                }
            }
        }
    }
}

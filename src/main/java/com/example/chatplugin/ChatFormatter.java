package com.example.chatplugin;

import net.md_5.bungee.api.ChatColor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFormatter {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public static String formatMessage(String message) {
        // Translate standard Minecraft color codes
        message = ChatColor.translateAlternateColorCodes('&', message);

        // Translate HEX color codes (e.g., &#RRGGBB)
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hexCode = matcher.group(1);
            try {
                matcher.appendReplacement(sb, ChatColor.of("#" + hexCode).toString());
            } catch (Exception e) {
                // In case of an invalid HEX code, append the original match
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

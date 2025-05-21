package com.example.chatplugin;

import net.md_5.bungee.api.ChatColor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// It's good practice to import Bukkit or Logger if you need to log from here,
// but for a static utility, direct logging can be tricky without passing a logger instance.
// For this example, we'll assume direct logging isn't set up in this static context
// or use System.err for critical warnings if necessary.

/**
 * Utility class for formatting chat messages.
 * This class provides methods to translate standard Minecraft color codes (e.g., &c)
 * and custom HEX color codes (e.g., &#RRGGBB) into displayable colors in chat.
 */
public class ChatFormatter {

    // Defines a regular expression pattern to find HEX color codes.
    // The pattern looks for "&#" followed by exactly six hexadecimal characters (0-9, A-F, a-f).
    // The six hex characters are captured in a group. Example: &#FF00AA
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * Formats a given message string by translating both standard Minecraft color codes
     * and custom HEX color codes.
     *
     * @param message The raw message string that may contain color codes.
     * @return The formatted message string with all color codes translated
     *         into their respective ChatColor objects, ready for display.
     */
    public static String formatMessage(String message) {
        // Step 1: Translate standard Minecraft color codes (prefixed with '&').
        // ChatColor.translateAlternateColorCodes replaces codes like '&c' with the internal ChatColor equivalent.
        // For example, "&cHello" becomes ChatColor.RED + "Hello".
        message = ChatColor.translateAlternateColorCodes('&', message);

        // Step 2: Translate custom HEX color codes (e.g., &#RRGGBB).
        Matcher matcher = HEX_PATTERN.matcher(message); // Create a matcher for the HEX pattern.
        StringBuffer sb = new StringBuffer(); // Use StringBuffer for efficient string building during replacement.

        // Iterate through all occurrences of the HEX pattern in the message.
        while (matcher.find()) {
            // matcher.group(1) captures the six hexadecimal characters (the RRGGBB part).
            String hexCode = matcher.group(1); 
            try {
                // ChatColor.of("#" + hexCode) converts the HEX string (e.g., "#FF00AA")
                // into its corresponding ChatColor object. This requires a server version
                // that supports HEX colors (e.g., Paper/Spigot 1.16+).
                // The result is then appended to the StringBuffer.
                matcher.appendReplacement(sb, ChatColor.of("#" + hexCode).toString());
            } catch (Exception e) {
                // If ChatColor.of() fails (e.g., invalid HEX, or server version doesn't support it),
                // catch the exception to prevent plugin errors.
                // Log a warning to the console. Using System.err as a simple logger here.
                System.err.println("[ChatPlugin] Failed to parse HEX color: &#" + hexCode + 
                                   ". Ensure you are using Paper/Spigot 1.16+ and the code is valid.");
                // Append the original matched string (e.g., "&#FF00AA") so it's not lost from the message.
                matcher.appendReplacement(sb, matcher.group(0)); 
            }
        }
        // After all replacements, append any remaining part of the input string to the StringBuffer.
        matcher.appendTail(sb);
        
        // Return the fully processed string from the StringBuffer.
        return sb.toString();
    }
}

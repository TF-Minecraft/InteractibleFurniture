package net.tfminecraft.interactiblefurniture.utils;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;

public class DebugUtils {
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static void sendSlotDebug(Player player, SlotDefinition slot, Vector slotPoint, double dist) {
        player.sendMessage(ChatColor.GRAY + "[DEBUG] Slot " + slot.getId() +
            " at " + String.format("%.2f %.2f %.2f", 
                slotPoint.getX(), slotPoint.getY(), slotPoint.getZ()));
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static void sendNewClosestDebug(Player player, SlotDefinition slot, double dist) {
        player.sendMessage(ChatColor.YELLOW + "[DEBUG] New closest: " + slot.getId() +
            " (dist=" + String.format("%.2f", dist) + ")");
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static void sendClickPointDebug(Player player, Vector clickPoint) {
        player.sendMessage(ChatColor.GREEN + "[DEBUG] Click point: " + 
            String.format("%.2f %.2f %.2f",
                clickPoint.getX(), clickPoint.getY(), clickPoint.getZ()));
    }
}
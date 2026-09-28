package net.tfminecraft.interactiblefurniture.protection;

import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.interactiblefurniture.furniture.Furniture;

/**
 * Asks the server's land protection whether a player may act on furniture.
 *
 * WorldGuard checks clicks on a furniture's Interaction entity itself, but it
 * lets clicks on ordinary blocks through without a region check. Furniture
 * that is used through its barrier or attached block would otherwise be open
 * to anyone, so those clicks ask WorldGuard about the furniture's display
 * entity and get the same answer as the Interaction route.
 */
public final class FurnitureProtection {

    /** The protection rules furniture actions are checked against. */
    public interface Rules {
        /** Right-click actions: slots, carrying, picking up and attaching. */
        boolean canInteract(Player player, Furniture furniture);

        /** Punches, which break the furniture. */
        boolean canDamage(Player player, Furniture furniture);
    }

    private static final Rules UNPROTECTED = new Rules() {
        @Override
        public boolean canInteract(Player player, Furniture furniture) {
            return true;
        }

        @Override
        public boolean canDamage(Player player, Furniture furniture) {
            return true;
        }
    };

    private static Rules rules = UNPROTECTED;

    private FurnitureProtection() {}

    /** Follows WorldGuard's region rules when WorldGuard is enabled. */
    public static void hook(Plugin plugin) {
        hook(plugin, WorldGuardRules::create);
    }

    static void hook(Plugin plugin, Supplier<Rules> worldGuard) {
        rules = UNPROTECTED;
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return;
        }
        try {
            rules = worldGuard.get();
            plugin.getLogger().info("Furniture actions follow WorldGuard region rules.");
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("WorldGuard is enabled but its protection query is unavailable: " + e);
        }
    }

    /** Replaces the rules; null restores the unprotected default. */
    public static void use(Rules replacement) {
        rules = replacement != null ? replacement : UNPROTECTED;
    }

    public static boolean canInteract(Player player, Furniture furniture) {
        return player == null || furniture == null || rules.canInteract(player, furniture);
    }

    public static boolean canDamage(Player player, Furniture furniture) {
        return player == null || furniture == null || rules.canDamage(player, furniture);
    }
}

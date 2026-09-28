package net.tfminecraft.interactiblefurniture.protection;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.sk89q.worldguard.bukkit.ProtectionQuery;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;

import net.tfminecraft.interactiblefurniture.furniture.Furniture;

/**
 * WorldGuard's answer for furniture actions. This class refers to WorldGuard
 * types, so it is only loaded once WorldGuard is known to be enabled.
 *
 * The query runs WorldGuard's own checks, including bypass permissions and
 * its deny message, as if the player had used or hit the display entity.
 */
final class WorldGuardRules implements FurnitureProtection.Rules {
    private final ProtectionQuery query;

    WorldGuardRules(ProtectionQuery query) {
        this.query = query;
    }

    static WorldGuardRules create() {
        return new WorldGuardRules(WorldGuardPlugin.inst().createProtectionQuery());
    }

    @Override
    public boolean canInteract(Player player, Furniture furniture) {
        Entity display = display(furniture);
        if (display != null) {
            return query.testEntityInteract(player, display);
        }
        return furniture.getLoc() == null || query.testBlockInteract(player, furniture.getLoc().getBlock());
    }

    @Override
    public boolean canDamage(Player player, Furniture furniture) {
        Entity display = display(furniture);
        if (display != null) {
            return query.testEntityDamage(player, display);
        }
        return furniture.getLoc() == null || query.testBlockBreak(player, furniture.getLoc().getBlock());
    }

    private static Entity display(Furniture furniture) {
        return furniture.getEntityId() != null ? Bukkit.getEntity(furniture.getEntityId()) : null;
    }
}

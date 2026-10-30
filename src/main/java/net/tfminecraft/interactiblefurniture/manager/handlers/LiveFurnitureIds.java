package net.tfminecraft.interactiblefurniture.manager.handlers;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;

import net.tfminecraft.interactiblefurniture.database.Database;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedFurnitureMap;

/**
 * Whether an id belongs to live furniture: a placed piece or one nested in
 * it, as {@link FurnitureRestoreHandler#collectAllFurnitureIds} counts them.
 *
 * It answers from the cheapest place that can say yes: the placed piece under
 * that id, then the pieces in and around the given chunk. Only an id neither
 * knows walks all live furniture, once per lookup.
 */
final class LiveFurnitureIds {

    private final Map<UUID, Furniture> placed;
    private final Chunk near;
    private Set<UUID> nearby;
    private Set<UUID> all;

    LiveFurnitureIds(Map<UUID, Furniture> placed, Chunk near) {
        this.placed = placed;
        this.near = near;
    }

    boolean contains(UUID id) {
        Furniture root = placed.get(id);
        if (root != null && id.equals(root.getEntityId())) {
            return true;
        }
        if (near != null && placed instanceof PlacedFurnitureMap index) {
            if (nearby == null) {
                nearby = collectNear(index, near);
            }
            if (nearby.contains(id)) {
                return true;
            }
        }
        if (all == null) {
            all = new HashSet<>();
            for (Furniture furniture : placed.values()) {
                FurnitureRestoreHandler.collectAllFurnitureIds(furniture, all);
            }
        }
        return all.contains(id);
    }

    /** Nested pieces and their displays usually sit by their root, in this chunk or one next to it. */
    private static Set<UUID> collectNear(PlacedFurnitureMap index, Chunk chunk) {
        Set<UUID> ids = new HashSet<>();
        String world = chunk.getWorld().getName();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Furniture furniture : index.inChunk(new Database.ChunkKey(world, chunk.getX() + dx, chunk.getZ() + dz))) {
                    FurnitureRestoreHandler.collectAllFurnitureIds(furniture, ids);
                }
            }
        }
        return ids;
    }
}

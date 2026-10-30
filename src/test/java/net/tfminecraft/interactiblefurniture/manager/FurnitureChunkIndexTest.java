package net.tfminecraft.interactiblefurniture.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureBreakHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurniturePlacementHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureRestoreHandler;

/**
 * The chunk lookups read an index. After every way furniture is placed,
 * moved, carried, nested, unloaded, loaded or removed, they must still give
 * exactly what walking all placed furniture gave.
 */
class FurnitureChunkIndexTest extends FurnitureTestServer {

    /** The lookup as it was before the index: every placed piece, by block coordinates. */
    private Set<Furniture> scan(Chunk chunk, boolean includeCarried) {
        Set<Furniture> set = new HashSet<>();
        for (Furniture f : manager.getPlacedFurniture().values()) {
            if (!includeCarried && f.isCarried()) continue;
            Location loc = f.getLoc();
            if (loc != null && loc.getBlockX() >> 4 == chunk.getX() && loc.getBlockZ() >> 4 == chunk.getZ()
                    && loc.isWorldLoaded() && chunk.getWorld().equals(loc.getWorld())) {
                set.add(f);
            }
        }
        return set;
    }

    private void assertMatchesScan(World... worlds) {
        for (World in : worlds) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    Chunk chunk = in.getChunkAt(x, z);
                    assertEquals(scan(chunk, false), manager.getFurnitureInChunk(chunk), in.getName() + " " + x + "," + z);
                    assertEquals(scan(chunk, true), manager.getFurnitureForSave(chunk), in.getName() + " " + x + "," + z);
                }
            }
        }
    }

    private Set<Furniture> inChunk(int x, int z) {
        return manager.getFurnitureInChunk(world.getChunkAt(x, z));
    }

    @Test
    void carryingPlacingAndDroppingKeepTheIndexCurrent() {
        Furniture crate = place(CRATE, 4, 4);
        Furniture table = place(TABLE, 8, 4);
        Furniture west = place(CRATE, -3, 4);
        assertEquals(Set.of(crate, table), inChunk(0, 0));
        assertMatchesScan(world);

        crate.carry(player);
        standAt(20, 6);
        assertEquals(Set.of(table), inChunk(0, 0));
        assertEquals(Set.of(crate, table), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertMatchesScan(world);

        assertTrue(FurniturePlacementHandler.placeCarriedFurniture(
                player, ground(20, 4), BlockFace.UP, crate, manager.getPlacedFurniture()));
        assertEquals(Set.of(crate), inChunk(1, 0));
        assertEquals(Set.of(table), inChunk(0, 0));
        assertMatchesScan(world);

        west.carry(player);
        standAt(4, 20);
        west.remove(true);
        assertEquals(Set.of(), inChunk(-1, 0));
        assertMatchesScan(world);

        crate.setLoc(new Location(world, -20, GROUND_Y + 1, -20));
        assertEquals(Set.of(crate), inChunk(-2, -2));
        assertEquals(Set.of(), inChunk(1, 0));
        assertMatchesScan(world);
    }

    @Test
    void nestingTakesAPieceOutOfItsChunkAndTakingItOffPutsItBack() {
        Furniture table = place(TABLE, 8, 4);
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        assertTrue(FurnitureAttachmentHandler.attachFromCarried(table, "surface", crate, player));
        assertEquals(Set.of(table), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertMatchesScan(world);

        Furniture taken = FurnitureAttachmentHandler.detach(table, "surface", player);
        assertNotNull(taken);
        assertEquals(Set.of(table, crate), inChunk(0, 0));
        assertMatchesScan(world);
    }

    @Test
    void unloadingDropsTheChunksPiecesAndLoadingIndexesTheRestoredOnes() {
        Furniture crate = place(CRATE, 4, 4);
        Furniture carried = place(CRATE, 6, 4);
        Furniture neighbour = place(TABLE, 20, 4);
        carried.carry(player);

        unloadChunk(0, 0);
        assertEquals(Set.of(), inChunk(0, 0));
        assertEquals(Set.of(carried), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertEquals(Set.of(neighbour), inChunk(1, 0));
        assertMatchesScan(world);

        // The carried piece's own record is skipped on load, because the piece is still live.
        loadChunk(0, 0);
        Set<Furniture> restored = inChunk(0, 0);
        assertEquals(1, restored.size());
        Furniture reloaded = restored.iterator().next();
        assertEquals(crate.getEntityId(), reloaded.getEntityId());
        assertNotSame(crate, reloaded);
        assertEquals(Set.of(reloaded, carried), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertMatchesScan(world);
    }

    @Test
    void breakingAndPluginRemovalLeaveTheIndex() {
        Furniture crate = place(CRATE, 4, 4);
        Furniture table = place(TABLE, 8, 4);
        assertTrue(FurnitureBreakHandler.removeFurniture(crate.getEntityId(), manager.getPlacedFurniture(), player, "test"));
        assertTrue(manager.removePlugin(table));
        assertEquals(Set.of(), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertMatchesScan(world);
    }

    @Test
    void piecesInOtherWorldsAndUnloadedWorldsStayOutOfThisWorldsChunks() {
        WorldMock other = server.addSimpleWorld("other");
        Furniture here = place(CRATE, 4, 4);
        Furniture there = new Furniture(CRATE, new Location(other, 4.5, GROUND_Y + 1, 4.5), UUID.randomUUID());
        // A world of the same name that is not loaded: its pieces share the index entry but never match.
        Furniture gone = new Furniture(CRATE, new Location(new WorldMock(new WorldCreator("world")), 4.5, GROUND_Y + 1, 4.5),
                UUID.randomUUID());
        List<UUID> fakes = List.of(there.getEntityId(), gone.getEntityId());
        manager.getPlacedFurniture().put(there.getEntityId(), there);
        manager.getPlacedFurniture().put(gone.getEntityId(), gone);
        try {
            assertEquals(Set.of(here), inChunk(0, 0));
            assertEquals(Set.of(there), manager.getFurnitureInChunk(other.getChunkAt(0, 0)));
            assertMatchesScan(world, other);
        } finally {
            fakes.forEach(manager.getPlacedFurniture()::remove);
        }
    }

    @Test
    void reconcilingKeepsNestedAndFarRootedDisplaysAndRemovesOrphans() {
        Furniture table = place(TABLE, 8, 4);
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        assertTrue(FurnitureAttachmentHandler.attachFromCarried(table, "surface", crate, player));
        Furniture farTable = place(TABLE, 40, 40);
        Furniture farCrate = place(CRATE, 41, 40);
        farCrate.carry(player);
        assertTrue(FurnitureAttachmentHandler.attachFromCarried(farTable, "surface", farCrate, player));

        // A display tagged for the far nested piece, standing in chunk (0, 0).
        ItemDisplay farTagged = world.spawn(new Location(world, 2, GROUND_Y + 1, 2), ItemDisplay.class,
                display -> FurniturePlacementHandler.tagDisplay(display, farCrate.getEntityId()));
        ItemDisplay orphan = world.spawn(new Location(world, 3, GROUND_Y + 1, 3), ItemDisplay.class,
                display -> FurniturePlacementHandler.tagDisplay(display, UUID.randomUUID()));

        FurnitureRestoreHandler.reconcileChunk(world.getChunkAt(0, 0), manager.getPlacedFurniture());

        assertTrue(orphan.isDead(), "the orphan display should be removed");
        assertFalse(farTagged.isDead(), "a far nested piece's display is still live");
        assertFalse(Bukkit.getEntity(table.getEntityId()).isDead());
        assertFalse(Bukkit.getEntity(crate.getEntityId()).isDead());
    }
}

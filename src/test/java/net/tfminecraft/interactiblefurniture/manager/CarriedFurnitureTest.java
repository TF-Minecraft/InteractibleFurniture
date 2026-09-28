package net.tfminecraft.interactiblefurniture.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedFurnitureSlot;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurniturePlacementHandler;

/**
 * A crate is picked up in chunk (0, 0) and carried into chunk (1, 0). However
 * the carry ends, the piece and its contents must exist exactly once.
 */
class CarriedFurnitureTest extends FurnitureTestServer {

    private Furniture carryFilledCrateOutOfItsChunk() {
        Furniture crate = place(CRATE, 4, 4);
        fill(crate, "lid", new ItemStack(Material.DIAMOND));
        crate.carry(player);
        assertTrue(savedIn(0, 0).stream()
                        .anyMatch(f -> f.getEntityId().equals(crate.getEntityId()) && f.isPersistedCarried()),
                "carrying should save a recovery record");
        standAt(20, 6);
        return crate;
    }

    private void clearDrops() {
        world.getEntitiesByClass(Item.class).forEach(Entity::remove);
    }

    @Test
    void placingInAnotherChunkLeavesNothingToDropWhenTheOldChunkReloads() {
        Furniture crate = carryFilledCrateOutOfItsChunk();

        assertTrue(FurniturePlacementHandler.placeCarriedFurniture(
                player, ground(20, 4), BlockFace.UP, crate, manager.getPlacedFurniture()));

        assertEquals(List.of(), savedIn(0, 0));
        // The new chunk being unloaded is the case no in-memory check can catch.
        unloadChunk(1, 0);
        unloadChunk(0, 0);
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems());

        loadChunk(1, 0);
        Furniture restored = manager.getPlacedFurniture().get(crate.getEntityId());
        assertNotNull(restored, "the placed crate should load from its new chunk");
        assertEquals(Material.DIAMOND, restored.getActiveSlot("lid").orElseThrow().getCurrentItem().getType());
    }

    @Test
    void aRecordThatCannotBeRemovedYetIsSkippedOnLoadAndRemovedLater() {
        Furniture crate = carryFilledCrateOutOfItsChunk();
        blockWrites(0, 0);

        assertTrue(FurniturePlacementHandler.placeCarriedFurniture(
                player, ground(20, 4), BlockFace.UP, crate, manager.getPlacedFurniture()));
        unloadChunk(1, 0);
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems(), "a record still pending removal must not be restored");

        allowWrites();
        loadChunk(0, 0);
        assertEquals(List.of(), savedIn(0, 0));
        assertEquals(List.of(), droppedItems());
    }

    @Test
    void attachingToFurnitureInAnotherChunkLeavesNothingToDropWhenTheOldChunkReloads() {
        Furniture table = place(TABLE, 20, 4);
        Furniture crate = carryFilledCrateOutOfItsChunk();

        assertTrue(FurnitureAttachmentHandler.attachFromCarried(table, "surface", crate, player));

        assertEquals(List.of(), savedIn(0, 0));
        unloadChunk(1, 0);
        unloadChunk(0, 0);
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems());

        loadChunk(1, 0);
        Furniture restoredTable = manager.getPlacedFurniture().get(table.getEntityId());
        assertNotNull(restoredTable);
        PlacedFurnitureSlot surface = restoredTable.getActiveFurnitureSlot("surface").orElseThrow();
        assertEquals(crate.getEntityId(), surface.getNested().getEntityId());
        assertEquals(Material.DIAMOND,
                surface.getNested().getActiveSlot("lid").orElseThrow().getCurrentItem().getType());
    }

    @Test
    void quittingMidCarryDropsThePieceOnceWhereTheCarrierStood() {
        carryFilledCrateOutOfItsChunk();

        player.disconnect();

        assertEquals(List.of(Material.BARREL, Material.DIAMOND), droppedItems());
        clearDrops();
        unloadChunk(0, 0);
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems());
    }

    @Test
    void shuttingDownMidCarryDropsThePieceAndItsContentsOnce() {
        carryFilledCrateOutOfItsChunk();

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(List.of(Material.BARREL, Material.DIAMOND), droppedItems());
        clearDrops();
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems());
    }

    @Test
    void crashingMidCarryStillRecoversThePieceAndItsContentsOnce() {
        Furniture crate = carryFilledCrateOutOfItsChunk();

        // Nothing ends the carry and nothing more is saved; the next start has only the files.
        manager.getPlacedFurniture().clear();
        server.getEntity(crate.getEntityId()).remove();

        loadChunk(0, 0);
        assertEquals(List.of(Material.BARREL, Material.DIAMOND), droppedItems());
        clearDrops();
        unloadChunk(0, 0);
        loadChunk(0, 0);
        assertEquals(List.of(), droppedItems());
    }

    @Test
    void aStaleCarriedRecordForLiveFurnitureDropsNothingAndLeavesThePieceAlone() {
        Furniture crate = place(CRATE, 20, 4);
        fill(crate, "lid", new ItemStack(Material.DIAMOND));
        // What earlier builds left behind: the same piece, still saved as carried where it was picked up.
        Furniture stale = savedIn(1, 0).get(0);
        stale.setPersistedCarried(true);
        stale.setLoc(new Location(world, 4.5, GROUND_Y + 1.5, 4.5));
        manager.getDatabase().saveChunk(world.getName(), 0, 0, List.of(stale));

        loadChunk(0, 0);

        assertEquals(List.of(), droppedItems());
        assertFalse(server.getEntity(crate.getEntityId()).isDead(), "the live display must be kept");
        assertEquals(List.of(), savedIn(0, 0));
        assertTrue(crate.getActiveSlot("lid").isPresent());
    }

    @Test
    void unloadingTheChunkAPieceWasCarriedFromKeepsItsRecoveryRecord() {
        Furniture stays = place(CRATE, 8, 8);
        Furniture crate = carryFilledCrateOutOfItsChunk();

        unloadChunk(0, 0);

        List<Furniture> saved = savedIn(0, 0);
        assertTrue(saved.stream().anyMatch(f -> f.getEntityId().equals(stays.getEntityId())));
        assertTrue(saved.stream().anyMatch(f -> f.getEntityId().equals(crate.getEntityId()) && f.isPersistedCarried()),
                "a crash after the unload must still be able to recover the carried piece");
        assertTrue(manager.getPlacedFurniture().containsKey(crate.getEntityId()), "the carry goes on");
        assertFalse(manager.getPlacedFurniture().containsKey(stays.getEntityId()));
    }

    @Test
    void changingWorldMidCarryDropsEveryCarriedPieceWithTheirCarriers() {
        Furniture first = carryFilledCrateOutOfItsChunk();
        PlayerMock other = server.addPlayer();
        other.teleport(new Location(world, 8.5, GROUND_Y + 1, 8.5));
        Furniture second = place(CRATE, 8, 8);
        second.carry(other);

        WorldMock elsewhere = server.addSimpleWorld("elsewhere");
        player.teleport(new Location(elsewhere, 0.5, GROUND_Y + 1, 0.5));
        other.teleport(new Location(elsewhere, 3.5, GROUND_Y + 1, 0.5));
        server.getScheduler().performOneTick();

        assertFalse(manager.getPlacedFurniture().containsKey(first.getEntityId()));
        assertFalse(manager.getPlacedFurniture().containsKey(second.getEntityId()));
        assertEquals(List.of(Material.BARREL, Material.BARREL, Material.DIAMOND), droppedItems(elsewhere));
        assertEquals(List.of(), savedIn(0, 0));
    }
}

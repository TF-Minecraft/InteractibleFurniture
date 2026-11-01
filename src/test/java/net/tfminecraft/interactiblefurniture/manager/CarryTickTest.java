package net.tfminecraft.interactiblefurniture.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedFurnitureMap;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureBreakHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurniturePlacementHandler;

/**
 * The carry loop runs every tick but visits only the carried pieces, the only
 * ones whose tick does anything. However a carry starts or ends, and however
 * a piece enters or leaves the placed map, that set must be exactly the
 * placed pieces that are carried.
 */
class CarryTickTest extends FurnitureTestServer {

    private PlacedFurnitureMap placed() {
        return (PlacedFurnitureMap) manager.getPlacedFurniture();
    }

    private Set<Furniture> carried() {
        return placed().carried();
    }

    /** What the loop used to find by walking every placed piece. */
    private void assertCarriedMatchesScan() {
        Set<Furniture> scan = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Furniture f : manager.getPlacedFurniture().values()) {
            if (f.isCarried()) {
                scan.add(f);
            }
        }
        assertEquals(scan, carried());
    }

    /** Swaps a placed piece for a spy of itself, so its ticks can be counted. */
    private Furniture watched(Furniture piece) {
        Furniture watched = spy(piece);
        manager.getPlacedFurniture().put(watched.getEntityId(), watched);
        return watched;
    }

    private PlayerMock carrierAt(int x, int z) {
        PlayerMock carrier = server.addPlayer();
        carrier.teleport(new Location(world, x + 0.5, GROUND_Y + 1, z + 0.5));
        return carrier;
    }

    @Test
    void aCarriedPieceIsTickedEveryTickAndOtherPiecesNever() {
        Furniture crate = watched(place(CRATE, 4, 4));
        Furniture table = watched(place(TABLE, 8, 4));
        Furniture stool = watched(place(STOOL, 12, 4));
        server.getScheduler().performTicks(3);
        assertEquals(Set.of(), carried());

        crate.carry(player);
        assertEquals(Set.of(crate), carried());
        server.getScheduler().performTicks(5);

        verify(crate, times(5)).tick();
        verify(table, never()).tick();
        verify(stool, never()).tick();
        assertTrue(crate.isCarried());
        assertCarriedMatchesScan();
        assertThrows(UnsupportedOperationException.class, () -> carried().add(table));
    }

    @Test
    void piecesWhoseTickEndsTheirCarryLeaveTheSetWithoutDisturbingTheLoop() {
        PlayerMock second = carrierAt(8, 8);
        PlayerMock third = carrierAt(12, 8);
        Furniture leaves = watched(place(CRATE, 4, 4));
        Furniture alsoLeaves = watched(place(CRATE, 8, 8));
        Furniture stays = watched(place(CRATE, 12, 8));
        leaves.carry(player);
        alsoLeaves.carry(second);
        stays.carry(third);

        // Two carriers change world, so those two ticks drop their pieces mid-loop.
        WorldMock elsewhere = server.addSimpleWorld("elsewhere");
        player.teleport(new Location(elsewhere, 0.5, GROUND_Y + 1, 0.5));
        second.teleport(new Location(elsewhere, 3.5, GROUND_Y + 1, 0.5));
        server.getScheduler().performOneTick();

        verify(leaves, times(1)).tick();
        verify(alsoLeaves, times(1)).tick();
        verify(stays, times(1)).tick();
        assertEquals(Set.of(stays), carried());
        assertFalse(manager.getPlacedFurniture().containsKey(leaves.getEntityId()));
        assertCarriedMatchesScan();

        server.getScheduler().performTicks(2);
        verify(leaves, times(1)).tick();
        verify(alsoLeaves, times(1)).tick();
        verify(stays, times(3)).tick();
    }

    @Test
    void everyWayACarryEndsTakesThePieceOutOfTheSet() {
        Furniture table = place(TABLE, 8, 4);
        Furniture placedDown = place(CRATE, 4, 4);
        Furniture attached = place(CRATE, 5, 5);
        Furniture stopped = place(CRATE, 6, 6);
        Furniture broken = place(CRATE, 7, 7);
        Furniture dropped = place(CRATE, 3, 3);

        placedDown.carry(player);
        assertCarriedMatchesScan();
        assertTrue(FurniturePlacementHandler.placeCarriedFurniture(
                player, ground(20, 4), BlockFace.UP, placedDown, manager.getPlacedFurniture()));
        assertEquals(Set.of(), carried());
        assertCarriedMatchesScan();

        attached.carry(player);
        assertTrue(FurnitureAttachmentHandler.attachFromCarried(table, "surface", attached, player));
        assertEquals(Set.of(), carried());
        assertCarriedMatchesScan();

        // Taking it back off the table puts it in the map again, not carried.
        Furniture taken = FurnitureAttachmentHandler.detach(table, "surface", player);
        assertTrue(manager.getPlacedFurniture().containsValue(taken));
        assertEquals(Set.of(), carried());

        stopped.carry(player);
        stopped.stopCarrying();
        assertEquals(Set.of(), carried());

        broken.carry(player);
        assertTrue(FurnitureBreakHandler.removeFurniture(
                broken.getEntityId(), manager.getPlacedFurniture(), player, "test"));
        assertEquals(Set.of(), carried());

        dropped.carry(player);
        player.disconnect();
        assertEquals(Set.of(), carried());
        assertCarriedMatchesScan();
    }

    @Test
    void aCarriedPieceFollowsTheMapInAndOut() {
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);

        manager.getPlacedFurniture().remove(crate.getEntityId());
        assertEquals(Set.of(), carried());
        // A piece put in already carried is ticked from then on.
        manager.getPlacedFurniture().put(crate.getEntityId(), crate);
        assertEquals(Set.of(crate), carried());

        manager.getPlacedFurniture().clear();
        assertEquals(Set.of(), carried());
        // A carry that ends while the piece is out of the map changes nothing there.
        crate.stopCarrying();
        crate.carry(player);
        assertEquals(Set.of(), carried());
    }

    @Test
    void unloadingAndLoadingChunksKeepsOnlyTheCarryGoing() {
        Furniture stays = place(CRATE, 8, 8);
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        standAt(20, 6);

        unloadChunk(0, 0);
        assertFalse(manager.getPlacedFurniture().containsValue(stays));
        assertEquals(Set.of(crate), carried(), "the carry goes on while its old chunk is unloaded");

        loadChunk(0, 0);
        assertEquals(Set.of(crate), carried());
        assertCarriedMatchesScan();
        assertEquals(2, manager.getPlacedFurniture().size());
    }

    @Test
    void disablingThePluginEndsEveryCarry() {
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        PlacedFurnitureMap map = placed();

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(Set.of(), map.carried());
        assertEquals(List.of(), List.copyOf(map.values()).stream().filter(Furniture::isCarried).toList());
    }
}

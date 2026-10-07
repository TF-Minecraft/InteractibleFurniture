package net.tfminecraft.interactiblefurniture.furniture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CarryMotionCoverageTest extends DisplayTestRig {
    private final AtomicReference<Location> position = new AtomicReference<>();
    private Player carrier() {
        Player p = mock(Player.class);
        position.set(new Location(world, 1, 64, 0));
        when(p.getLocation()).thenAnswer(c -> position.get().clone());
        when(p.getWorld()).thenAnswer(c -> position.get().getWorld());
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    @Test void carryTracksMotionAndLeavesStationaryDisplayUntouched() throws Exception {
        type("crate", "item: v.paper\nslots:\n  top:\n    top:\n      whitelist: ['*']\n");
        Furniture f = furniture("crate");
        ItemDisplay display = (ItemDisplay) entities.get(f.getEntityId());
        PlacedSlot contents = f.getOrCreatePlacedSlot("top");
        contents.forceModel(new ItemStack(Material.STONE));
        f.addActiveSlot(contents);
        assertTrue(f.hasActiveSlot("top"));
        Player carrier = carrier();
        f.carry(carrier);
        assertSame(carrier, f.getHolder());
        assertTrue(f.isPersistedCarried());
        assertNotNull(f.getCarriedRecordChunk());
        clearInvocations(display);
        f.tick();
        verify(display, never()).setTransformation(any());
        f.tick();
        verify(display).setInterpolationDuration(2);
        verify(display).setInterpolationDelay(0);
        assertEquals(new Vector3f(1, 1.5f, .7f), display.getTransformation().getTranslation());
        clearInvocations(display);
        f.tick();
        verify(display, never()).setTransformation(any());
        position.set(new Location(world, 2, 64, 0));
        f.tick();
        assertEquals(new Vector3f(2, 1.5f, 2.7f), display.getTransformation().getTranslation());
        assertTrue(f.hasActiveSlot("top"));
        f.stopCarrying();
        f.stopCarrying(); // Idempotent stop after placement or a repeated disconnect callback.
        assertNull(f.getHolder());
        assertFalse(f.isPersistedCarried());
        clearInvocations(display);
        f.tick();
        verify(display, never()).setTransformation(any());
    }

    @Test void rotationAloneAndVerticalMovementUpdateCarryTransform() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate");
        ItemDisplay display = (ItemDisplay) entities.get(f.getEntityId());
        Player p = carrier();
        f.carry(p); f.tick(); f.tick();
        // Same target translation, stale rotation: a render state restored independently.
        Transformation t = display.getTransformation();
        display.setTransformation(new Transformation(t.getTranslation(), new Quaternionf(), t.getScale(), t.getRightRotation()));
        clearInvocations(display);
        f.tick();
        verify(display).setTransformation(any());
        assertTrue(display.getTransformation().getLeftRotation().equals(new Quaternionf().rotationY((float)Math.PI), .00001f));
        position.set(new Location(world, 1, 65, 0));
        f.tick();
        assertEquals(2.5f, display.getTransformation().getTranslation().y);
    }

    @Test void offlineCarrierDropsFurnitureAtItsLastPosition() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate"); Player p = carrier(); f.carry(p);
        when(p.isOnline()).thenReturn(false);
        f.tick();
        assertFalse(placed.containsKey(f.getEntityId()));
        assertFalse(entities.containsKey(f.getEntityId()));
        verify(world).dropItemNaturally(eq(position.get()), any(ItemStack.class));
        verify(manager).discardCarriedRecord(f);
    }

    @Test void unloadedDisplayEndsCarryAndPreservesRecoveryAsAnItem() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate"); f.carry(carrier());
        entities.remove(f.getEntityId()); f.tick();
        assertFalse(placed.containsKey(f.getEntityId()));
        verify(world).dropItemNaturally(eq(position.get()), any(ItemStack.class));
    }

    @Test void worldChangeDropsAtDestinationWithoutCrossWorldDistanceFailure() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate"); f.carry(carrier());
        World destination = mock(World.class);
        position.set(new Location(destination, 10, 70, 20)); f.tick();
        assertFalse(placed.containsKey(f.getEntityId()));
        verify(destination).dropItemNaturally(eq(position.get()), any(ItemStack.class));
    }

    @Test void carryBeyondTrackingRadiusDropsInsteadOfStrandingDisplay() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate"); f.carry(carrier()); f.tick();
        position.set(new Location(world, 70, 64, 0)); f.tick();
        assertFalse(placed.containsKey(f.getEntityId()));
        verify(world).dropItemNaturally(eq(position.get()), any(ItemStack.class));
    }

    @Test void newCarryInAnotherChunkDiscardsOldRecoveryRecord() throws Exception {
        type("crate", "item: v.paper");
        Furniture f = furniture("crate"); Player p = carrier(); f.carry(p); f.stopCarrying();
        f.setLoc(new Location(world, 32, 64, 0)); f.carry(p);
        verify(manager).discardCarriedRecord(f);
        assertEquals(2, f.getCarriedRecordChunk().x());
        f.clearCarriedRecordChunk(); assertNull(f.getCarriedRecordChunk());
    }

    @Test void solidAttachedAndNestedFurnitureCannotBeCarried() throws Exception {
        type("crate", "item: v.paper"); Player p = carrier();
        Furniture solid = furniture("crate"); solid.addBarrierBlock(mock(org.bukkit.block.Block.class)); solid.carry(p);
        assertFalse(solid.isCarried());
        Furniture attached = furniture("crate"); attached.setAttachment(UUID.randomUUID(), "top"); attached.carry(p);
        assertFalse(attached.isCarried());
        Furniture parent = furniture("crate");
        assertSame(parent.getOrCreatePlacedFurnitureSlot("top"), parent.getOrCreatePlacedFurnitureSlot("top"));
        parent.carry(p); assertFalse(parent.isCarried());
        verify(manager, never()).pulse(any());
    }

    @Test void originMatchingSupportsLegacyUnspecifiedFaces() throws Exception {
        type("crate", "item: v.paper"); Furniture f = furniture("crate");
        var block = server.addSimpleWorld("origins").getBlockAt(5, 60, 6);
        f.setOriginBlock(block.getLocation(), null);
        assertTrue(f.matchesOrigin(block, BlockFace.DOWN));
        f.setOriginBlock(block.getLocation(), BlockFace.UP);
        assertFalse(f.matchesOrigin(block, null));
        assertFalse(f.matchesOrigin(block, BlockFace.DOWN));
        assertTrue(f.matchesOrigin(block, BlockFace.UP));
        assertFalse(f.matchesOrigin(block.getRelative(BlockFace.NORTH), BlockFace.UP));
        assertNotNull(f.getCurrentModelData());
        Furniture unknown = furniture("removed-definition"); assertNull(unknown.getCurrentModelData());
    }
}

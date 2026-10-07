package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.events.FurnitureBreakEvent;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.MockBukkit;

class BreakCoverageTest extends DisplayTestRig {
    @BeforeEach void definitions() throws Exception {
        type("crate", """
                item: v.paper
                sound:
                  break: test.break
                slots:
                  top:
                    saved: {whitelist: ['*'], drop-on-break: true}
                    hidden: {whitelist: ['*'], drop-on-break: false}
                    child: {slot-type: furniture, whitelist: ['*'], drop-on-break: true}
                    decoration: {slot-type: furniture, whitelist: ['*'], drop-on-break: false}
                """);
    }
    private void listen(java.util.function.Consumer<FurnitureBreakEvent> listener) {
        server.getPluginManager().registerEvent(FurnitureBreakEvent.class, new Listener(){}, EventPriority.NORMAL,
                (ignored,event) -> listener.accept((FurnitureBreakEvent)event), MockBukkit.createMockPlugin());
    }
    private void attach(Furniture parent, String slot, Furniture child) {
        parent.getOrCreatePlacedFurnitureSlot(slot).setNested(child);
        child.setAttachment(parent.getEntityId(),slot); placed.remove(child.getEntityId());
    }
    private List<Material> dropped() {
        var capture = ArgumentCaptor.forClass(ItemStack.class);
        verify(world,atLeastOnce()).dropItemNaturally(any(),capture.capture());
        return capture.getAllValues().stream().map(ItemStack::getType).toList();
    }
    @Test void cancellationLeavesTheWholePieceAndItsContentsUntouched() {
        Furniture f = furniture("crate"); f.getOrCreatePlacedSlot("saved").forceModel(new ItemStack(Material.DIAMOND));
        AtomicBoolean cancel = new AtomicBoolean(true); listen(e -> e.setCancelled(cancel.get()));
        assertFalse(FurnitureBreakHandler.removeFurniture(f.getEntityId(),placed,player,"break"));
        assertSame(f,placed.get(f.getEntityId())); assertEquals(2,entities.size());
        verify(manager,never()).persistChunk(any()); verify(world,never()).dropItemNaturally(any(),any());
        cancel.set(false); assertTrue(FurnitureBreakHandler.removeFurniture(f.getEntityId(),placed,player,"break"));
        assertTrue(entities.isEmpty()); assertTrue(placed.isEmpty());
        assertEquals(List.of(Material.DIAMOND,Material.PAPER),dropped());
        verify(world).playSound(any(Location.class),eq("test.break"),eq(1f),eq(1f));
    }
    @Test void dropPoliciesRemoveNestedDisplaysWithoutDuplicatingContents() {
        Furniture root=furniture("crate"), kept=furniture("crate"), decoration=furniture("crate");
        root.getOrCreatePlacedSlot("saved").forceModel(new ItemStack(Material.DIAMOND));
        root.getOrCreatePlacedSlot("hidden").forceModel(new ItemStack(Material.STONE));
        kept.getOrCreatePlacedSlot("saved").forceModel(new ItemStack(Material.GOLD_INGOT));
        attach(root,"child",kept); attach(root,"decoration",decoration);
        root.getOrCreatePlacedFurnitureSlot("empty");
        assertTrue(FurnitureBreakHandler.removeFurniture(root.getEntityId(),placed,player,null));
        List<Material> drops=dropped();
        assertEquals(4,drops.size()); assertEquals(2,Collections.frequency(drops,Material.PAPER));
        assertTrue(drops.containsAll(List.of(Material.DIAMOND,Material.GOLD_INGOT))); assertFalse(drops.contains(Material.STONE));
        assertTrue(entities.isEmpty()); assertTrue(root.getActiveFurnitureSlots().isEmpty());
        assertFalse(kept.isAttached()); assertFalse(decoration.isAttached());
    }
    @Test void suppressingSlotDropsStillRemovesAllDescendantDisplays() {
        Furniture root=furniture("crate"), child=furniture("crate"); attach(root,"child",child);
        child.getOrCreatePlacedSlot("saved").forceModel(new ItemStack(Material.DIAMOND));
        assertTrue(FurnitureBreakHandler.removeFurniture(root.getEntityId(),placed,player,"plugin",false));
        assertTrue(entities.isEmpty()); verify(world,never()).dropItemNaturally(any(),any());
    }
    @Test void breakingOneNestedPiecePersistsParentAndRespectsCancellation() {
        Furniture root=furniture("crate"), child=furniture("crate"); attach(root,"child",child);
        AtomicBoolean cancel=new AtomicBoolean(true); listen(e -> e.setCancelled(cancel.get()));
        assertFalse(FurnitureBreakHandler.breakNestedFurniture(child,placed,player,"break"));
        assertTrue(child.isAttached()); assertTrue(root.hasActiveFurnitureSlot("child"));
        cancel.set(false); assertTrue(FurnitureBreakHandler.breakNestedFurniture(child,placed,player,"break"));
        assertFalse(child.isAttached()); assertFalse(root.hasActiveFurnitureSlot("child"));
        assertFalse(entities.containsKey(child.getEntityId())); assertTrue(entities.containsKey(root.getEntityId()));
        verify(manager).persistFurniture(root); assertEquals(List.of(Material.PAPER),dropped());
    }
    @Test void invalidNestedReferencesDoNotDestroyAnything() {
        Furniture f=furniture("crate");
        assertFalse(FurnitureBreakHandler.breakNestedFurniture(null,placed,player,"break"));
        assertFalse(FurnitureBreakHandler.breakNestedFurniture(f,placed,player,"break"));
        f.setAttachment(UUID.randomUUID(),null);
        assertFalse(FurnitureBreakHandler.breakNestedFurniture(f,placed,player,"break"));
        f.setAttachment(UUID.randomUUID(),"child");
        assertFalse(FurnitureBreakHandler.breakNestedFurniture(f,placed,player,"break"));
        assertFalse(FurnitureBreakHandler.removeFurniture(UUID.randomUUID(),placed,player,"break"));
        assertEquals(1,entities.size());
    }
    @Test void connectedPiecesBreakTopDownAndLeaveCarriedPiecesAlone() {
        var blocks=server.addSimpleWorld("barriers"); Block barrier=blocks.getBlockAt(0,64,0); barrier.setType(Material.BARRIER);
        Furniture root=furniture("crate"), upper=furniture("crate"), carried=furniture("crate"), attached=furniture("crate");
        root.addBarrierBlock(barrier); upper.setOriginBlock(barrier.getLocation(),BlockFace.UP); upper.setLoc(new Location(world,0,65,0));
        carried.carry(player); carried.setOriginBlock(barrier.getLocation(),BlockFace.UP);
        attached.setAttachment(root.getEntityId(),"decor"); attached.setOriginBlock(barrier.getLocation(),BlockFace.UP);
        List<Furniture> events=new ArrayList<>(); listen(e -> events.add(e.getFurniture()));
        assertEquals(Set.of(root.getEntityId(),upper.getEntityId(),carried.getEntityId(),attached.getEntityId()),
                FurnitureBreakHandler.findConnectedFurniture(barrier,placed));
        assertTrue(FurnitureBreakHandler.removeFurniture(root.getEntityId(),placed,player,"break"));
        assertEquals(List.of(upper,root),events); assertEquals(Material.AIR,barrier.getType());
        assertTrue(placed.containsKey(carried.getEntityId())); assertTrue(placed.containsKey(attached.getEntityId()));
    }
    @Test void sharedBarrierRemainsWhenAnotherPiecesBreakIsCancelled() {
        Block shared=server.addSimpleWorld("shared").getBlockAt(0,64,0); shared.setType(Material.BARRIER);
        Furniture a=furniture("crate"),b=furniture("crate"); a.addBarrierBlock(shared); b.addBarrierBlock(shared);
        listen(e -> {if(e.getFurniture()==b)e.setCancelled(true);});
        assertFalse(FurnitureBreakHandler.removeFurniture(a.getEntityId(),placed,player,"break"));
        assertFalse(placed.containsKey(a.getEntityId())); assertTrue(placed.containsKey(b.getEntityId()));
        assertEquals(Material.BARRIER,shared.getType()); assertTrue(a.getBarrierBlocks().isEmpty());
    }
    @Test void pickupReturnsRootToHandWhilePluginRemovalDoesNotDropIt() {
        Furniture picked=furniture("crate");
        assertTrue(FurnitureBreakHandler.removeFurniture(picked.getEntityId(),placed,player,"picked-up"));
        assertEquals(Material.PAPER,player.getInventory().getItemInMainHand().getType());
        Furniture pluginRemoved=furniture("crate");
        assertTrue(FurnitureBreakHandler.removeFurniture(pluginRemoved.getEntityId(),placed,player,"plugin"));
        verify(world,never()).dropItemNaturally(any(),any());
    }
    @Test void missingDefinitionsAndItemsStillCleanUpEntities() {
        Furniture missing=furniture("gone");
        missing.getOrCreatePlacedSlot("removed-slot").setModel(new ItemStack(Material.EMERALD));
        assertTrue(FurnitureBreakHandler.removeFurniture(missing.getEntityId(),placed,null,"break"));
        assertEquals(List.of(Material.EMERALD),dropped());
        when(TLibs.getItemAPI().getCreator().getItemFromPath("v.paper")).thenReturn(null);
        Furniture missingItem=furniture("crate");
        assertTrue(FurnitureBreakHandler.removeFurniture(missingItem.getEntityId(),placed,null,"break"));
        assertTrue(entities.isEmpty());
    }
    @Test void staleBarrierAndAlreadyRemovedDisplayDoNotBlockCleanup() {
        Furniture f=furniture("crate"); Block changed=server.addSimpleWorld("changed").getBlockAt(0,64,0); changed.setType(Material.STONE);
        f.addBarrierBlock(changed); entities.remove(f.getEntityId()); f.getOrCreatePlacedSlot("saved");
        assertTrue(FurnitureBreakHandler.removeFurniture(f.getEntityId(),placed,null,"break"));
        assertEquals(Material.STONE,changed.getType()); assertTrue(f.getBarrierBlocks().isEmpty());
    }
    @Test void malformedNestedIdentityAndCyclesCannotDuplicateDropsOrRecurseForever() {
        Furniture root=furniture("crate");
        root.getOrCreatePlacedFurnitureSlot("child").setNested(root);
        root.getOrCreatePlacedFurnitureSlot("decoration").setNested(new Furniture("crate",root.getLoc(),null));
        var legacyApi=new FurnitureBreakHandler();
        assertTrue(legacyApi.removeFurniture(root.getEntityId(),placed,null,"break"));
        assertEquals(List.of(Material.PAPER),dropped()); assertTrue(entities.isEmpty());
    }
    @Test void eventListenersCanRemoveAnotherConnectedPieceWithoutADuplicateBreak() {
        Block barrier=server.addSimpleWorld("event-removal").getBlockAt(0,64,0); barrier.setType(Material.BARRIER);
        Furniture upper=furniture("crate"),lower=furniture("crate"); upper.addBarrierBlock(barrier); lower.addBarrierBlock(barrier);
        upper.setLoc(new Location(world,0,65,0));
        List<Furniture> broken=new ArrayList<>();
        listen(event->{broken.add(event.getFurniture()); if(event.getFurniture()==upper){placed.remove(lower.getEntityId());entities.remove(lower.getEntityId());}});
        assertTrue(FurnitureBreakHandler.removeFurniture(upper.getEntityId(),placed,null,"break"));
        assertEquals(List.of(upper),broken); assertTrue(placed.isEmpty());
    }

}

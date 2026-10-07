package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.events.*;
import net.tfminecraft.interactiblefurniture.furniture.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class AttachmentContractTest extends DisplayTestRig {
    @BeforeEach void definitions() throws Exception {
        type("parent","""
                item: v.paper
                slots:
                  top:
                    child: {slot-type: furniture, whitelist: [v.paper]}
                    item: {whitelist: ['*']}
                    unused: {whitelist: ['*']}
                """);
        type("wrong-item", "item: v.stone");
    }
    private <T extends Event> void listen(Class<T> cls, java.util.function.Consumer<T> action) {
        server.getPluginManager().registerEvent(cls,new Listener(){},EventPriority.NORMAL,
                (ignored,event)->action.accept(cls.cast(event)),MockBukkit.createMockPlugin());
    }
    @Test void attachRejectsInvalidArgumentsDefinitionsAndOccupiedSlots() {
        Furniture parent=furniture("parent"), child=furniture("parent");
        assertFalse(FurnitureAttachmentHandler.attach(null,"child",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,null,child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",null,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",child,null));
        assertFalse(FurnitureAttachmentHandler.attach(furniture("missing"),"child",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"missing",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"item",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",furniture("wrong-item"),player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",furniture("missing"),player));
        assertTrue(FurnitureAttachmentHandler.attach(parent,"child",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",furniture("parent"),player));
        assertFalse(FurnitureAttachmentHandler.attach(furniture("parent"),"child",child,player));
        assertFalse(FurnitureAttachmentHandler.attachFromCarried(parent,"child",null,player));
    }
    @Test void cancelledAttachRetainsOriginalOwnershipAndCancelledDetachRetainsParent() {
        Furniture parent=furniture("parent"), child=furniture("parent");
        AtomicBoolean cancel=new AtomicBoolean(true);
        listen(FurnitureSlotFurnitureAddEvent.class,e->e.setCancelled(cancel.get()));
        assertFalse(FurnitureAttachmentHandler.attach(parent,"child",child,player));
        assertSame(child,placed.get(child.getEntityId())); assertFalse(child.isAttached());
        cancel.set(false); assertTrue(FurnitureAttachmentHandler.attach(parent,"child",child,player));
        listen(FurnitureSlotFurnitureTakeEvent.class,e->e.setCancelled(true));
        assertNull(FurnitureAttachmentHandler.detach(parent,"child",player));
        assertSame(child,parent.getActiveFurnitureSlot("child").orElseThrow().getNested());
    }
    @Test void failedAttachResumesCarryOrDropsWhenHolderHasDisconnected() {
        Furniture parent=furniture("parent"), child=furniture("parent"); child.carry(player);
        assertFalse(FurnitureAttachmentHandler.attachFromCarried(parent,"missing",child,player));
        assertSame(player,child.getHolder());
        player.disconnect();
        assertFalse(FurnitureAttachmentHandler.attachFromCarried(parent,"missing",child,player));
        assertFalse(placed.containsKey(child.getEntityId()));
    }
    @Test void carriedAttachConsumesTheRecoveryRecordAndTransfersNestedOwnership() {
        Furniture parent=furniture("parent"), child=furniture("parent"); child.carry(player);
        assertTrue(FurnitureAttachmentHandler.attachFromCarried(parent,"child",child,player));
        assertFalse(child.isCarried()); assertTrue(child.isAttached()); verify(manager).discardCarriedRecord(child);
        var slot=parent.getActiveFurnitureSlot("child").orElseThrow(); assertSame(parent,slot.getParent());
        assertFalse(slot.getDefinition().isEmpty()); slot.setParent(null); assertTrue(slot.getDefinition().isEmpty());
        slot.setParent(furniture("missing")); assertTrue(slot.getDefinition().isEmpty()); slot.setParent(parent);
    }
    @Test void detachValidatesIncompleteAndStaleSlotsWithoutPublishingAnOrphan() {
        Furniture parent=furniture("parent");
        assertNull(FurnitureAttachmentHandler.detach(null,"child",player));
        assertNull(FurnitureAttachmentHandler.detach(parent,null,player));
        assertNull(FurnitureAttachmentHandler.detach(parent,"child",null));
        assertNull(FurnitureAttachmentHandler.detach(parent,"child",player));
        parent.getOrCreatePlacedFurnitureSlot("child");
        assertNull(FurnitureAttachmentHandler.detach(parent,"child",player));
        parent.getOrCreatePlacedFurnitureSlot("stale").setNested(furniture("parent"));
        assertNull(FurnitureAttachmentHandler.detach(parent,"stale",player));
    }
    @Test void detachCopiesOriginAndSynchronizesContentsWithTheParent() {
        Furniture parent=furniture("parent"), child=furniture("parent");
        parent.setOriginBlock(new Location(world,1,63,2),BlockFace.DOWN);
        child.getOrCreatePlacedSlot("item").forceModel(new ItemStack(Material.DIAMOND));
        child.getOrCreatePlacedSlot("stale");
        child.getOrCreatePlacedSlot("unused");
        FurnitureNestedDisplay.syncItemSlots(child);
        assertTrue(FurnitureAttachmentHandler.attach(parent,"child",child,player));
        assertSame(child,FurnitureAttachmentHandler.detach(parent,"child",player));
        assertEquals(parent.getOriginBlockLocation(),child.getOriginBlockLocation());
        assertEquals(BlockFace.DOWN,child.getOriginBlockFace().orElseThrow());
        assertEquals(Material.DIAMOND,child.getActiveSlot("item").orElseThrow().getCurrentItem().getType());
    }
    @Test void displaySynchronizationToleratesPublicApiNullsAndUnloadedDisplays() {
        Furniture parent=furniture("parent"),child=furniture("parent"),missing=furniture("gone");
        FurnitureNestedDisplay.syncNestedRoot(null,"child",child);
        FurnitureNestedDisplay.syncNestedRoot(missing,"child",child);
        FurnitureNestedDisplay.syncNestedRoot(parent,"missing",child);
        FurnitureNestedDisplay.onParentTransformChanged(null);
        FurnitureNestedDisplay.syncItemSlots(null); FurnitureNestedDisplay.prepareAttached(null);
        FurnitureNestedDisplay.prepareDetached(null,parent,"child");
        FurnitureNestedDisplay.prepareDetached(child,missing,"child");
        FurnitureNestedDisplay.prepareDetached(child,parent,"missing");
        parent.getOrCreatePlacedFurnitureSlot("empty"); FurnitureNestedDisplay.onParentTransformChanged(parent);
        entities.remove(child.getEntityId());
        FurnitureNestedDisplay.syncNestedRoot(parent,"child",child);
        FurnitureNestedDisplay.syncItemSlots(child); FurnitureNestedDisplay.prepareDetached(child,parent,"child");
        assertEquals(new Location(world,0,64,0),child.getLoc());
    }
}

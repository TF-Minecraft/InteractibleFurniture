package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.events.*;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.furniture.data.DisplayData;
import net.tfminecraft.interactiblefurniture.loaders.SoundLoader;

class SlotInteractionCoverageTest extends DisplayTestRig {
    private Furniture furniture;
    private SlotDefinition slot;
    private ItemDisplay parent;
    @BeforeEach
    void configure() throws Exception {
        type("crate", """
                item: v.barrel
                slots:
                  top:
                    top:
                      whitelist: ['*']
                      interactible: true
                """);
        furniture = furniture("crate"); slot = furniture.getType().getSlot("top");
        parent = (ItemDisplay)entities.get(furniture.getEntityId());
    }
    private boolean right() {
        return SlotInteractionHandler.handleSlotInteraction(player, furniture, parent, slot, Action.RIGHT_CLICK_BLOCK);
    }
    private <T extends Event> void listen(Class<T> cls, java.util.function.Consumer<T> action) {
        server.getPluginManager().registerEvent(cls, new Listener(){}, EventPriority.NORMAL,
                (ignored, event) -> action.accept(cls.cast(event)), MockBukkit.createMockPlugin());
    }

    @Test
    void placementConsumesOneAndPublishesListenerReplacementAndDisplaySettings() {
        ItemStack held = new ItemStack(Material.STONE, 4); player.getInventory().setItemInMainHand(held);
        DisplayData data = new DisplayData(); data.setyPos(2);
        listen(FurnitureSlotItemAddEvent.class, e -> {e.setItem(new ItemStack(Material.DIAMOND, 2)); e.setDisplayData(data);});
        SoundLoader.getMap().put("v.stone", "test.custom");
        assertTrue(right());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        PlacedSlot active = furniture.getActiveSlot("top").orElseThrow();
        assertEquals(Material.DIAMOND, active.getCurrentItem().getType()); assertEquals(1, active.getCurrentItem().getAmount());
        assertSame(data, active.getCurrentDisplayData());
        ItemDisplay display = (ItemDisplay)entities.get(active.getDisplayStandId());
        assertEquals(66, display.getLocation().getY());
        verify(manager).persistFurniture(furniture);
        verify(world).playSound(any(Location.class), eq("test.custom"), eq(1f), eq(1f));
        assertFalse(right(), "occupied slot refuses a second item");
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void cancelledPlacementPreservesTheHeldStackAndLeavesNoDisplay() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 4));
        listen(FurnitureSlotItemAddEvent.class, e -> e.setCancelled(true));
        assertFalse(right()); assertEquals(4, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(furniture.getActiveSlots().isEmpty()); assertEquals(1, entities.size());
        verify(manager, never()).persistFurniture(any());
    }

    @Test
    void unusablePlacementTargetDoesNotConsumeTheHeldItem() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 4));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player, furniture, null, slot, Action.RIGHT_CLICK_BLOCK));
        assertEquals(4, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(furniture.getActiveSlots().isEmpty());
    }

    @Test
    void cancelledTakeRetainsContentsAndSuccessfulTakeMergesThenReturnsRemainder() {
        var active = furniture.getOrCreatePlacedSlot("top"); active.setModel(new ItemStack(Material.STONE, 8));
        player.getInventory().setItem(1, new ItemStack(Material.STONE, 62));
        player.getInventory().setItem(2, new ItemStack(Material.DIRT, 1));
        player.getInventory().setItem(3, new ItemStack(Material.STONE, 64));
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(true);
        listen(FurnitureSlotItemTakeEvent.class, e -> e.setCancelled(cancel.get()));
        assertFalse(right()); assertSame(active, furniture.getActiveSlot("top").orElseThrow());
        assertEquals(62, player.getInventory().getItem(1).getAmount());
        cancel.set(false); assertTrue(right());
        assertEquals(64, player.getInventory().getItem(1).getAmount());
        assertEquals(6, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(furniture.getActiveSlots().isEmpty()); verify(manager).persistFurniture(furniture);
    }

    @Test
    void takeUsesFallbackDisplayAndLeavesHandEmptyWhenAllItemsMerge() {
        ItemDisplay stand = display(furniture.getLoc()); stand.setItemStack(new ItemStack(Material.STONE, 2));
        furniture.getOrCreatePlacedSlot("top").setDisplayStandId(stand.getUniqueId());
        player.getInventory().setItem(2, new ItemStack(Material.STONE, 60));
        SoundLoader.getMap().put("v.stone", "test.take");
        assertTrue(right()); assertEquals(62, player.getInventory().getItem(2).getAmount());
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        assertFalse(entities.containsKey(stand.getUniqueId()));
        verify(world).playSound(any(Location.class), eq("test.take"), eq(1f), eq(1f));
    }

    @Test
    void emptySlotAndLostDisplayDoNotYieldAnItem() {
        assertFalse(right());
        furniture.getOrCreatePlacedSlot("top").setDisplayStandId(java.util.UUID.randomUUID());
        assertFalse(right());
        ItemDisplay empty = display(furniture.getLoc());
        furniture.getOrCreatePlacedSlot("top").setDisplayStandId(empty.getUniqueId());
        assertFalse(right()); verify(manager, never()).persistFurniture(any());
    }

    @Test
    void leftClickDropsAcceptedEventReplacementAtClickedBlock() {
        var active = furniture.getOrCreatePlacedSlot("top"); active.setModel(new ItemStack(Material.STONE, 2));
        listen(FurnitureSlotItemTakeEvent.class, e -> e.setItem(new ItemStack(Material.DIAMOND)));
        Block block = mock(Block.class); when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        assertTrue(SlotInteractionHandler.handleSlotInteraction(player, block, Action.LEFT_CLICK_BLOCK,
                furniture, parent, BlockFace.UP));
        verify(world).dropItemNaturally(eq(block.getLocation()), argThat(i -> i.getType() == Material.DIAMOND));
        assertTrue(furniture.getActiveSlots().isEmpty());
    }

    @Test
    void hitSearchHonorsWhitelistAndNestedNearestSlots() throws Exception {
        type("child", """
                slots:
                  top:
                    child:
                      whitelist: [STONE]
                      interactible: true
                      offset: {x: 2}
                    decorative:
                      interactible: false
                """);
        Furniture child = furniture("child");
        furniture.getOrCreatePlacedFurnitureSlot("nested").setNested(child);
        furniture.getOrCreatePlacedFurnitureSlot("empty");
        Vector click = new Vector(2,64,0);
        var hit = SlotInteractionHandler.findBestSlotHit(click, furniture, parent, new ItemStack(Material.STONE));
        assertSame(child, hit.furniture()); assertEquals("child", hit.slot().getId()); assertEquals(0,hit.distance());
        assertSame(hit.slot(), SlotInteractionHandler.findClosestSlotForHit(click, furniture, parent));
        assertSame(hit.slot(), SlotInteractionHandler.findBestSlotHit(click,furniture,parent).slot());
        assertSame(slot, SlotInteractionHandler.findClosestSlotForHit(click,furniture,parent,new ItemStack(Material.DIRT)));
        entities.remove(child.getEntityId());
        assertSame(slot, SlotInteractionHandler.findClosestSlotForHit(click, furniture, parent));
        assertNull(SlotInteractionHandler.findBestSlotHit(null,furniture,parent));
        assertNull(SlotInteractionHandler.findBestSlotHit(click,null,parent));
        assertNull(SlotInteractionHandler.findBestSlotHit(click,furniture,null));
    }

    @Test
    void unsupportedActionsAndUnknownTypesDoNotMutate() {
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,furniture,parent,slot,Action.PHYSICAL));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,furniture,parent,slot,Action.LEFT_CLICK_BLOCK));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,null,Action.RIGHT_CLICK_BLOCK,
                furniture("missing"),parent,BlockFace.UP));
        assertNull(SlotInteractionHandler.findClosestSlotForHit(new Vector(), furniture("missing"),parent));
    }
    @Test
    void furnitureSlotTakesAreCancellableAndNeverReplaceAnExistingCarry() throws Exception {
        type("table","item: v.paper\nslots:\n  top:\n    child: {slot-type: furniture, whitelist: ['*'], interactible: true}\n");
        Furniture table=furniture("table"), child=furniture("crate");
        ItemDisplay display=(ItemDisplay)entities.get(table.getEntityId()); SlotDefinition target=table.getType().getSlot("child");
        var api=new SlotInteractionHandler();
        assertFalse(api.handleSlotInteraction(player,table,display,target,Action.RIGHT_CLICK_BLOCK));
        assertTrue(FurnitureAttachmentHandler.attach(table,"child",child,player));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertFalse(api.handleSlotInteraction(player,table,display,target,Action.RIGHT_CLICK_BLOCK));
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean(true);
        listen(FurnitureSlotFurnitureTakeEvent.class,e->e.setCancelled(cancel.get()));
        assertFalse(api.handleSlotInteraction(player,table,display,target,Action.RIGHT_CLICK_BLOCK));
        cancel.set(false);
        assertTrue(api.handleSlotInteraction(player,table,display,target,Action.RIGHT_CLICK_BLOCK));
        assertTrue(child.isCarried()); assertFalse(child.isAttached()); assertFalse(table.hasActiveFurnitureSlot("child"));
    }
    @Test
    void attachmentSearchRejectsUnknownTypesMissingDisplaysAndDisallowedOrOccupiedSlots() throws Exception {
        assertFalse(SlotInteractionHandler.tryAttachCarried(null,furniture,furniture,new Vector()));
        Furniture noDisplay=furniture("crate"); entities.remove(noDisplay.getEntityId());
        assertFalse(SlotInteractionHandler.tryAttachCarried(player,noDisplay,furniture,new Vector()));
        assertFalse(SlotInteractionHandler.tryAttachCarried(player,furniture("missing"),furniture,new Vector()));
        type("table","item: v.paper\nslots:\n  top:\n    child: {slot-type: furniture, whitelist: [v.diamond], interactible: true}\n    decoration: {slot-type: furniture, whitelist: ['*'], interactible: false}\n");
        Furniture table=furniture("table");
        assertFalse(SlotInteractionHandler.tryAttachCarried(player,furniture,furniture,new Vector()),"item-only slots cannot hold furniture");
        assertFalse(SlotInteractionHandler.tryAttachCarried(player,table,furniture,new Vector()));
        table.getOrCreatePlacedFurnitureSlot("child");
        assertFalse(SlotInteractionHandler.tryAttachCarried(player,table,furniture,new Vector()));
    }
    @Test
    void slotlessFurniturePublishesAnInteractionWithoutClaimingTheClick() throws Exception {
        type("decoration","item: v.paper"); Furniture f=furniture("decoration");
        java.util.List<Furniture> events=new java.util.ArrayList<>(); listen(FurnitureInteractEvent.class,e->events.add(e.getFurniture()));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,null,Action.RIGHT_CLICK_BLOCK,f,entities.get(f.getEntityId()),BlockFace.UP));
        assertEquals(java.util.List.of(f),events);
    }
    @Test
    void disallowedPlacementAndCancelledOrEmptyLeftClickPreserveSlotState() throws Exception {
        type("limited","slots:\n  top:\n    slot: {whitelist: [DIAMOND], interactible: true}\n");
        Furniture limited=furniture("limited"); player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,limited,(ItemDisplay)entities.get(limited.getEntityId()),limited.getType().getSlot("slot"),Action.RIGHT_CLICK_BLOCK));
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        Block block=mock(Block.class); when(block.getWorld()).thenReturn(world); when(block.getLocation()).thenReturn(new Location(world,1,64,1));
        var contents=furniture.getOrCreatePlacedSlot("top");
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,block,Action.LEFT_CLICK_BLOCK,furniture,parent,BlockFace.UP));
        contents.setModel(new ItemStack(Material.DIAMOND)); listen(FurnitureSlotItemTakeEvent.class,e->e.setCancelled(true));
        assertFalse(SlotInteractionHandler.handleSlotInteraction(player,block,Action.LEFT_CLICK_BLOCK,furniture,parent,BlockFace.UP));
        assertSame(contents,furniture.getActiveSlot("top").orElseThrow()); verify(world,never()).dropItemNaturally(any(),any());
    }

}

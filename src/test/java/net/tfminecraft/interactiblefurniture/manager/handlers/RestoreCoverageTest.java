package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.utils.Keys;

class RestoreCoverageTest extends DisplayTestRig {
    @BeforeEach
    void definitions() throws Exception {
        type("crate","""
                item: v.barrel
                slots:
                  top:
                    item: {whitelist: ['*']}
                    child: {slot-type: furniture, whitelist: ['*']}
                """);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("restore-test"));
    }
    private Furniture saved() {
        Furniture f=furniture("crate");
        f.setOriginBlock(new Location(world,0,63,0),BlockFace.UP);
        return f;
    }
    @Test
    void existingDisplayInAnotherWorldReturnsToSavedLocation() {
        Furniture f=saved(); ItemDisplay display=(ItemDisplay)entities.get(f.getEntityId());
        World other=mock(World.class); display.teleport(new Location(other,1,64,1));
        assertSame(f,FurnitureRestoreHandler.restore(f));
        assertEquals(f.getLoc(),display.getLocation());
    }
    @Test
    void missingDisplayIsRespawnedAndSlotDataReattached() {
        Furniture f=saved(); UUID old=f.getEntityId(); entities.remove(old);
        f.getOrCreatePlacedSlot("item").setModel(new ItemStack(Material.DIAMOND));
        assertSame(f,FurnitureRestoreHandler.restore(f)); assertNotEquals(old,f.getEntityId());
        ItemDisplay display=(ItemDisplay)entities.get(f.getEntityId());
        assertEquals(f.getEntityId().toString(),display.getPersistentDataContainer().get(Keys.furnitureDisplay(),PersistentDataType.STRING));
        PlacedSlot slot=f.getActiveSlot("item").orElseThrow();
        assertSame(f,slot.getFurniture());
        assertEquals(Material.DIAMOND,((ItemDisplay)entities.get(slot.getDisplayStandId())).getItemStack().getType());
        verify(manager,atLeastOnce()).markDirty(f);
    }
    @Test
    void savedItemsReplaceLiveDisplayContentsAndMissingItemsRecoverFromDisplay() {
        Furniture f=saved(); var slot=f.getOrCreatePlacedSlot("item"); ItemDisplay stand=display(f.getLoc());
        slot.setDisplayStandId(stand.getUniqueId()); stand.setItemStack(new ItemStack(Material.STONE));
        assertSame(f,FurnitureRestoreHandler.restore(f)); assertEquals(Material.STONE,slot.getCurrentItem().getType());
        slot.setModel(new ItemStack(Material.DIAMOND)); FurnitureRestoreHandler.restore(f);
        assertEquals(Material.DIAMOND,stand.getItemStack().getType());
        f.getOrCreatePlacedSlot("empty"); FurnitureRestoreHandler.restore(f);
        assertNull(f.getActiveSlot("empty").orElseThrow().getDisplayStandId());
    }
    @Test
    void staleAndMalformedEntityTagsAreRemovedWithoutTouchingLiveOrUntaggedEntities() {
        Furniture f=saved(); ItemDisplay live=(ItemDisplay)entities.get(f.getEntityId());
        FurniturePlacementHandler.tagDisplay(live,f.getEntityId());
        ItemDisplay orphan=display(f.getLoc()), malformed=display(f.getLoc()), untagged=display(f.getLoc());
        FurniturePlacementHandler.tagDisplay(orphan,UUID.randomUUID());
        malformed.getPersistentDataContainer().set(Keys.furnitureDisplay(),PersistentDataType.STRING,"invalid");
        Interaction known=interaction(f.getEntityId().toString()), stale=interaction(UUID.randomUUID().toString()), bad=interaction("invalid"), bare=interaction(null);
        Chunk chunk=mock(Chunk.class);
        when(chunk.getEntities()).thenReturn(new Entity[]{live,orphan,malformed,untagged,known,stale,bad,bare,mock(Entity.class)});
        FurnitureRestoreHandler.reconcileChunk(chunk,placed);
        assertTrue(entities.containsKey(live.getUniqueId())); assertTrue(entities.containsKey(untagged.getUniqueId()));
        assertFalse(entities.containsKey(orphan.getUniqueId())); assertFalse(entities.containsKey(malformed.getUniqueId()));
        verify(stale).remove(); verify(bad).remove(); verify(known,never()).remove(); verify(bare,never()).remove();
    }
    private Interaction interaction(String tag) {
        Interaction entity=mock(Interaction.class); var data=new PersistentDataContainerMock();
        when(entity.getPersistentDataContainer()).thenReturn(data);
        if(tag!=null)data.set(Keys.furnitureEntity(),PersistentDataType.STRING,tag);
        return entity;
    }
    @Test
    void invalidRecordsAreIgnoredAndLiveNestedIdentityPreventsDuplicateRecoveryDrops() {
        assertNull(FurnitureRestoreHandler.restore(null));
        assertNull(FurnitureRestoreHandler.restore(new Furniture("crate",null,UUID.randomUUID())));
        assertNull(FurnitureRestoreHandler.restore(new Furniture("crate",new Location(null,0,0,0),UUID.randomUUID())));
        assertNull(FurnitureRestoreHandler.restore(furniture("missing")));
        Furniture root=saved(), child=saved(); root.getOrCreatePlacedFurnitureSlot("child").setNested(child);
        root.getOrCreatePlacedFurnitureSlot("empty");
        placed.remove(child.getEntityId());
        Furniture recovery=new Furniture("crate",child.getLoc(),child.getEntityId()); recovery.setPersistedCarried(true);
        assertNull(FurnitureRestoreHandler.restore(recovery,placed));
        assertTrue(entities.containsKey(child.getEntityId())); verify(world,never()).dropItemNaturally(any(),any());
        var ids=new HashSet<UUID>(); FurnitureRestoreHandler.collectAllFurnitureIds(root,ids);
        assertEquals(Set.of(root.getEntityId(),child.getEntityId()),ids);
        FurnitureRestoreHandler.collectAllFurnitureIds(null,ids); FurnitureRestoreHandler.collectAllFurnitureIds(root,null);
        FurnitureRestoreHandler.collectAllFurnitureIds(new Furniture("crate",root.getLoc(),null),ids);
        assertEquals(2,ids.size());
    }
    @Test
    void temporarilyMissingModelRetainsTheSavedPieceForLaterRecovery() {
        Furniture f=saved(); UUID original=f.getEntityId(); entities.remove(original);
        f.getOrCreatePlacedSlot("item").setModel(new ItemStack(Material.DIAMOND));
        when(net.tfminecraft.tlibs.TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenReturn(null);
        assertSame(f,FurnitureRestoreHandler.restore(f),"a transient item-provider failure must not discard the saved record");
        assertEquals(original,f.getEntityId());
        assertEquals(Material.DIAMOND,f.getActiveSlot("item").orElseThrow().getCurrentItem().getType());
        when(net.tfminecraft.tlibs.TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenReturn(new ItemStack(Material.PAPER));
        assertSame(f,FurnitureRestoreHandler.restore(f)); assertNotEquals(original,f.getEntityId());
        assertNotNull(f.getActiveSlot("item").orElseThrow().getDisplayStandId());
    }
    @Test
    void restoresSavedBarriersAndReconstructsLegacyLayerFootprints() throws Exception {
        var blocks=server.addSimpleWorld("restored-barriers");
        Furniture f=saved(); var air=blocks.getBlockAt(0,64,0); var changed=blocks.getBlockAt(1,64,0); changed.setType(Material.STONE);
        f.addBarrierBlock(air); f.addBarrierBlock(changed);
        assertSame(f,FurnitureRestoreHandler.restore(f)); assertEquals(Material.BARRIER,air.getType()); assertEquals(Material.STONE,changed.getType());
        type("layered","item: v.paper\nlayers:\n  1:\n    - 'X'\n");
        Furniture legacy=furniture("layered"); legacy.setOriginBlock(blocks.getBlockAt(3,63,0).getLocation(),BlockFace.UP);
        assertSame(legacy,FurnitureRestoreHandler.restore(legacy)); assertEquals(Material.BARRIER,blocks.getBlockAt(3,64,0).getType());
        assertEquals(1,legacy.getBarrierBlocks().size());
        Furniture missingFace=furniture("layered"); missingFace.setOriginBlock(blocks.getBlockAt(5,63,0).getLocation(),null);
        assertSame(missingFace,FurnitureRestoreHandler.restore(missingFace)); assertTrue(missingFace.getBarrierBlocks().isEmpty());
    }
    @Test
    void nestedRestoreRetainsUnavailableChildrenAndSkipsEmptySlots() {
        Furniture root=saved(),child=saved(); child.setAttachment(root.getEntityId(),"child");
        root.getOrCreatePlacedFurnitureSlot("child").setNested(child); root.getOrCreatePlacedFurnitureSlot("empty");
        entities.remove(child.getEntityId());
        when(net.tfminecraft.tlibs.TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenReturn(null);
        assertSame(root,FurnitureRestoreHandler.restore(root)); assertSame(child,root.getActiveFurnitureSlot("child").orElseThrow().getNested());
        assertFalse(entities.containsKey(child.getEntityId()));
    }
    @Test
    void standaloneStrandedRecoveryDropsContentsWithoutRequiringALiveRegistryMap() {
        Furniture f=furniture("crate"); f.setPersistedCarried(true); f.getOrCreatePlacedSlot("item").setModel(new ItemStack(Material.DIAMOND));
        assertNull(FurnitureRestoreHandler.restore(f,null));
        verify(world,times(2)).dropItemNaturally(any(Location.class),any(ItemStack.class));
        assertFalse(entities.containsKey(f.getEntityId())); assertTrue(f.getActiveSlots().isEmpty());
    }

    @Test
    void solidAndAttachedRecordsAreNotMistakenForStrandedCarries() throws Exception {
        type("solid","item: v.paper\nsolid: true"); Furniture solid=furniture("solid");
        solid.addBarrierBlock(server.addSimpleWorld("solid-restored").getBlockAt(0,64,0));
        assertSame(solid,FurnitureRestoreHandler.restore(solid));
        Furniture attached=furniture("crate"); attached.setAttachment(UUID.randomUUID(),"child");
        assertSame(attached,FurnitureRestoreHandler.restore(attached));
    }

}

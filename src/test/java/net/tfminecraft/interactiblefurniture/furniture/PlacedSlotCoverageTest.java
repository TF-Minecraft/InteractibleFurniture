package net.tfminecraft.interactiblefurniture.furniture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.furniture.data.DisplayData;
import net.tfminecraft.interactiblefurniture.utils.Keys;

class PlacedSlotCoverageTest extends DisplayTestRig {
    private Furniture furniture;
    private PlacedSlot slot;
    private ItemDisplay parent;
    @BeforeEach
    void setupSlot() throws Exception {
        type("crate", """
                display:
                  position: {y: 0.25}
                slots:
                  top:
                    slot:
                      whitelist: ['*']
                      offset: {x: 1, y: 2, z: 3}
                """);
        furniture = furniture("crate"); parent = (ItemDisplay)entities.get(furniture.getEntityId());
        slot = furniture.getOrCreatePlacedSlot("slot");
    }
    @Test
    void forcedModelSpawnsTagsAndTransformsThenCanBeReplacedOrCleared() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        slot.forceModel(item);
        ItemDisplay display = (ItemDisplay)entities.get(slot.getDisplayStandId());
        assertNotNull(display); assertEquals(item,display.getItemStack());
        assertEquals(furniture.getEntityId().toString(),display.getPersistentDataContainer().get(Keys.furnitureDisplay(),PersistentDataType.STRING));
        assertEquals("slot",display.getPersistentDataContainer().get(Keys.furnitureSlot(),PersistentDataType.STRING));
        assertEquals(new Location(world,1,65.75,3),display.getLocation());
        assertSame(furniture,slot.getFurniture()); assertEquals("slot",slot.getId());
        slot.setCurrentItem(new ItemStack(Material.STONE)); assertEquals(Material.STONE,display.getItemStack().getType());
        slot.forceModel(new ItemStack(Material.DIRT)); assertEquals(Material.DIRT,display.getItemStack().getType());
        slot.clearModel(); assertNull(slot.getDisplayStandId()); assertFalse(entities.containsKey(display.getUniqueId()));
        assertFalse(furniture.getActiveSlots().containsKey("slot"));
    }
    @Test
    void displayDataUpdatesPositionAndScaleWhileCarryUsesInterpolation() {
        slot.forceModel(new ItemStack(Material.STONE));
        ItemDisplay display = (ItemDisplay)entities.get(slot.getDisplayStandId());
        DisplayData data = new DisplayData(); data.setxPos(2); data.setxScale(3);
        slot.setRotation(data);
        assertSame(data,slot.getCurrentDisplayData()); assertEquals(3,display.getLocation().getX());
        assertEquals(3,display.getTransformation().getScale().x);
        slot.applyDisplayData(null); assertEquals(1,display.getLocation().getX());
        furniture.carry(player);
        parent.teleport(new Location(world,10,64,0));
        Location origin = display.getLocation();
        slot.followParentTransform(parent);
        assertEquals(origin,display.getLocation(),"carried display interpolates from its current origin");
        assertEquals(10,display.getTransformation().getTranslation().x);
        verify(display).setInterpolationDuration(2); verify(display).setInterpolationDelay(0);
        DisplayData old = slot.getCurrentDisplayData(); slot.applyDisplayData(data); assertSame(old,slot.getCurrentDisplayData());
        furniture.stopCarrying(); slot.syncDisplayToParent(parent,null);
        assertEquals(11,display.getLocation().getX());
    }
    @Test
    void detachedSlotAndMissingEntitiesAllowSafeInMemoryUpdates() {
        slot.updateDisplay(); slot.applyDisplayData(null); slot.followParentTransform(parent);
        slot.setDisplayStandId(UUID.randomUUID()); slot.setCurrentItem(new ItemStack(Material.STONE));
        slot.applyDisplayData(null); slot.followParentTransform(parent);
        slot.setDisplayStandId(null); entities.remove(parent.getUniqueId());
        slot.forceModel(new ItemStack(Material.DIAMOND)); assertEquals(Material.DIAMOND,slot.getCurrentItem().getType());
        slot.setFurniture(null); assertNull(slot.getDefinition()); assertNull(slot.getFurniture());
        slot.setDisplayStandId(UUID.randomUUID()); slot.applyDisplayData(null); slot.followParentTransform(parent);
        slot.removeDisplayStand(null); assertNull(slot.getCurrentItem()); assertNull(slot.getDisplayStandId());
    }
    @Test
    void missingDefinitionAndParentDoNotRepositionExistingDisplays() {
        slot.forceModel(new ItemStack(Material.STONE)); ItemDisplay display=(ItemDisplay)entities.get(slot.getDisplayStandId());
        slot.setFurniture(furniture("missing"));
        slot.applyDisplayData(null); slot.syncDisplayToParent(parent,null);
        assertEquals(new Location(world,1,65.75,3),display.getLocation());
        slot.setFurniture(furniture); entities.remove(parent.getUniqueId()); slot.applyDisplayData(null);
        slot.syncDisplayToParent(null,null); slot.spawnDisplayStand(furniture.getLoc(),new ItemStack(Material.STONE),null,null);
        slot.setCurrentItem(new ItemStack(Material.AIR)); assertNull(slot.getDisplayStandId());
    }
    @Test
    void attachedForcedModelUsesParentTransformAndRemovalOnlyDeletesItsOwnDisplay() {
        furniture.setAttachment(UUID.randomUUID(),"parent"); slot.forceModel(new ItemStack(Material.STONE));
        ItemDisplay extra = display(furniture.getLoc()); UUID old=slot.getDisplayStandId();
        slot.removeDisplayStand(world);
        assertNull(slot.getCurrentItem()); assertNull(slot.getDisplayStandId()); assertFalse(entities.containsKey(old));
        assertTrue(entities.containsKey(extra.getUniqueId()));
        slot.removeDisplayStand(world);
    }


}

package net.tfminecraft.interactiblefurniture.furniture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.tfminecraft.interactiblefurniture.enums.Display;
import net.tfminecraft.interactiblefurniture.enums.SoundEffect;
import net.tfminecraft.interactiblefurniture.furniture.data.*;

class ModelConfigurationTest {
    private YamlConfiguration yaml(String input) throws Exception {
        var cfg = new YamlConfiguration(); cfg.loadFromString(input); return cfg;
    }

    @ParameterizedTest
    @ValueSource(strings = {"templates", "slot-templates"})
    void rootTemplatesAreAvailableBeforeSlotsAreResolved(String key) throws Exception {
        FurnitureType type = new FurnitureType("table", yaml(key + """
                :
                  shelf:
                    offset: {x: 1, y: 2, z: 3}
                    whitelist: [STONE]
                    display:
                      rotation: {x: 5, y: 10, z: 15}
                      scale: {x: 2, y: 3, z: 4}
                      position: {x: 6, y: 7, z: 8}
                    slot-type: item
                    interactible: true
                    drop-on-break: false
                slots:
                  top:
                    location: '2,3,4'
                    template: shelf
                    base: {}
                    override:
                      template: shelf
                      offset: {y: 9}
                      display:
                        rotation: {z: 20}
                        scale: {y: 5}
                        position: {x: 10}
                """));
        SlotDefinition base = type.getSlot("base"), override = type.getSlot("override");
        assertEquals(new Vector(1,2,3), base.getOffset());
        assertEquals(new Vector(1,9,3), override.getOffset());
        assertEquals(new Vector(5,10,20), override.getDisplayRotation());
        assertEquals(new Vector(2,5,4), override.getDisplayScale());
        assertEquals(new Vector(10,7,8), override.getDisplayPosition());
        assertEquals(List.of("STONE"), base.getWhitelist()); assertTrue(base.isInteractible());
        assertFalse(base.dropsOnBreak()); assertEquals(SlotType.ITEM, base.getSlotType());
        assertEquals(2,base.getLayer()); assertEquals(3,base.getRow()); assertEquals(4,base.getCol());
        assertEquals(2,type.getSlotsForBlock(2,3,4).size()); assertTrue(type.getSlotsForBlock(1,1,1).isEmpty());
        Vector changed = base.getOffset(); changed.setX(42); assertEquals(1,base.getOffset().getX());
        assertThrows(UnsupportedOperationException.class, () -> base.getWhitelist().add("DIRT"));
    }

    @Test
    void stringWhitelistRetainsItsSingleAllowedItem() throws Exception {
        FurnitureType type = new FurnitureType("single", yaml("""
                slots:
                  top:
                    slot:
                      whitelist: STONE
                """));
        assertTrue(type.getSlot("slot").isInteractible());
        assertTrue(type.getSlot("slot").isItemAllowed("STONE"));
        assertFalse(type.getSlot("slot").isItemAllowed("DIRT"));
    }

    @Test
    void configurationDefaultsCopiesAndOverridesPreserveModelValues() throws Exception {
        var source = new ModelData(Display.ITEM_DISPLAY, "v.stone");
        var data = new FurnitureDataContainer(source);
        assertNotSame(source,data.getModelData()); assertEquals("v.stone",data.getCurrentModelData().getModel());
        assertEquals(Display.ITEM_DISPLAY, data.getModelData().getDisplay()); assertNull(data.getModelOverride());
        var override = new ModelData(Display.ITEM_DISPLAY,"v.diamond"); data.setModelOverride(override);
        assertSame(override,data.getModelOverride()); assertSame(override,data.getCurrentModelData());
        data.setVariables(Map.of("heat",10)); assertEquals(10,data.getVariables().get("heat"));
        var parsed = new FurnitureDataContainer(yaml("display: item_display\nmodel: v.barrel\n"));
        assertEquals("v.barrel",parsed.getModelData().getModel());
        assertEquals("v.paper",new FurnitureDataContainer(yaml("display: unknown\nmodel: bad\n")).getModelData().getModel());
        assertNull(new FurnitureDataContainer(yaml("model: absent-display\n")).getModelData());

        var interaction = new InteractionData(yaml("width: 3\nheight: 4\noffset: {x: 1, y: 2, z: 3}\n"));
        var copy = new InteractionData(interaction);
        assertEquals(3,copy.getWidth()); assertEquals(4,copy.getHeight());
        assertEquals(new Vector(1,2,3),copy.getOffset());
        interaction.getOffset().setX(99); assertEquals(1,copy.getOffset().getX());
        var defaults = new InteractionData(yaml("{}"));
        assertEquals(1.5f,defaults.getWidth()); assertEquals(2,defaults.getHeight());
        assertEquals(new Vector(0,.5,0),defaults.getOffset());
    }

    @Test
    void displayConfigurationAndMutatorsRetainEachIndependentAxis() throws Exception {
        var parsed = new DisplayData(yaml("""
                rotation: {x: 1, y: 2, z: 3}
                scale: {x: 4, y: 5, z: 6}
                position: {x: 7, y: 8, z: 9}
                """));
        assertArrayEquals(new float[]{1,2,3,4,5,6,7,8,9},values(parsed));
        var defaults = new DisplayData(yaml("{}"));
        assertArrayEquals(new float[]{0,0,0,1,1,1,0,0,0}, values(defaults));
        var changed = new DisplayData();
        changed.setxRot(9); changed.setyRot(8); changed.setzRot(7);
        changed.setxScale(6); changed.setyScale(5); changed.setzScale(4);
        changed.setxPos(3); changed.setyPos(2); changed.setzPos(1);
        assertArrayEquals(new float[]{9,8,7,6,5,4,3,2,1}, values(changed));
    }
    private float[] values(DisplayData d) {
        return new float[]{d.getxRot(),d.getyRot(),d.getzRot(),d.getxScale(),d.getyScale(),d.getzScale(),d.getxPos(),d.getyPos(),d.getzPos()};
    }

    @Test
    void layersSupportSortedListSectionAndScalarFormatsWithInvalidSizesIgnored() throws Exception {
        var type = new FurnitureType("layers",yaml("""
                allowed-blocks: [stone, invalid]
                sound: {place: test.place, bad: ignored}
                layers:
                  '3': X
                  '1': [x]
                  '2': {row: X}
                  '4': []
                  '5': [XX, XX]
                  not-a-layer: [X]
                slots:
                  bad: scalar
                  malformed:
                    location: '1,2'
                  valid:
                    ignored: scalar
                """));
        assertEquals(3,type.getLayers().size());
        for (var layer:type.getLayers()) assertTrue(layer[0][0]);
        assertTrue(type.isAllowedBlock(Material.STONE)); assertFalse(type.isAllowedBlock(Material.DIRT));
        assertTrue(type.hasSoundEffect(SoundEffect.PLACE)); assertEquals("test.place",type.getSoundEffectPath(SoundEffect.PLACE));
        assertTrue(type.getSlots().isEmpty());
        assertEquals(SlotType.ITEM,SlotType.fromString(null)); assertEquals(SlotType.ITEM,SlotType.fromString(" "));
        assertEquals(SlotType.ITEM,SlotType.fromString("invalid")); assertEquals(SlotType.FURNITURE,SlotType.fromString(" furniture "));
    }
}

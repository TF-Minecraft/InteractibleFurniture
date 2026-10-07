package net.tfminecraft.interactiblefurniture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.interactiblefurniture.database.ItemStackCodec;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.loaders.*;
import net.tfminecraft.interactiblefurniture.utils.CoordinateUtils;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilityContractsTest extends DisplayTestRig {
    @TempDir Path directory;
    @Test void standaloneLoadersPublishOnlyCompleteFilesAndRetainPriorEntriesOnFailure() throws Exception {
        var furnitureLoader=new FurnitureLoader(); var soundLoader=new SoundLoader();
        Path file=directory.resolve("furniture.yml"); Files.writeString(file,"crate:\n  item: v.paper\nignored: scalar\n");
        furnitureLoader.load(file.toFile()); assertEquals("v.paper",FurnitureLoader.getByString("crate").getItemPath());
        Path sounds=directory.resolve("sounds.yml"); Files.writeString(sounds,"sounds: ['v.paper test.paper', 'malformed']");
        soundLoader.load(sounds.toFile()); assertEquals("test.paper",SoundLoader.getByString("v.paper"));
        Files.writeString(file,"crate: ["); assertThrows(IllegalArgumentException.class,()->furnitureLoader.load(file.toFile()));
        assertEquals("v.paper",FurnitureLoader.getByString("crate").getItemPath());
        Files.writeString(sounds,"sounds: ["); assertThrows(IllegalArgumentException.class,()->soundLoader.load(sounds.toFile()));
        assertEquals("test.paper",SoundLoader.getByString("v.paper"));
    }
    @Test void clickRaysIntersectFacesAndDistancesUseTheirDocumentedAxes() {
        Player viewer=mock(Player.class); Block clicked=mock(Block.class);
        when(clicked.getLocation()).thenAnswer(c->new Location(world,0,64,2));
        when(viewer.getLocation()).thenReturn(new Location(world,.5,64,0,0,0));
        when(viewer.getEyeLocation()).thenReturn(new Location(world,.5,65.6,0));
        // The public utility remains usable through its historical no-argument constructor.
        CoordinateUtils legacy=new CoordinateUtils();
        assertEquals(new Vector(.5,65.6,2),legacy.calculateClickPoint(viewer,clicked,BlockFace.NORTH));
        assertEquals(new Vector(.5,65,2.5),CoordinateUtils.calculateClickPoint(viewer,clicked,BlockFace.UP));
        assertEquals(5,CoordinateUtils.calculateDistance(new Vector(0,99,0),new Vector(3,1,4),BlockFace.UP));
        assertEquals(5,CoordinateUtils.calculateDistance(new Vector(0,99,0),new Vector(3,1,4),BlockFace.DOWN));
        assertEquals(13,CoordinateUtils.calculateDistance(new Vector(),new Vector(3,4,12),BlockFace.NORTH));
        assertEquals(13,CoordinateUtils.distance3D(new Vector(),new Vector(3,4,12)));
    }
    @Test void itemCodecRoundTripsRealMetadataAndRejectsUnserializableProviderData() {
        ItemStack stack=new ItemStack(Material.DIAMOND,3); var meta=stack.getItemMeta(); meta.setDisplayName("Keepsake"); stack.setItemMeta(meta);
        assertEquals(stack,ItemStackCodec.deserialize(ItemStackCodec.serialize(stack)));
        ItemStack unsupported=mock(ItemStack.class);
        when(unsupported.serialize()).thenReturn(Map.of("provider",new Object()));
        assertNull(ItemStackCodec.serialize(unsupported));
        assertNull(ItemStackCodec.deserialize("not-base64")); assertNull(ItemStackCodec.serialize(null));
    }
    @Test void slotPoliciesDistinguishFurnitureAndItemsAndExposePlacementPreferences() throws Exception {
        FurnitureType definition=type("table","""
                item: v.paper
                rotate-to-player: true
                slots:
                  top:
                    item: {whitelist: [v.stone]}
                    child: {slot-type: furniture, whitelist: [v.paper]}
                """);
        SlotDefinition item=definition.getSlot("item"),child=definition.getSlot("child");
        assertFalse(item.isFurnitureAllowed(definition)); assertFalse(child.isFurnitureAllowed(null));
        assertFalse(child.isItemAllowed("v.paper")); assertTrue(item.isItemAllowed("v.stone"));
        assertTrue(child.isFurnitureAllowed(definition));
        assertTrue(definition.shouldRotateToPlayer());
    }
}

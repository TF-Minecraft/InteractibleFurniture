package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.furniture.data.ModelData;
import net.tfminecraft.interactiblefurniture.enums.Display;
import net.tfminecraft.interactiblefurniture.utils.Keys;
import net.tfminecraft.tlibs.TLibs;

class DisplayCreationTest extends DisplayTestRig {
    @ParameterizedTest @EnumSource(value=BlockFace.class,names={"UP","DOWN","NORTH","SOUTH","EAST","WEST","SELF"})
    void displayFactoryAppliesModelSettingsAndRotationForEachSurface(BlockFace face) throws Exception {
        var type=type("model","""
                model: {display: ITEM_DISPLAY, model: v.stone}
                display:
                  rotation: {x: 10, y: 20, z: 30}
                  scale: {x: 2, y: 3, z: 4}
                  position: {x: 5, y: 6, z: 7}
                """);
        Location at=new Location(world,1,2,3);
        ItemDisplay result=FurniturePlacementHandler.spawnDisplayAt(type,at,90,face);
        assertEquals(Material.PAPER,result.getItemStack().getType()); assertEquals(at,result.getLocation());
        assertEquals(new org.joml.Vector3f(5,6,7),result.getTransformation().getTranslation());
        assertEquals(new org.joml.Vector3f(2,3,4),result.getTransformation().getScale());
        var rotation=new org.joml.Quaternionf().rotateY((float)Math.toRadians(110)).rotateX((float)Math.toRadians(10)).rotateZ((float)Math.toRadians(30));
        if(face==BlockFace.DOWN)rotation.rotateX((float)-Math.PI);
        if(face==BlockFace.NORTH||face==BlockFace.SOUTH||face==BlockFace.EAST||face==BlockFace.WEST)rotation.rotateX((float)(-Math.PI/2));
        assertTrue(rotation.equals(result.getTransformation().getLeftRotation(),0.00001f));
        assertEquals(result.getUniqueId().toString(),result.getPersistentDataContainer().get(Keys.furnitureDisplay(),PersistentDataType.STRING));
        verify(result).setPersistent(true); verify(result).setViewRange(50f);
    }
    @Test
    void unavailableModelDoesNotSpawnAnInvisibleFurnitureDisplay() throws Exception {
        var type=type("missing-model","model: {display: ITEM_DISPLAY, model: missing}\n");
        when(TLibs.getItemAPI().getCreator().getItemFromPath("missing")).thenReturn(null);
        assertNull(FurniturePlacementHandler.spawnDisplayAt(type,new Location(world,0,64,0),0,BlockFace.UP));
        assertTrue(entities.isEmpty());
    }
    @Test
    void rejectsUnsupportedModelsAndOccupiedOriginsAndHonorsInstanceOverrides() throws Exception {
        var type=type("model","{}"); Location at=new Location(world,0,64,0);
        assertNull(FurniturePlacementHandler.spawnDisplayAt(null,at,0,BlockFace.UP,new ModelData(Display.ITEM_DISPLAY,"v.stone")));
        assertNull(FurniturePlacementHandler.spawnDisplayAt(type,at,0,BlockFace.UP,null));
        assertNull(FurniturePlacementHandler.spawnDisplayAt(type,at,0,BlockFace.UP,new ModelData(Display.MEG,"unsupported")));
        var f=furniture("model");
        when(manager.getByLocation(any(Location.class))).thenReturn(f);
        assertNull(FurniturePlacementHandler.spawnDisplayEntity(type,at,0,BlockFace.UP));
        f.setModelOverride(new ModelData(Display.ITEM_DISPLAY,"override"));
        assertNotNull(FurniturePlacementHandler.spawnDisplayAt(f,at,0,BlockFace.UP));
    }
}

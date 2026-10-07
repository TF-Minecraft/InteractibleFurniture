package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;

class PlacementCoverageTest extends FurnitureTestServer {
    private FurnitureType crate(String options) throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("item: test.crate\n"+options);
        var type = new FurnitureType(CRATE,yaml); FurnitureLoader.getMap().put(CRATE,type); return type;
    }
    private boolean place(BlockFace face) {
        ItemStack item = new ItemStack(Material.BARREL,2); player.getInventory().setItemInMainHand(item);
        // Paper returns a fresh Location; MockBukkit BlockMock returns its mutable internal one.
        var clicked = spy(ground(4,4));
        var location = clicked.getLocation().clone();
        doAnswer(call -> location.clone()).when(clicked).getLocation();
        return FurniturePlacementHandler.handlePlacement(player,clicked,face,item,manager.getPlacedFurniture());
    }

    @Test
    void roofPlacementChecksTheSameBlocksItWouldOccupy() throws Exception {
        crate("solid: true\nplacement_options: {roof: true}\n");
        world.getBlockAt(4,GROUND_Y-1,4).setType(Material.STONE);
        assertTrue(place(BlockFace.DOWN),"recognized furniture consumes the interaction");
        assertTrue(manager.getPlacedFurniture().isEmpty(),"blocked roof space must reject placement");
        assertEquals(2,player.getInventory().getItemInMainHand().getAmount());
        assertEquals(Material.STONE,world.getBlockAt(4,GROUND_Y-1,4).getType());
    }
    @Test
    void existingBarrierCanBeSharedAsSupportedByConnectedFurnitureBreaking() throws Exception {
        crate("solid: true\nplacement_options: {floor: true}\n");
        world.getBlockAt(4,GROUND_Y+1,4).setType(Material.BARRIER);
        assertTrue(place(BlockFace.UP));
        Furniture placed = manager.getPlacedFurniture().values().iterator().next();
        assertTrue(placed.getBarrierBlocks().contains(world.getBlockAt(4,GROUND_Y+1,4)));
        assertEquals(1,player.getInventory().getItemInMainHand().getAmount());
    }

    static Stream<Arguments> directions() {
        return Stream.of(Arguments.of(0f,180f),Arguments.of(45f,135f),Arguments.of(90f,90f),Arguments.of(135f,45f),
                Arguments.of(180f,0f),Arguments.of(225f,315f),Arguments.of(270f,270f),Arguments.of(315f,225f),
                Arguments.of(-45f,225f),Arguments.of(720f,180f),Arguments.of(359f,180f));
    }
    @ParameterizedTest @MethodSource("directions")
    void floorPlacementSnapsPlayerFacingInAllDirections(float yaw,float expected) throws Exception {
        crate("placement_options: {floor: true}\nsound: {place: test.place}\n");
        Location at=player.getLocation(); at.setYaw(yaw); player.teleport(at);
        assertTrue(place(BlockFace.UP));
        assertEquals(expected,manager.getPlacedFurniture().values().iterator().next().getYaw());
        assertEquals(1,player.getInventory().getItemInMainHand().getAmount());
    }
    @ParameterizedTest @ValueSource(floats={0,90,180,270,359})
    void cardinalOnlyFurnitureSnapsWithoutDiagonalRotation(float yaw) throws Exception {
        crate("placement_options: {floor: true}\ndiagonal: false\n");
        Location at=player.getLocation(); at.setYaw(yaw); player.teleport(at);
        assertTrue(place(BlockFace.UP));
        float expected=yaw>=315?180:(540-yaw)%360;
        assertEquals(expected,manager.getPlacedFurniture().values().iterator().next().getYaw());
    }
    static Stream<Arguments> surfaces() {
        return Stream.of(Arguments.of(BlockFace.NORTH,0f,4.5,64.5,3.5),Arguments.of(BlockFace.SOUTH,180f,4.5,64.5,5.5),
                Arguments.of(BlockFace.EAST,270f,5.5,64.5,4.5),Arguments.of(BlockFace.WEST,90f,3.5,64.5,4.5),
                Arguments.of(BlockFace.DOWN,180f,4.5,63.5,4.5));
    }
    @ParameterizedTest @MethodSource("surfaces")
    void wallAndRoofPlacementUseFaceSpecificOrigins(BlockFace face,float yaw,double x,double y,double z) throws Exception {
        crate("placement_options: {wall: true, roof: true}\n"); assertTrue(place(face));
        Furniture result=manager.getPlacedFurniture().values().iterator().next();
        assertEquals(yaw,result.getYaw()); assertEquals(new Location(world,x,y,z),result.getLoc());
    }
    @Test
    void insidePlacementAndInvalidSurfacesRespectConfiguration() throws Exception {
        crate("placement_options: {floor: true}\nplace-inside: true\nallowed-blocks: [STONE]\n");
        assertFalse(place(BlockFace.SELF)); assertFalse(place(null)); assertFalse(place(BlockFace.DOWN));
        assertTrue(place(BlockFace.UP));
        assertEquals(64.5,manager.getPlacedFurniture().values().iterator().next().getLoc().getY());
        assertEquals(new Location(world,1,2,3),FurniturePlacementHandler.applyFaceOffset(new Location(world,1,2,3),BlockFace.SELF));
        assertFalse(FurniturePlacementHandler.placeCarriedFurniture(player,ground(10,10),BlockFace.UP,null,manager.getPlacedFurniture()));
        assertNotNull(new FurniturePlacementHandler());
    }
    @Test
    void cancelledPlacementRemovesItsProvisionalDisplayAndPreservesHeldItems() throws Exception {
        crate("placement_options: {floor: true}\n");
        int before=world.getEntities().size();
        server.getPluginManager().registerEvent(net.tfminecraft.interactiblefurniture.events.FurniturePlaceEvent.class,
                new org.bukkit.event.Listener(){},org.bukkit.event.EventPriority.NORMAL,
                (ignored,event)->((net.tfminecraft.interactiblefurniture.events.FurniturePlaceEvent)event).setCancelled(true),plugin);
        assertFalse(place(BlockFace.UP)); assertTrue(manager.getPlacedFurniture().isEmpty());
        assertEquals(before,world.getEntities().size()); assertEquals(2,player.getInventory().getItemInMainHand().getAmount());
    }
    @Test
    void carriedPlacementCannotOverwriteAnOccupiedTargetBlock() throws Exception {
        Furniture carried=place(CRATE,4,4); carried.carry(player);
        crate("solid: true\nplacement_options: {floor: true}\n");
        var target=spy(ground(10,10)); var targetLocation=target.getLocation().clone();
        doAnswer(call->targetLocation.clone()).when(target).getLocation();
        world.getBlockAt(10,GROUND_Y+1,10).setType(Material.STONE);
        assertFalse(FurniturePlacementHandler.placeCarriedFurniture(player,target,BlockFace.UP,carried,manager.getPlacedFurniture()));
        assertTrue(carried.isCarried()); assertEquals(Material.STONE,world.getBlockAt(10,GROUND_Y+1,10).getType());
    }

}

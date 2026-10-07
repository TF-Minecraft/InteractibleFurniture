package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.*;
import net.tfminecraft.interactiblefurniture.DisplayTestRig;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;

class AttachmentTransformTest extends DisplayTestRig {
    @BeforeEach
    void nodes() throws Exception {
        type("node","""
                item: v.barrel
                slots:
                  group:
                    child:
                      slot-type: furniture
                      whitelist: ['*']
                      offset: {x: 2}
                """);
    }
    @Test
    void cannotAttachAncestorInsideItsDescendant() {
        Furniture root = furniture("node"), child = furniture("node");
        assertTrue(FurnitureAttachmentHandler.attach(root,"child",child,player));
        assertFalse(FurnitureAttachmentHandler.attach(child,"child",root,player));
        assertFalse(root.isAttached()); assertTrue(child.isAttached());
        assertTrue(child.getActiveFurnitureSlots().isEmpty());
        assertSame(root,placed.get(root.getEntityId()));
    }
    @Test
    void movingRootUpdatesEveryNestedLevelAndTheirItemDisplays() {
        Furniture root = furniture("node"), child = furniture("node"), grandchild = furniture("node");
        assertTrue(FurnitureAttachmentHandler.attach(child,"child",grandchild,player));
        assertTrue(FurnitureAttachmentHandler.attach(root,"child",child,player));
        root.setLoc(new Location(world,10,64,0));
        ((ItemDisplay)entities.get(root.getEntityId())).teleport(root.getLoc());
        FurnitureNestedDisplay.onParentTransformChanged(root);
        assertEquals(12,child.getLoc().getX());
        assertEquals(14,grandchild.getLoc().getX());
        assertEquals(grandchild.getLoc(),entities.get(grandchild.getEntityId()).getLocation());
        assertSame(child,FurnitureAttachmentHandler.detach(root,"child",player));
        assertFalse(child.isAttached()); assertSame(child,placed.get(child.getEntityId()));
        assertSame(grandchild,child.getActiveFurnitureSlot("child").orElseThrow().getNested());
    }
}

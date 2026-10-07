package net.tfminecraft.interactiblefurniture.manager.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.utils.Keys;

class InteractionLifecycleTest extends FurnitureTestServer {
    @Test
    void hitboxFollowsFurnitureRespawnsMissingOrDeadEntitiesAndRemovesCleanly() {
        Furniture stool = place(STOOL,4,4);
        UUID first = stool.getInteractionEntityId();
        Interaction hitbox = (Interaction)server.getEntity(first);
        assertEquals(1,hitbox.getInteractionWidth()); assertEquals(1,hitbox.getInteractionHeight());
        assertTrue(hitbox.isResponsive()); assertTrue(hitbox.isPersistent());
        assertEquals(stool.getEntityId().toString(),hitbox.getPersistentDataContainer().get(Keys.furnitureEntity(),PersistentDataType.STRING));
        InteractionHandler.spawnInteraction(stool); assertEquals(first,stool.getInteractionEntityId());
        stool.setLoc(new Location(world,8,66,8)); InteractionHandler.updateInteractionPosition(stool);
        assertEquals(new Location(world,8,66.5,8),hitbox.getLocation());
        hitbox.remove(); InteractionHandler.updateInteractionPosition(stool);
        assertNotEquals(first,stool.getInteractionEntityId());
        InteractionHandler.removeInteraction(stool); assertNull(stool.getInteractionEntityId());
        InteractionHandler.updateInteractionPosition(stool); assertNotNull(stool.getInteractionEntityId());
        stool.setInteractionEntityId(UUID.randomUUID()); InteractionHandler.removeInteraction(stool);
        assertNull(stool.getInteractionEntityId()); InteractionHandler.removeInteraction(stool);
    }

    @Test
    void noninteractiveUnknownAndSolidTypesHaveNoHitboxes() throws Exception {
        var unknown = new Furniture("missing",new Location(world,0,64,0),UUID.randomUUID());
        InteractionHandler.spawnInteraction(unknown); InteractionHandler.updateInteractionPosition(unknown);
        assertNull(InteractionHandler.getInteractionLocation(unknown));
        Furniture crate = place(CRATE,4,4);
        InteractionHandler.spawnInteraction(crate); InteractionHandler.updateInteractionPosition(crate);
        assertNull(crate.getInteractionEntityId()); assertNull(InteractionHandler.getInteractionLocation(crate));
        Furniture stool = place(STOOL,8,4); UUID old = stool.getInteractionEntityId();
        YamlConfiguration cfg = new YamlConfiguration(); cfg.loadFromString("solid: true\ninteraction: {width: 2}\n");
        FurnitureLoader.getMap().put(STOOL,new FurnitureType(STOOL,cfg));
        InteractionHandler.spawnInteraction(stool); assertEquals(old,stool.getInteractionEntityId());
        InteractionHandler.updateInteractionPosition(stool); assertNull(stool.getInteractionEntityId());
        assertNull(server.getEntity(old));
    }

    @Test
    void taggedInteractionsResolveNestedDescendantsAndRejectMissingOrInvalidIds() {
        Furniture root = place(TABLE,4,4), child = place(CRATE,6,4), grandchild = place(CRATE,8,4);
        root.getOrCreatePlacedFurnitureSlot("surface").setNested(child);
        root.getOrCreatePlacedFurnitureSlot("empty");
        child.getOrCreatePlacedFurnitureSlot("nested").setNested(grandchild);
        Map<UUID,Furniture> roots = Map.of(root.getEntityId(),root);
        Interaction entity = world.spawn(new Location(world,0,64,0),Interaction.class);
        assertNull(InteractionHandler.resolveFurniture(entity,roots));
        entity.getPersistentDataContainer().set(Keys.furnitureEntity(),PersistentDataType.STRING,"not-a-uuid");
        assertNull(InteractionHandler.resolveFurniture(entity,roots));
        for (Furniture expected : java.util.List.of(root,child,grandchild)) {
            entity.getPersistentDataContainer().set(Keys.furnitureEntity(),PersistentDataType.STRING,expected.getEntityId().toString());
            assertSame(expected,InteractionHandler.resolveFurniture(entity,roots));
        }
        assertSame(root,InteractionHandler.findFurniture(root.getEntityId(),Map.of(UUID.randomUUID(),root)));
        assertNull(InteractionHandler.findFurniture(UUID.randomUUID(),roots));
        assertNotNull(new InteractionHandler());
    }
    @Test
    void restoringInteractiveFurnitureRecreatesItsMissingHitbox() {
        Furniture stool=place(STOOL,4,4); UUID old=stool.getInteractionEntityId();
        server.getEntity(old).remove();
        assertSame(stool,FurnitureRestoreHandler.restore(stool));
        assertNotEquals(old,stool.getInteractionEntityId());
        assertNotNull(server.getEntity(stool.getInteractionEntityId()));
    }

}

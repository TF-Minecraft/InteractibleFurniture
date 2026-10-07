package net.tfminecraft.interactiblefurniture.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.events.FurnitureBreakEvent;
import net.tfminecraft.interactiblefurniture.events.FurnitureInteractEvent;
import net.tfminecraft.interactiblefurniture.events.FurniturePunchEvent;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.interactiblefurniture.furniture.SlotType;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurniturePlacementHandler;
import net.tfminecraft.interactiblefurniture.protection.FurnitureProtection;
import net.tfminecraft.interactiblefurniture.utils.Keys;

class FurnitureManagerCoverageTest extends FurnitureTestServer {
    @Test
    void locationAndChunkQueriesDistinguishPlacedFurnitureFromCarriedFurniture() {
        Furniture crate = place(CRATE, 4, 4);
        Furniture table = place(TABLE, 8, 4);
        Furniture elsewhere = place(CRATE, 20, 4);
        assertSame(crate, manager.getByLocation(crate.getLoc().clone()));
        assertNull(manager.getByLocation(new Location(world, 99, 99, 99)));
        assertFalse(manager.releaseCarried(player));
        crate.carry(player);

        assertSame(crate, manager.getByCarrier(player));
        assertNull(manager.getByCarrier(server.addPlayer()));
        assertNull(manager.getByLocation(crate.getLoc()));
        assertEquals(Set.of(table), manager.getFurnitureInChunk(world.getChunkAt(0, 0)));
        assertEquals(Set.of(table, crate), manager.getFurnitureForSave(world.getChunkAt(0, 0)));
        assertEquals(Set.of(elsewhere), manager.getFurnitureInChunk(world.getChunkAt(1, 0)));
    }

    @Test
    void savedFurnitureVisitorIsSafeBeforeStartupAndDelegatesAfterStartup() {
        List<Furniture> found = new ArrayList<>();
        new FurnitureManager().visitSavedFurniture(found::add);
        assertTrue(found.isEmpty());
        Furniture crate = place(CRATE, 4, 4);
        manager.visitSavedFurniture(found::add);
        assertEquals(List.of(crate.getEntityId()), found.stream().map(Furniture::getEntityId).toList());
    }

    @Test
    void rightClickPlacesHeldFurnitureAndConsumesExactlyOneItem() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.BARREL, 2));
        PlayerInteractEvent event = right(ground(4, 4));

        manager.onPlayerInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(1, manager.getPlacedFurniture().size());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(CRATE, manager.getPlacedFurniture().values().iterator().next().getId());
        assertEquals(1, savedIn(0, 0).size());
    }

    @Test
    void unrelatedRightClicksAndMissingClickedBlocksLeaveTheWorldAndInventoryAlone() {
        manager.onPlayerInteract(new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, null, null, BlockFace.UP));
        manager.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, null, BlockFace.UP));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 2));
        manager.onPlayerInteract(right(ground(4, 4)));
        clearCooldowns();
        player.getInventory().setItemInMainHand(null);
        manager.onPlayerInteract(right(ground(8, 4)));

        assertTrue(manager.getPlacedFurniture().isEmpty());
        assertTrue(droppedItems().isEmpty());
        assertEquals(Material.STONE, ground(4, 4).getType());
    }

    @Test
    void emptyPickupGivesTheFurnitureItemToThePlayerWithoutDroppingIt() {
        Furniture crate = place(CRATE, 4, 4);

        manager.onPlayerInteract(right(ground(4, 4)));

        assertFalse(manager.getPlacedFurniture().containsKey(crate.getEntityId()));
        assertEquals(Material.BARREL, player.getInventory().getItemInMainHand().getType());
        assertTrue(droppedItems().isEmpty());
        assertTrue(savedIn(0, 0).isEmpty());
    }

    @Test
    void interactionCancellationPreservesFurnitureAndCooldownSuppressesRepeatedEvents() throws Exception {
        Furniture crate = place(CRATE, 4, 4);
        List<FurnitureInteractEvent> seen = new ArrayList<>();
        listen(FurnitureInteractEvent.class, event -> { seen.add(event); event.setCancelled(true); });
        PlayerInteractEvent first = right(ground(4, 4));
        manager.onPlayerInteract(first);
        manager.pulse(player);
        manager.onPlayerInteract(right(ground(4, 4)));
        assertEquals(1, seen.size());
        assertEquals(Event.Result.DENY, first.useInteractedBlock());
        assertTrue(manager.getPlacedFurniture().containsKey(crate.getEntityId()));

        Thread.sleep(220); // Exercise a real expired deadline rather than editing the cooldown map.
        manager.onPlayerInteract(right(ground(4, 4)));
        assertEquals(2, seen.size());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void aCarriedPieceCanBeAttachedOrPlacedThroughBlockClicks() {
        Furniture parent = place(TABLE, 4, 4);
        Furniture crate = place(CRATE, 8, 4);
        crate.carry(player);
        clearCooldowns();
        PlayerInteractEvent attach = right(ground(4, 4));

        manager.onPlayerInteract(attach);

        assertEquals(Event.Result.DENY, attach.useInteractedBlock());
        assertSame(crate, parent.getActiveFurnitureSlot("surface").orElseThrow().getNested());
        assertFalse(crate.isCarried());
        assertFalse(manager.getPlacedFurniture().containsKey(crate.getEntityId()));

        Furniture other = place(CRATE, 12, 4);
        other.carry(player);
        clearCooldowns();
        PlayerInteractEvent place = right(ground(20, 4));
        manager.onPlayerInteract(place);
        assertEquals(Event.Result.DENY, place.useInteractedBlock());
        assertFalse(other.isCarried());
        assertTrue(other.isOriginBlock(ground(20, 4)));
        assertEquals(other.getEntityId(), savedIn(1, 0).getFirst().getEntityId());
    }

    @Test
    void failedCarryPlacementLeavesThePieceInThePlayersHands() {
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        Block blocked = ground(20, 4);
        clearCooldowns();

        // The crate supports floor placement only; this is a click on the block's underside.
        manager.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                blocked, BlockFace.DOWN, EquipmentSlot.HAND));

        assertTrue(crate.isCarried());
        assertSame(crate, manager.getByCarrier(player));
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void entityClicksCarryTheResolvedFurnitureAndRespectCooldown() throws Exception {
        Furniture stool = place(STOOL, 4, 4);
        Interaction interaction = interaction(stool);
        List<FurnitureInteractEvent> seen = new ArrayList<>();
        listen(FurnitureInteractEvent.class, seen::add);
        player.setSneaking(true);

        PlayerInteractAtEntityEvent first = at(player, interaction);
        manager.onPlayerInteractAtEntity(first);
        assertTrue(first.isCancelled());
        assertTrue(stool.isCarried());
        assertEquals(1, seen.size());
        manager.pulse(player);
        manager.onPlayerInteractAtEntity(at(player, interaction));
        assertEquals(1, seen.size());
        Thread.sleep(220);
        // An interaction packet already queued before carry removed the entity must not act again.
        manager.onPlayerInteractAtEntity(at(player, interaction));
        assertEquals(1, seen.size());
        assertTrue(stool.isCarried());
    }

    @Test
    void entityClicksIgnoreUnrelatedEntitiesAndUnknownFurniture() {
        Furniture stool = place(STOOL, 4, 4);
        manager.onPlayerInteractAtEntity(at(player, server.getEntity(stool.getEntityId())));
        Interaction unrelated = world.spawn(player.getLocation(), Interaction.class);
        manager.onPlayerInteractAtEntity(at(player, unrelated));

        assertFalse(stool.isCarried());
        assertEquals(1, manager.getPlacedFurniture().size());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void entityClicksAttachACarriedPieceAndProtectionKeepsItCarriedWhenDenied() throws Exception {
        tableWithInteraction();
        Furniture parent = place(TABLE, 4, 4);
        Furniture crate = place(CRATE, 8, 4);
        crate.carry(player);
        FurnitureProtection.use(new FurnitureProtection.Rules() {
            public boolean canInteract(Player who, Furniture furniture) { return false; }
            public boolean canDamage(Player who, Furniture furniture) { return true; }
        });
        clearCooldowns();
        PlayerInteractAtEntityEvent denied = at(player, interaction(parent));
        manager.onPlayerInteractAtEntity(denied);
        assertTrue(denied.isCancelled());
        assertTrue(crate.isCarried());
        assertTrue(parent.getActiveFurnitureSlots().isEmpty());

        FurnitureProtection.use(null);
        clearCooldowns();
        PlayerInteractAtEntityEvent accepted = at(player, interaction(parent));
        manager.onPlayerInteractAtEntity(accepted);
        assertTrue(accepted.isCancelled());
        assertFalse(crate.isCarried());
        assertSame(crate, parent.getActiveFurnitureSlot("surface").orElseThrow().getNested());
    }

    @Test
    void aFailedEntityAttachmentDoesNotPickUpAnotherPieceWhileAlreadyCarrying() {
        Furniture stool = place(STOOL, 4, 4);
        Furniture crate = place(CRATE, 8, 4);
        crate.carry(player);
        clearCooldowns();

        manager.onPlayerInteractAtEntity(at(player, interaction(stool)));

        assertTrue(crate.isCarried());
        assertFalse(stool.isCarried());
        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
    }

    @Test
    void blockInteractionSelectsTheClosestSlotFromTheClickPoint() {
        Furniture crate = place(CRATE, 4, 4);
        fill(crate, "lid", new ItemStack(Material.DIAMOND));
        // Slot selection uses the click point calculated from the player's viewing direction.
        player.teleport(new Location(world, 30, 70, 30, 0, 0));

        manager.onPlayerInteract(right(ground(4, 4)));

        assertEquals(Material.DIAMOND, player.getInventory().getItemInMainHand().getType());
        assertTrue(crate.getActiveSlots().isEmpty());
    }

    @Test
    void unsupportedSlotItemsLeaveTheHeldStackAndFurnitureUnchanged() {
        Furniture crate = place(CRATE, 4, 4);
        crate.getType().getSlots().put("lid", new SlotDefinition("lid", 0, 0, 0,
                new Vector(), List.of("test.crate"), null, null, null, SlotType.ITEM, true, true));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 3));

        manager.onPlayerInteract(right(ground(4, 4)));

        assertTrue(crate.getActiveSlots().isEmpty());
        assertEquals(new ItemStack(Material.DIAMOND, 3), player.getInventory().getItemInMainHand());
        assertEquals(Set.of(crate.getEntityId()), manager.getPlacedFurniture().keySet());
    }

    @Test
    void aCarriedPieceCannotAttachToSolidFurnitureWithoutSlotsOrPlaceOnItsUnderside() throws Exception {
        Furniture solid = model(solidType(), 4, 4);
        Block barrier = world.getBlockAt(4, 65, 4);
        barrier.setType(Material.BARRIER);
        solid.addBarrierBlock(barrier);
        Furniture carried = place(CRATE, 8, 4);
        carried.carry(player);
        clearCooldowns();

        manager.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                barrier, BlockFace.DOWN, EquipmentSlot.HAND));

        assertTrue(carried.isCarried());
        assertTrue(solid.getActiveFurnitureSlots().isEmpty());
        assertTrue(manager.getPlacedFurniture().containsKey(solid.getEntityId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clickingAnOccupiedFurnitureSlotWhileCarryingCannotStartASecondCarry(boolean entityClick) throws Exception {
        tableWithInteraction();
        Furniture parent = place(TABLE, 4, 4);
        Furniture nested = place(STOOL, 8, 4);
        assertTrue(FurnitureAttachmentHandler.attach(parent, "surface", nested, player));
        Furniture carried = place(CRATE, 12, 4);
        carried.carry(player);
        clearCooldowns();

        if (entityClick) manager.onPlayerInteractAtEntity(at(player, interaction(parent)));
        else manager.onPlayerInteract(right(ground(4, 4)));

        assertTrue(carried.isCarried());
        assertFalse(nested.isCarried(), "a player can carry only one piece at a time");
        assertTrue(nested.isAttached());
        assertSame(nested, parent.getActiveFurnitureSlot("surface").orElseThrow().getNested());
        assertEquals(1, manager.getPlacedFurniture().values().stream().filter(Furniture::isCarried).count());
    }

    @Test
    void sharedBarrierBreakRemovesConnectedFurnitureWithoutFalselyCancellingTheBlockBreak() throws Exception {
        String solid = solidType();
        Furniture first = model(solid, 4, 4);
        Furniture second = model(solid, 8, 4);
        Furniture unrelated = model(solid, 12, 4);
        Block shared = world.getBlockAt(5, 65, 4);
        shared.setType(Material.BARRIER);
        first.addBarrierBlock(shared);
        second.addBarrierBlock(shared);
        Block other = world.getBlockAt(13, 65, 4);
        other.setType(Material.BARRIER);
        unrelated.addBarrierBlock(other);
        BlockBreakEvent event = new BlockBreakEvent(ground(4, 4), player);

        manager.onBlockBreak(event);

        assertFalse(event.isCancelled(), "successful connected removal must not be reported as a blocked break");
        assertFalse(manager.getPlacedFurniture().containsKey(first.getEntityId()));
        assertFalse(manager.getPlacedFurniture().containsKey(second.getEntityId()));
        assertTrue(manager.getPlacedFurniture().containsKey(unrelated.getEntityId()));
        assertEquals(Material.AIR, shared.getType());
        assertEquals(List.of(Material.BARREL, Material.BARREL), droppedItems());
    }

    @Test
    void cancelledFurnitureBreakPreservesTheOriginAndCancelsTheBlockEvent() {
        Furniture crate = place(CRATE, 4, 4);
        listen(FurnitureBreakEvent.class, event -> event.setCancelled(true));
        BlockBreakEvent event = new BlockBreakEvent(ground(4, 4), player);

        manager.onBlockBreak(event);

        assertTrue(event.isCancelled());
        assertTrue(manager.getPlacedFurniture().containsKey(crate.getEntityId()));
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void orphanBarrierBlocksAreClearedButOrdinaryBlocksAreLeftAlone() {
        Block barrier = world.getBlockAt(4, 65, 4);
        barrier.setType(Material.BARRIER);
        BlockBreakEvent barrierBreak = new BlockBreakEvent(barrier, player);
        manager.onBlockBreak(barrierBreak);
        assertTrue(barrierBreak.isCancelled());
        assertEquals(Material.AIR, barrier.getType());

        Block ordinary = ground(8, 4);
        BlockBreakEvent ordinaryBreak = new BlockBreakEvent(ordinary, player);
        manager.onBlockBreak(ordinaryBreak);
        assertFalse(ordinaryBreak.isCancelled());
        assertEquals(Material.STONE, ordinary.getType());
    }

    @Test
    void punchingABarrierRemovesItsFurnitureAndPublishesThePunch() throws Exception {
        Furniture furniture = model(solidType(), 4, 4);
        Block barrier = world.getBlockAt(4, 65, 4);
        barrier.setType(Material.BARRIER);
        furniture.addBarrierBlock(barrier);
        List<FurniturePunchEvent> seen = new ArrayList<>();
        listen(FurniturePunchEvent.class, seen::add);
        PlayerInteractEvent event = left(player, Action.LEFT_CLICK_BLOCK, barrier);

        manager.onPlayerLeftClick(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(List.of(furniture), seen.stream().map(FurniturePunchEvent::getFurniture).toList());
        assertFalse(manager.getPlacedFurniture().containsKey(furniture.getEntityId()));
        assertEquals(List.of(Material.BARREL), droppedItems());
    }

    @Test
    void leftClickingAnUnrelatedBlockLeavesBarrierAndOrdinaryFurnitureIntact() throws Exception {
        Furniture solid = model(solidType(), 4, 4);
        Block barrier = world.getBlockAt(4, 65, 4);
        barrier.setType(Material.BARRIER);
        solid.addBarrierBlock(barrier);
        Furniture crate = place(CRATE, 12, 4);

        manager.onPlayerLeftClick(left(rayPlayer(null), Action.LEFT_CLICK_BLOCK, ground(8, 4)));

        assertEquals(Set.of(solid.getEntityId(), crate.getEntityId()), manager.getPlacedFurniture().keySet());
        assertEquals(Material.BARRIER, barrier.getType());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void airPunchRaycastSelectsInteractionEntitiesAndBreaksTheResolvedFurniture() {
        Furniture stool = place(STOOL, 4, 4);
        Player rayPlayer = rayPlayer(interaction(stool));
        PlayerInteractEvent event = left(rayPlayer, Action.LEFT_CLICK_AIR, null);

        manager.onPlayerLeftClick(event);

        assertFalse(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertEquals(List.of(Material.OAK_LOG), droppedItems());
        assertEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void cancelledPunchesAreCooldownLimitedAndLeaveTheFurnitureIntact() {
        Furniture stool = place(STOOL, 4, 4);
        List<FurniturePunchEvent> seen = new ArrayList<>();
        listen(FurniturePunchEvent.class, event -> { seen.add(event); event.setCancelled(true); });
        EntityDamageByEntityEvent first = damage(player, interaction(stool));
        manager.onEntityDamageByEntity(first);
        manager.pulse(player);
        manager.onEntityDamageByEntity(damage(player, interaction(stool)));

        assertTrue(first.isCancelled());
        assertEquals(1, seen.size());
        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertTrue(droppedItems().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-hit", "block-hit", "unrelated-interaction", "missing-type", "solid-type"})
    void airPunchIgnoresRayHitsThatAreNotInteractibleFurniture(String scenario) throws Exception {
        Furniture stool = place(STOOL, 4, 4);
        Entity target = interaction(stool);
        if (scenario.equals("no-hit")) target = null;
        if (scenario.equals("block-hit")) target = server.getEntity(stool.getEntityId());
        if (scenario.equals("unrelated-interaction")) target = world.spawn(player.getLocation(), Interaction.class);
        if (scenario.equals("missing-type")) FurnitureLoader.getMap().remove(STOOL);
        if (scenario.equals("solid-type")) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("solid", true); config.set("item", "test.stool");
            FurnitureLoader.getMap().put(STOOL, new FurnitureType(STOOL, config));
        }
        manager.onPlayerLeftClick(left(rayPlayer(target), Action.LEFT_CLICK_AIR, null));

        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void deniedAirPunchesAndNonLeftClicksNeverReachFurniture() {
        Furniture stool = place(STOOL, 4, 4);
        PlayerInteractEvent denied = left(player, Action.LEFT_CLICK_AIR, null);
        denied.setUseItemInHand(Event.Result.DENY);
        manager.onPlayerLeftClick(denied);
        manager.onPlayerLeftClick(right(ground(4, 4)));

        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void damageToRootDisplaysBreaksOnlyRegisteredFurnitureAndOnlyForPlayerAttackers() {
        Furniture crate = place(CRATE, 4, 4);
        Entity display = server.getEntity(crate.getEntityId());
        manager.onEntityDamageByEntity(damage(mock(Entity.class), display));
        manager.onEntityDamageByEntity(damage(player, mock(Entity.class)));
        manager.onEntityDamageByEntity(damage(player, world.spawn(player.getLocation(), ItemDisplay.class)));
        assertTrue(manager.getPlacedFurniture().containsKey(crate.getEntityId()));

        EntityDamageByEntityEvent attack = damage(player, display);
        manager.onEntityDamageByEntity(attack);
        assertTrue(attack.isCancelled());
        assertFalse(manager.getPlacedFurniture().containsKey(crate.getEntityId()));
        assertEquals(List.of(Material.BARREL), droppedItems());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "missing-type", "solid-type"})
    void damageToUnresolvableInteractionEntitiesDoesNotDestroyFurniture(String scenario) throws Exception {
        Furniture stool = place(STOOL, 4, 4);
        Interaction target = interaction(stool);
        if (scenario.equals("unknown")) target = world.spawn(player.getLocation(), Interaction.class);
        if (scenario.equals("missing-type")) FurnitureLoader.getMap().remove(STOOL);
        if (scenario.equals("solid-type")) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("solid", true); config.set("item", "test.stool");
            FurnitureLoader.getMap().put(STOOL, new FurnitureType(STOOL, config));
        }

        manager.onEntityDamageByEntity(damage(player, target));

        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void punchingANestedInteractionRemovesOnlyTheNestedPieceAndPersistsItsParent() {
        Furniture parent = place(TABLE, 4, 4);
        Furniture nested = place(CRATE, 8, 4);
        assertTrue(FurnitureAttachmentHandler.attach(parent, "surface", nested, player));
        // Integrations may give nested furniture its own Interaction entity.
        Interaction target = world.spawn(nested.getLoc(), Interaction.class);
        target.getPersistentDataContainer().set(Keys.furnitureEntity(), PersistentDataType.STRING, nested.getEntityId().toString());

        manager.onEntityDamageByEntity(damage(player, target));

        assertTrue(manager.getPlacedFurniture().containsKey(parent.getEntityId()));
        assertTrue(parent.getActiveFurnitureSlots().isEmpty());
        assertTrue(savedIn(0, 0).getFirst().getActiveFurnitureSlots().isEmpty());
        assertEquals(List.of(Material.BARREL), droppedItems());
    }

    @Test
    void pluginRemovalDropsContentsWithoutDroppingTheFurnitureItem() {
        Furniture crate = place(CRATE, 4, 4);
        fill(crate, "lid", new ItemStack(Material.DIAMOND));

        assertFalse(manager.removePlugin(null));
        assertTrue(manager.removePlugin(crate));
        assertFalse(manager.removePlugin(crate));
        assertEquals(List.of(Material.DIAMOND), droppedItems());
        assertTrue(savedIn(0, 0).isEmpty());
    }

    @Test
    void periodicSavingPersistsDirtyStateAndClearsCompletedWork() {
        Furniture crate = place(CRATE, 4, 4);
        crate.setVariables(Map.of("revision", 2));
        manager.markDirty(crate);
        assertTrue(savedIn(0, 0).getFirst().getVariables().isEmpty());

        server.getScheduler().performTicks(1200);

        assertEquals(2, ((Number) savedIn(0, 0).getFirst().getVariables().get("revision")).intValue());
        crate.setVariables(Map.of("revision", 3));
        manager.saveDirtyChunks();
        assertEquals(2, ((Number) savedIn(0, 0).getFirst().getVariables().get("revision")).intValue(),
                "a completed dirty save should not be repeated without another change notification");
    }

    @Test
    void dirtySavingIgnoresInvalidReferencesAndChunksWhoseWorldOrChunkHasUnloaded() {
        manager.markDirty(null);
        manager.markDirty(new Furniture(CRATE, null, UUID.randomUUID()));
        manager.markDirty(new Furniture(CRATE, new Location(null, 0, 0, 0), UUID.randomUUID()));
        manager.persistFurniture(null);
        manager.persistFurniture(new Furniture(CRATE, null, UUID.randomUUID()));
        manager.persistFurniture(new Furniture(CRATE, new Location(null, 0, 0, 0), UUID.randomUUID()));
        World unloaded = mock(World.class);
        when(unloaded.getName()).thenReturn("unloaded-world");
        manager.markDirty(new Furniture(CRATE, new Location(unloaded, 0, 64, 0), UUID.randomUUID()));
        assertFalse(world.isChunkLoaded(40, 40));
        manager.markDirty(new Furniture(CRATE, new Location(world, 640, 64, 640), UUID.randomUUID()));

        manager.saveDirtyChunks();
        manager.saveDirtyChunks();

        assertFalse(world.isChunkLoaded(40, 40), "saving stale dirty work must not load chunks");
        assertFalse(new java.io.File(chunkFolder(), "40_40.json").exists());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedChangesPersistAtTheTopLevelParentsChunk(boolean delayed) throws Exception {
        tableWithInteraction();
        Furniture root = place(TABLE, 15, 4);
        Furniture middle = place(TABLE, 20, 4);
        Furniture leaf = place(CRATE, 24, 4);
        assertTrue(FurnitureAttachmentHandler.attach(middle, "surface", leaf, player));
        assertTrue(FurnitureAttachmentHandler.attach(root, "surface", middle, player));
        assertEquals(0, root.getLoc().getBlockX() >> 4);
        assertEquals(1, leaf.getLoc().getBlockX() >> 4);
        leaf.setVariables(Map.of("revision", 9));

        if (delayed) {
            manager.markDirty(leaf);
            manager.saveDirtyChunks();
        } else {
            manager.persistFurniture(leaf);
        }

        Furniture savedLeaf = savedIn(0, 0).getFirst().getActiveFurnitureSlot("surface").orElseThrow()
                .getNested().getActiveFurnitureSlot("surface").orElseThrow().getNested();
        assertEquals(9, ((Number) savedLeaf.getVariables().getOrDefault("revision", -1)).intValue(),
                "nested state belongs in the top-level parent's saved chunk");
    }

    @Test
    void startupDropsInterruptedCarriesOnceAndRewritesTheirRecords() {
        Furniture interrupted = new Furniture(CRATE, new Location(world, 4.5, 65, 4.5), UUID.randomUUID());
        interrupted.setPersistedCarried(true);
        manager.getDatabase().saveChunk(world.getChunkAt(0, 0), List.of(interrupted));

        manager.loadAlreadyLoadedChunks();

        assertTrue(manager.getPlacedFurniture().isEmpty());
        assertEquals(List.of(Material.BARREL), droppedItems());
        assertTrue(savedIn(0, 0).isEmpty());
        manager.loadAlreadyLoadedChunks();
        assertEquals(List.of(Material.BARREL), droppedItems());
    }

    @Test
    void startupReattachesSavedFurnitureToAnExistingDisplay() {
        Furniture crate = place(CRATE, 4, 4);
        crate.setVariables(Map.of("owner", "Alice"));
        manager.persistFurniture(crate);
        manager.getPlacedFurniture().clear();

        manager.loadAlreadyLoadedChunks();

        Furniture restored = manager.getPlacedFurniture().get(crate.getEntityId());
        assertNotNull(restored);
        assertEquals("Alice", restored.getVariables().get("owner"));
        assertFalse(server.getEntity(restored.getEntityId()).isDead());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void startupSkipsAPendingStaleCarryInAnAlreadyLoadedChunk() {
        Furniture live = place(CRATE, 20, 4);
        Furniture stale = new Furniture(CRATE, new Location(world, 4.5, 65, 4.5), live.getEntityId());
        stale.setPersistedCarried(true);
        world.loadChunk(0, 0);
        manager.getDatabase().saveChunk(world.getName(), 0, 0, List.of(stale));
        blockWrites(0, 0);

        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);

        assertTrue(manager.getPlacedFurniture().containsKey(live.getEntityId()));
        assertTrue(droppedItems().isEmpty());
        allowWrites();
        manager.retryPendingCarriedRecords();
        assertTrue(savedIn(0, 0).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLateNestedPersistenceCallbackAfterParentUnloadPreservesTheSavedTree(boolean delayed) {
        Furniture parent = place(TABLE, 4, 4);
        Furniture nested = place(CRATE, 8, 4);
        assertTrue(FurnitureAttachmentHandler.attach(parent, "surface", nested, player));
        unloadChunk(0, 0);
        assertTrue(manager.getPlacedFurniture().isEmpty());

        if (delayed) {
            manager.markDirty(nested);
            manager.saveDirtyChunks();
        } else {
            manager.persistFurniture(nested);
        }

        assertFalse(savedIn(0, 0).isEmpty(), "a late callback must not erase the unloaded parent's saved tree");
        assertEquals(parent.getEntityId(), savedIn(0, 0).getFirst().getEntityId(),
                "a late callback must not overwrite an unloaded parent's saved tree with an empty chunk");
        assertEquals(nested.getEntityId(), savedIn(0, 0).getFirst().getActiveFurnitureSlot("surface").orElseThrow()
                .getNested().getEntityId());
    }

    @Test
    void loadingAParentWithAMissingNestedDisplayPersistsTheReplacementIdentity() {
        Furniture parent = place(TABLE, 4, 4);
        Furniture nested = place(CRATE, 8, 4);
        assertTrue(FurnitureAttachmentHandler.attach(parent, "surface", nested, player));
        UUID missingDisplay = nested.getEntityId();
        server.getEntity(missingDisplay).remove();
        unloadChunk(0, 0);

        loadChunk(0, 0);

        Furniture live = manager.getPlacedFurniture().get(parent.getEntityId())
                .getActiveFurnitureSlot("surface").orElseThrow().getNested();
        assertNotEquals(missingDisplay, live.getEntityId());
        assertNotNull(server.getEntity(live.getEntityId()));
        assertEquals(live.getEntityId(), savedIn(0, 0).getFirst().getActiveFurnitureSlot("surface")
                .orElseThrow().getNested().getEntityId(), "restored nested identity must be durable before the next unload");
    }

    @Test
    void reconciliationRemovesTaggedOrphansAndPreservesUnrelatedDisplays() {
        world.loadChunk(0, 0);
        ItemDisplay orphan = world.spawn(player.getLocation(), ItemDisplay.class);
        orphan.getPersistentDataContainer().set(Keys.furnitureDisplay(), PersistentDataType.STRING, UUID.randomUUID().toString());
        ItemDisplay unrelated = world.spawn(player.getLocation(), ItemDisplay.class);

        manager.reconcileLoadedChunks();

        assertTrue(orphan.isDead());
        assertFalse(unrelated.isDead());
    }

    @Test
    void pendingRemovalSaveFailureIsReportedAndTheInMemoryRetryStillSucceeds() throws Exception {
        Furniture crate = place(CRATE, 4, 4);
        crate.carry(player);
        blockWrites(0, 0);
        Path pendingTmp = plugin.getDataFolder().toPath().resolve("data/pending-carried-records.json.tmp");
        Files.createDirectory(pendingTmp);
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        Bukkit.getLogger().addHandler(handler);
        try {
            assertTrue(FurniturePlacementHandler.placeCarriedFurniture(player, ground(20, 4), BlockFace.UP,
                    crate, manager.getPlacedFurniture()));
            assertTrue(warnings.stream().anyMatch(message -> message.contains("Could not save pending carried-record removals")));
            allowWrites();
            Files.delete(pendingTmp);
            manager.retryPendingCarriedRecords();
            assertTrue(savedIn(0, 0).isEmpty());
            assertEquals(crate.getEntityId(), savedIn(1, 0).getFirst().getEntityId());
        } finally {
            Bukkit.getLogger().removeHandler(handler);
            Files.deleteIfExists(pendingTmp);
        }
    }

    private PlayerInteractEvent right(Block block) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                block, BlockFace.UP, EquipmentSlot.HAND);
    }

    private PlayerInteractEvent left(Player who, Action action, Block block) {
        return new PlayerInteractEvent(who, action, null, block, BlockFace.UP, EquipmentSlot.HAND);
    }

    private PlayerInteractAtEntityEvent at(Player who, Entity entity) {
        return new PlayerInteractAtEntityEvent(who, entity, new Vector(0, 0.5, 0), EquipmentSlot.HAND);
    }

    @SuppressWarnings("removal")
    private EntityDamageByEntityEvent damage(Entity damager, Entity target) {
        return new EntityDamageByEntityEvent(damager, target, DamageCause.ENTITY_ATTACK, 1.0);
    }

    private Interaction interaction(Furniture furniture) {
        return (Interaction) server.getEntity(furniture.getInteractionEntityId());
    }

    private <T extends Event> void listen(Class<T> type, Consumer<T> action) {
        server.getPluginManager().registerEvent(type, new Listener() {}, EventPriority.NORMAL,
                (ignored, event) -> action.accept(type.cast(event)), plugin);
    }

    private Player rayPlayer(Entity hit) {
        Player rayPlayer = spy(player);
        WorldMock rayWorld = mock(WorldMock.class);
        doReturn(rayWorld).when(rayPlayer).getWorld();
        when(rayWorld.rayTrace(any(Location.class), any(Vector.class), eq(5.0), eq(FluidCollisionMode.NEVER),
                eq(true), eq(0.0), any())).thenAnswer(call -> {
                    Predicate<Entity> filter = call.getArgument(6);
                    assertFalse(filter.test(mock(ItemDisplay.class)));
                    assertTrue(filter.test(mock(Interaction.class)));
                    return hit == null ? null : new RayTraceResult(hit.getLocation().toVector(), hit);
                });
        return rayPlayer;
    }

    private void tableWithInteraction() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                item: test.table
                placement_options: {floor: true}
                interaction: {width: 1, height: 1}
                slots:
                  top:
                    surface:
                      slot-type: furniture
                      interactible: true
                      whitelist: ['*']
                      offset: {x: 1}
                """);
        FurnitureLoader.getMap().put(TABLE, new FurnitureType(TABLE, config));
    }

    private String solidType() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("solid: true\nitem: test.crate");
        FurnitureLoader.getMap().put("manager-solid", new FurnitureType("manager-solid", config));
        return "manager-solid";
    }

    private Furniture model(String type, int x, int z) {
        Location at = new Location(world, x + 0.5, 65, z + 0.5);
        ItemDisplay display = world.spawn(at, ItemDisplay.class);
        Furniture furniture = new Furniture(type, at, display.getUniqueId(), ground(x, z).getLocation(), BlockFace.UP);
        manager.getPlacedFurniture().put(furniture.getEntityId(), furniture);
        return furniture;
    }
}

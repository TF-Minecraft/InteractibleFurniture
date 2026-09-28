package net.tfminecraft.interactiblefurniture.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
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
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.events.FurnitureInteractEvent;
import net.tfminecraft.interactiblefurniture.events.FurniturePunchEvent;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.protection.FurnitureProtection;

/**
 * The owner's crate stands in a claim where the thief is not a member. The
 * guard plugin plays the part of land protection by cancelling the thief's
 * events, the way WorldGuard does.
 */
class ProtectedFurnitureTest extends FurnitureTestServer {
    private PlayerMock thief;
    private Guard guard;
    private final List<Event> furnitureEvents = new ArrayList<>();

    @BeforeEach
    void claim() {
        thief = server.addPlayer();
        standAt(4, 7);
        thief.teleport(player.getLocation());
        guard = new Guard(thief);
        server.getPluginManager().registerEvents(new FurnitureEvents(), plugin);
    }

    private void protect() {
        server.getPluginManager().registerEvents(guard, MockBukkit.createMockPlugin("Guard"));
    }

    private Furniture filledCrate() {
        Furniture crate = place(CRATE, 4, 4);
        fill(crate, "lid", new ItemStack(Material.DIAMOND));
        return crate;
    }

    private <T extends Event> T call(T event) {
        server.getPluginManager().callEvent(event);
        return event;
    }

    private PlayerInteractEvent click(Player who, Action action, Block block) {
        clearCooldowns();
        return call(new PlayerInteractEvent(who, action, null, block, BlockFace.UP, EquipmentSlot.HAND));
    }

    private void assertUntouched(Furniture crate) {
        assertTrue(manager.getPlacedFurniture().containsKey(crate.getEntityId()), "the crate must stay placed");
        assertFalse(crate.isCarried());
        assertEquals(Material.DIAMOND, crate.getActiveSlot("lid").orElseThrow().getCurrentItem().getType());
        assertEquals(List.of(), droppedItems());
    }

    @Test
    void worldGuardLoadsFirstSoItsCancellationsAreSeen() {
        assertTrue(plugin.getPluginMeta().getPluginSoftDependencies().contains("WorldGuard"));
    }

    @Test
    void breakingTheBlockUnderFurnitureInAClaimKeepsItAndItsContents() {
        Furniture crate = filledCrate();
        protect();

        BlockBreakEvent broken = call(new BlockBreakEvent(ground(4, 4), thief));

        assertTrue(broken.isCancelled());
        assertUntouched(crate);
    }

    @Test
    void breakingTheBlockUnderFurnitureElsewhereStillDropsIt() {
        Furniture crate = filledCrate();

        call(new BlockBreakEvent(ground(4, 4), thief));

        assertFalse(manager.getPlacedFurniture().containsKey(crate.getEntityId()));
        assertEquals(List.of(Material.BARREL, Material.DIAMOND), droppedItems());
    }

    @Test
    void aDeniedRightClickCannotCarryFurniture() {
        Furniture crate = filledCrate();
        protect();
        thief.setSneaking(true);

        click(thief, Action.RIGHT_CLICK_BLOCK, ground(4, 4));

        assertUntouched(crate);
        assertEquals(List.of(), furnitureEvents);
    }

    @Test
    void aDeniedRightClickCannotEmptyASlot() {
        Furniture crate = filledCrate();
        protect();

        click(thief, Action.RIGHT_CLICK_BLOCK, ground(4, 4));

        assertUntouched(crate);
        assertTrue(thief.getInventory().getItemInMainHand().getType().isAir());
    }

    @Test
    void anAllowedRightClickStillEmptiesASlot() {
        Furniture crate = filledCrate();

        click(thief, Action.RIGHT_CLICK_BLOCK, ground(4, 4));

        assertTrue(crate.getActiveSlot("lid").isEmpty());
        assertEquals(Material.DIAMOND, thief.getInventory().getItemInMainHand().getType());
    }

    @Test
    void aDeniedLeftClickCannotPunchFurnitureApart() {
        Furniture crate = filledCrate();
        protect();

        click(thief, Action.LEFT_CLICK_BLOCK, ground(4, 4));

        assertUntouched(crate);
        assertEquals(List.of(), furnitureEvents);
    }

    @Test
    void aDeniedEntityClickCannotCarryFurniture() {
        Furniture stool = place(STOOL, 4, 4);
        Interaction interaction = interactionOf(stool);
        protect();
        thief.setSneaking(true);

        clearCooldowns();
        call(new PlayerInteractAtEntityEvent(thief, interaction, new Vector(0, 0.5, 0)));

        assertFalse(stool.isCarried());
        assertEquals(List.of(), furnitureEvents);
    }

    @Test
    @SuppressWarnings("removal")
    void aDeniedEntityHitCannotPunchFurnitureApart() {
        Furniture stool = place(STOOL, 4, 4);
        Interaction interaction = interactionOf(stool);
        protect();

        clearCooldowns();
        call(new EntityDamageByEntityEvent(thief, interaction, DamageCause.ENTITY_ATTACK, 1.0));

        assertTrue(manager.getPlacedFurniture().containsKey(stool.getEntityId()));
        assertEquals(List.of(), droppedItems());
    }

    /*
     * WorldGuard never cancels clicks on plain blocks, so these clicks reach
     * the plugin; its protection rules must still turn the thief away.
     */

    @Test
    void protectionRulesTurnAwayBlockClicksAndPunchesButNotMembers() {
        Furniture crate = filledCrate();
        FurnitureProtection.use(new DenyThief());
        thief.setSneaking(true);

        PlayerInteractEvent carry = click(thief, Action.RIGHT_CLICK_BLOCK, ground(4, 4));
        click(thief, Action.LEFT_CLICK_BLOCK, ground(4, 4));

        assertTrue(carry.useInteractedBlock() == Event.Result.DENY, "the click is claimed so nothing else acts on it");
        assertUntouched(crate);
        assertEquals(List.of(), furnitureEvents);

        player.setSneaking(true);
        click(player, Action.RIGHT_CLICK_BLOCK, ground(4, 4));
        assertTrue(crate.isCarried(), "members can still use their furniture");
    }

    @Test
    void protectionRulesStopACarriedPieceBeingAttachedToFurnitureInAClaim() {
        Furniture table = place(TABLE, 4, 4);
        Furniture own = place(CRATE, 20, 4);
        own.carry(thief);
        FurnitureProtection.use(new DenyThief());

        click(thief, Action.RIGHT_CLICK_BLOCK, ground(4, 4));

        assertTrue(table.getActiveFurnitureSlots().isEmpty());
        assertTrue(own.isCarried());
    }

    private Interaction interactionOf(Furniture furniture) {
        Entity entity = server.getEntity(furniture.getInteractionEntityId());
        assertNotNull(entity, "the stool should have an Interaction entity");
        return (Interaction) entity;
    }

    /** Cancels the thief's events the way land protection would. */
    public static final class Guard implements Listener {
        private final Player denied;

        Guard(Player denied) {
            this.denied = denied;
        }

        // WorldGuard cancels at NORMAL and is registered first; LOW gives the same order here.
        @EventHandler(priority = EventPriority.LOW)
        public void onInteract(PlayerInteractEvent event) {
            if (event.getPlayer().equals(denied)) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInteractAt(PlayerInteractAtEntityEvent event) {
            if (event.getPlayer().equals(denied)) event.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onHit(EntityDamageByEntityEvent event) {
            if (event.getDamager().equals(denied)) event.setCancelled(true);
        }

        // Some protection, such as infestation lures, cancels breaks as late as HIGH.
        @EventHandler(priority = EventPriority.HIGH)
        public void onBreak(BlockBreakEvent event) {
            if (event.getPlayer().equals(denied)) event.setCancelled(true);
        }
    }

    public final class FurnitureEvents implements Listener {
        @EventHandler
        public void onInteract(FurnitureInteractEvent event) {
            furnitureEvents.add(event);
        }

        @EventHandler
        public void onPunch(FurniturePunchEvent event) {
            furnitureEvents.add(event);
        }
    }

    private final class DenyThief implements FurnitureProtection.Rules {
        @Override
        public boolean canInteract(Player who, Furniture furniture) {
            return !who.equals(thief);
        }

        @Override
        public boolean canDamage(Player who, Furniture furniture) {
            return !who.equals(thief);
        }
    }
}

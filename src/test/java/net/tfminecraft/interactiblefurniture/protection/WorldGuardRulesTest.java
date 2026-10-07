package net.tfminecraft.interactiblefurniture.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.ArgumentCaptor;

import com.sk89q.worldguard.bukkit.ProtectionQuery;

import net.tfminecraft.interactiblefurniture.furniture.Furniture;

class WorldGuardRulesTest {
    private ServerMock server;
    private PlayerMock player;
    private Location location;
    private ProtectionQuery query;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        location = new Location(server.addSimpleWorld("world"), 4.5, 65.5, 4.5);
        query = mock(ProtectionQuery.class);
        plugin = MockBukkit.createMockPlugin("InteractibleFurniture");
    }

    @AfterEach
    void tearDown() {
        FurnitureProtection.use(null);
        MockBukkit.unmock();
    }

    private Furniture furnitureWithDisplay() {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class);
        return new Furniture("crate", location, display.getUniqueId());
    }

    @Test
    void clicksAndPunchesAreCheckedAsUsingOrHittingTheDisplay() {
        Furniture furniture = furnitureWithDisplay();
        var display = server.getEntity(furniture.getEntityId());
        when(query.testEntityInteract(player, display)).thenReturn(false);
        when(query.testEntityDamage(player, display)).thenReturn(true);
        WorldGuardRules rules = new WorldGuardRules(query);

        assertFalse(rules.canInteract(player, furniture));
        assertTrue(rules.canDamage(player, furniture));
    }

    @Test
    void withoutALoadedDisplayTheFurnitureBlockIsChecked() {
        Furniture furniture = new Furniture("crate", location, UUID.randomUUID());
        when(query.testBlockInteract(eq(player), any(Block.class))).thenReturn(false);
        when(query.testBlockBreak(eq(player), any(Block.class))).thenReturn(false);
        WorldGuardRules rules = new WorldGuardRules(query);

        assertFalse(rules.canInteract(player, furniture));
        assertFalse(rules.canDamage(player, furniture));

        ArgumentCaptor<Block> checked = ArgumentCaptor.forClass(Block.class);
        verify(query).testBlockInteract(eq(player), checked.capture());
        assertEquals(location.getBlock().getLocation(), checked.getValue().getLocation());
    }

    @Test
    void hookingWorldGuardSendsFurnitureChecksToItsProtectionQuery() {
        MockBukkit.createMockPlugin("WorldGuard");
        Furniture furniture = furnitureWithDisplay();
        when(query.testEntityInteract(any(), any())).thenReturn(false);
        when(query.testEntityDamage(any(), any())).thenReturn(false);

        FurnitureProtection.hook(plugin, () -> new WorldGuardRules(query));

        assertFalse(FurnitureProtection.canInteract(player, furniture));
        assertFalse(FurnitureProtection.canDamage(player, furniture));
    }

    @Test
    void withoutWorldGuardFurnitureIsLeftUnprotected() {
        FurnitureProtection.use(new WorldGuardRules(query));

        FurnitureProtection.hook(plugin);

        Furniture furniture = furnitureWithDisplay();
        assertTrue(FurnitureProtection.canInteract(player, furniture));
        assertTrue(FurnitureProtection.canDamage(player, furniture));
        verifyNoInteractions(query);
    }

    @Test
    void aWorldGuardThatCannotBeQueriedLeavesFurnitureUsable() {
        MockBukkit.createMockPlugin("WorldGuard");

        // WorldGuard's classes cannot start without a real WorldGuard, as when its API is broken.
        FurnitureProtection.hook(plugin);

        Furniture furniture = furnitureWithDisplay();
        assertTrue(FurnitureProtection.canInteract(player, furniture));
        assertTrue(FurnitureProtection.canDamage(player, furniture));
    }
    @Test
    void defaultHookUsesTheLiveWorldGuardProtectionQuery() {
        var worldGuard=mock(com.sk89q.worldguard.bukkit.WorldGuardPlugin.class);
        when(worldGuard.createProtectionQuery()).thenReturn(query);
        try (var singleton=org.mockito.Mockito.mockStatic(com.sk89q.worldguard.bukkit.WorldGuardPlugin.class)) {
            singleton.when(com.sk89q.worldguard.bukkit.WorldGuardPlugin::inst).thenReturn(worldGuard);
            Furniture furniture=furnitureWithDisplay();
            when(query.testEntityInteract(eq(player),any())).thenReturn(true);
            assertTrue(WorldGuardRules.create().canInteract(player,furniture));
            verify(worldGuard).createProtectionQuery();
        }
    }

}

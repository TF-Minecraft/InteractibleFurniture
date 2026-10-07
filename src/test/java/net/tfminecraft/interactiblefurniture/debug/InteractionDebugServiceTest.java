package net.tfminecraft.interactiblefurniture.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;

class InteractionDebugServiceTest extends DebugTestWorld {
    private InteractionDebugService service;

    @BeforeEach
    void createServiceAndVisibleFurniture() throws Exception {
        service = new InteractionDebugService(plugin);
        Furniture furniture = furniture(type(""), 0, 64, 0);
        furniture.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
    }

    @Test
    void playersShareOneTimerAndOnlyEnabledPlayersReceiveParticles() {
        Player second = addPlayer();
        assertFalse(service.isEnabled(player));
        assertFalse(service.disable(player));
        assertTrue(service.enable(player));
        assertFalse(service.enable(player));
        assertTrue(service.isEnabled(player));
        assertEquals(1, scheduled.size());
        Scheduled timer = scheduled.getFirst();
        assertEquals(10L, timer.delay);
        assertEquals(10L, timer.period);
        assertTrue(particles(player).isEmpty(), "enabling waits for the first scheduled tick");

        timer.tick();
        assertBox(particles(player), BLUE, 150, 0, 63, 0, 1, 64, 1);
        assertTrue(particles(second).isEmpty());

        assertTrue(service.enable(second));
        assertEquals(1, scheduled.size(), "all viewers use the same timer");
        timer.tick();
        assertEquals(300, particles(player).size());
        assertBox(particles(second), BLUE, 150, 0, 63, 0, 1, 64, 1);

        assertTrue(service.disable(player));
        assertFalse(service.disable(player));
        assertFalse(timer.cancelled);
        timer.tick();
        assertEquals(300, particles(player).size(), "disabled viewers stop receiving particles");
        assertEquals(300, particles(second).size());

        assertTrue(service.disable(second));
        assertTrue(timer.cancelled);
        assertFalse(service.isEnabled(second));
        assertTrue(service.enable(player));
        assertEquals(2, scheduled.size());
        scheduled.get(1).tick();
        assertEquals(450, particles(player).size(), "debug can restart after the last viewer leaves");
    }

    @Test
    void stopClearsEveryViewerAndAllowsAFreshTimerToStart() {
        service.stop();
        Player second = addPlayer();
        service.enable(player);
        service.enable(second);
        scheduled.getFirst().tick();

        service.stop();
        service.stop();

        assertTrue(scheduled.getFirst().cancelled);
        assertFalse(service.isEnabled(player));
        assertFalse(service.isEnabled(second));
        assertFalse(service.disable(player));
        assertTrue(service.enable(second));
        assertEquals(2, scheduled.size());
        scheduled.get(1).tick();
        assertBox(particles(player), BLUE, 150, 0, 63, 0, 1, 64, 1);
        assertEquals(300, particles(second).size());
    }

    @Test
    void quittingOnlyCancelsTheTimerWhenTheLastEnabledViewerLeaves() {
        Player second = addPlayer();
        Player unrelated = addPlayer();
        service.enable(player);
        service.enable(second);

        service.onQuit(new PlayerQuitEvent(unrelated, Component.empty(), QuitReason.DISCONNECTED));
        assertTrue(service.isEnabled(player));
        assertTrue(service.isEnabled(second));
        service.onQuit(new PlayerQuitEvent(player, Component.empty(), QuitReason.DISCONNECTED));
        assertFalse(service.isEnabled(player));
        assertFalse(scheduled.getFirst().cancelled);
        scheduled.getFirst().tick();
        assertTrue(particles(player).isEmpty());
        assertTrue(particles(unrelated).isEmpty());
        assertBox(particles(second), BLUE, 150, 0, 63, 0, 1, 64, 1);

        service.onQuit(new PlayerQuitEvent(second, Component.empty(), QuitReason.DISCONNECTED));
        assertFalse(service.isEnabled(second));
        assertTrue(scheduled.getFirst().cancelled);
    }

    @Test
    void ticksRemoveMissingAndOfflinePlayersWhileStillRenderingOnlineViewers() {
        Player offline = addPlayer();
        Player online = addPlayer();
        service.enable(player);
        service.enable(offline);
        service.enable(online);
        players.remove(player.getUniqueId());
        when(offline.isOnline()).thenReturn(false);

        Scheduled timer = scheduled.getFirst();
        timer.tick();

        assertFalse(service.isEnabled(player));
        assertFalse(service.isEnabled(offline));
        assertTrue(service.isEnabled(online));
        assertFalse(timer.cancelled);
        assertTrue(particles(player).isEmpty());
        assertTrue(particles(offline).isEmpty());
        assertBox(particles(online), BLUE, 150, 0, 63, 0, 1, 64, 1);

        when(online.isOnline()).thenReturn(false);
        timer.tick();
        assertFalse(service.isEnabled(online));
        assertTrue(timer.cancelled);
        assertEquals(150, particles(online).size());
    }
}

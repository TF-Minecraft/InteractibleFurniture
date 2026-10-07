package net.tfminecraft.interactiblefurniture.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.interactiblefurniture.furniture.data.DisplayData;

/** Public event payloads and mutation/cancellation contract consumed by other plugins. */
class FurnitureEventsTest {
    @Test
    void eventsRetainTheirPayloadAndHaveIndependentHandlerLists() {
        Player player = mock(Player.class);
        Furniture parent = mock(Furniture.class), child = mock(Furniture.class);
        SlotDefinition slot = mock(SlotDefinition.class);
        ItemStack original = new ItemStack(Material.STONE), replacement = new ItemStack(Material.DIAMOND);
        Vector click = new Vector(1, 2, 3);
        var place = new FurniturePlaceEvent(parent, player);
        assertSame(parent, place.getFurniture()); assertSame(player, place.getPlayer());
        assertTrue(place.hasPlayer()); assertFalse(new FurniturePlaceEvent(parent, null).hasPlayer());
        var broken = new FurnitureBreakEvent(parent, player);
        assertSame(parent, broken.getFurniture()); assertSame(player, broken.getPlayer());
        assertTrue(broken.hasPlayer()); assertFalse(new FurnitureBreakEvent(parent, null).hasPlayer());
        var punch = new FurniturePunchEvent(player, parent);
        assertSame(parent, punch.getFurniture()); assertSame(player, punch.getPlayer());
        var interact = new FurnitureInteractEvent(player, parent, slot, click);
        assertSame(parent, interact.getFurniture()); assertSame(slot, interact.getHitSlot());
        assertSame(click, interact.getClickPoint());
        var general = new FurnitureInteractEvent(player, parent);
        assertNull(general.getHitSlot()); assertNull(general.getClickPoint());
        var add = new FurnitureSlotItemAddEvent(player, parent, slot, original);
        assertSame(parent, add.getFurniture()); assertSame(slot, add.getSlot()); assertSame(original, add.getItem());
        assertNull(add.getDisplayData());
        var display = new DisplayData(); display.setyPos(1.25f);
        add.setItem(replacement); add.setDisplayData(display);
        assertSame(replacement, add.getItem()); assertSame(display, add.getDisplayData());
        var take = new FurnitureSlotItemTakeEvent(player, parent, slot, original);
        assertSame(parent, take.getFurniture()); assertSame(slot, take.getSlot()); assertSame(original, take.getItem());
        take.setItem(replacement); assertSame(replacement, take.getItem());
        var attach = new FurnitureSlotFurnitureAddEvent(player, parent, slot, child);
        assertSame(parent, attach.getFurniture()); assertSame(slot, attach.getSlot()); assertSame(child, attach.getNested());
        var detach = new FurnitureSlotFurnitureTakeEvent(player, parent, slot, child);
        assertSame(parent, detach.getFurniture()); assertSame(slot, detach.getSlot()); assertSame(child, detach.getNested());

        Event[] events = {place, broken, punch, interact, add, take, attach, detach};
        HandlerList[] handlers = {FurniturePlaceEvent.getHandlerList(), FurnitureBreakEvent.getHandlerList(),
                FurniturePunchEvent.getHandlerList(), FurnitureInteractEvent.getHandlerList(),
                FurnitureSlotItemAddEvent.getHandlerList(), FurnitureSlotItemTakeEvent.getHandlerList(),
                FurnitureSlotFurnitureAddEvent.getHandlerList(), FurnitureSlotFurnitureTakeEvent.getHandlerList()};
        var unique = new HashSet<HandlerList>();
        for (int i = 0; i < events.length; i++) {
            assertSame(handlers[i], events[i].getHandlers());
            assertTrue(unique.add(handlers[i]), "event types must not share handlers");
            Cancellable event = (Cancellable) events[i];
            assertFalse(event.isCancelled()); event.setCancelled(true); assertTrue(event.isCancelled());
            event.setCancelled(false); assertFalse(event.isCancelled());
        }
    }
}

package net.tfminecraft.interactiblefurniture.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.debug.InteractionDebugService;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.interactiblefurniture.furniture.SlotType;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;

/** Command contracts: permissions, messages, selection, and delegated actions. */
class IfCommandCoverageTest {
    private static final String RELOAD = "interactiblefurniture.reload";
    private static final String DEBUG = "interactiblefurniture.debug";
    private static final String USAGE = "Usage: /if reload | /if nested attach | /if nested detach | /if debug <on|off>";

    private final IfCommand command = new IfCommand();
    private final Map<UUID, Furniture> placed = new LinkedHashMap<>();
    // Bukkit Location holds its world weakly; fixture worlds must remain loaded for the test.
    private final java.util.List<World> worlds = new java.util.ArrayList<>();
    private InteractibleFurniture plugin;
    private FurnitureManager manager;
    private InteractionDebugService debug;
    private Player player;
    private World world;
    private MockedStatic<InteractibleFurniture> instance;
    private MockedStatic<FurnitureAttachmentHandler> attachments;

    @BeforeEach
    void setUp() {
        plugin = mock(InteractibleFurniture.class);
        manager = mock(FurnitureManager.class);
        debug = mock(InteractionDebugService.class);
        player = mock(Player.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(player.hasPermission(RELOAD)).thenReturn(true);
        when(player.hasPermission(DEBUG)).thenReturn(true);
        when(plugin.getFurnitureManager()).thenReturn(manager);
        when(plugin.getInteractionDebugService()).thenReturn(debug);
        when(manager.getPlacedFurniture()).thenReturn(placed);
        when(manager.getByCarrier(player)).thenAnswer(call -> placed.values().stream()
                .filter(f -> f.isCarried() && f.getHolder() == player)
                .findFirst().orElse(null));
        instance = mockStatic(InteractibleFurniture.class);
        instance.when(InteractibleFurniture::getInstance).thenReturn(plugin);
        attachments = mockStatic(FurnitureAttachmentHandler.class);
    }

    @AfterEach
    void tearDown() {
        attachments.close();
        instance.close();
    }

    @Test
    void emptyAndUnknownCommandsShowUsage() {
        CommandSender empty = mock(CommandSender.class);
        execute(empty);
        verify(empty).sendMessage(USAGE);

        execute(player, "unknown");
        verify(player).sendMessage(USAGE);
        verify(plugin, never()).reloadAll();
    }

    @Test
    void reloadRequiresPermissionBeforeCallingThePlugin() {
        when(player.hasPermission(RELOAD)).thenReturn(false);
        execute(player, "reload");
        verify(player).sendMessage("You do not have permission to reload InteractibleFurniture.");
        verify(plugin, never()).reloadAll();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consoleReloadReportsTheActualOutcome(boolean success) {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RELOAD)).thenReturn(true);
        when(plugin.reloadAll()).thenReturn(success);

        execute(console, "ReLoAd");

        verify(plugin).reloadAll();
        verify(console).sendMessage(success
                ? "Reloaded InteractibleFurniture configs." : "Reload failed. Check console.");
    }

    @Test
    void nestedRequiresPermissionAndAPlayer() {
        when(player.hasPermission(RELOAD)).thenReturn(false);
        execute(player, "nested", "attach");
        verify(player).sendMessage("You do not have permission to use nested furniture commands.");

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(RELOAD)).thenReturn(true);
        execute(console, "nested", "detach");
        verify(console).sendMessage("Players only.");
        attachments.verifyNoInteractions();
    }

    @Test
    void missingAndUnknownNestedOperationsShowUsage() {
        execute(player, "nested");
        execute(player, "nested", "unknown");
        verify(player, org.mockito.Mockito.times(2))
                .sendMessage("Usage: /if nested attach | /if nested detach");
        attachments.verifyNoInteractions();
    }

    @Test
    void debugRequiresItsOwnPermissionAndAPlayer() {
        when(player.hasPermission(DEBUG)).thenReturn(false);
        execute(player, "debug", "on");
        verify(player).sendMessage("You do not have permission to use furniture debug.");

        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(DEBUG)).thenReturn(true);
        execute(console, "debug", "on");
        verify(console).sendMessage("Players only.");
        verify(debug, never()).enable(player);
    }

    @Test
    void missingAndUnknownDebugOperationsShowUsage() {
        execute(player, "debug");
        execute(player, "debug", "unknown");
        verify(player, org.mockito.Mockito.times(2)).sendMessage("Usage: /if debug <on|off>");
        verify(debug, never()).enable(player);
        verify(debug, never()).disable(player);
    }

    @Test
    void debugCanBeEnabledAndDisabledWithoutRepeatedTransitions() {
        execute(player, "DeBuG", "ON");
        verify(debug).enable(player);
        verify(player).sendMessage("Furniture interaction debug enabled.");

        when(debug.isEnabled(player)).thenReturn(true);
        execute(player, "debug", "on");
        verify(debug).enable(player);
        verify(player).sendMessage("Furniture interaction debug is already on.");

        execute(player, "debug", "OFF");
        verify(debug).disable(player);
        verify(player).sendMessage("Furniture interaction debug disabled.");

        when(debug.isEnabled(player)).thenReturn(false);
        execute(player, "debug", "off");
        verify(debug).disable(player);
        verify(player).sendMessage("Furniture interaction debug is already off.");
    }

    @Test
    void attachRequiresACarriedPieceAndAnEligibleNearbyParent() {
        execute(player, "nested", "attach");
        verify(player).sendMessage("Carry a furniture piece first (shift-right-click).");

        carryPiece();
        execute(player, "nested", "attach");
        verify(player).sendMessage("No nearby parent furniture found.");
        attachments.verifyNoInteractions();
    }

    @Test
    void attachRejectsMissingTypesAndParentsWithoutAnEmptyFurnitureSlot() {
        carryPiece();
        Furniture parent = furniture("table", world, 1, 0);
        execute(player, "nested", "attach");
        verify(player).sendMessage("No empty furniture slot on nearest parent.");

        slots(parent, slot("item", SlotType.ITEM), slot("occupied", SlotType.FURNITURE));
        parent.getOrCreatePlacedFurnitureSlot("occupied").setNested(mock(Furniture.class));
        execute(player, "nested", "attach");
        verify(player, org.mockito.Mockito.times(2)).sendMessage("No empty furniture slot on nearest parent.");
        attachments.verifyNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void attachChoosesTheNearestEligibleParentAndAlphabeticallyFirstEmptyFurnitureSlot(boolean success) {
        Furniture carried = carryPiece();
        Furniture carriedByAnotherPlayer = furniture("other-carry", world, 0, 0);
        Player other = mock(Player.class);
        when(other.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        carriedByAnotherPlayer.carry(other);
        furniture("attached", world, 0, 0).setAttachment(UUID.randomUUID(), "top");
        furniture("missing-world", null, 0, 0);
        furniture("different-world", mock(World.class), 0, 0);
        furniture("outside-radius", world, 9, 0);
        furniture("initial-farther", world, 7, 0);
        Furniture parent = furniture("table", world, 3, 0);
        furniture("later-farther", world, 5, 0);
        furniture("equally-near", world, -3, 0);
        slots(parent, slot("z-last", SlotType.FURNITURE), slot("a-item", SlotType.ITEM),
                slot("a-full", SlotType.FURNITURE), slot("b-first", SlotType.FURNITURE));
        parent.getOrCreatePlacedFurnitureSlot("a-full").setNested(mock(Furniture.class));
        attachments.when(() -> FurnitureAttachmentHandler.attachFromCarried(parent, "b-first", carried, player))
                .thenReturn(success);

        execute(player, "NeStEd", "AtTaCh");

        attachments.verify(() -> FurnitureAttachmentHandler.attachFromCarried(parent, "b-first", carried, player));
        attachments.verifyNoMoreInteractions();
        verify(player).sendMessage(success
                ? "Attached crate to table slot b-first." : "Attach failed.");
    }

    @Test
    void attachAcceptsTheSearchRadiusBoundary() {
        Furniture carried = carryPiece();
        Furniture parent = furniture("table", world, 8, 0);
        slots(parent, slot("top", SlotType.FURNITURE));

        execute(player, "nested", "attach");

        attachments.verify(() -> FurnitureAttachmentHandler.attachFromCarried(parent, "top", carried, player));
    }

    @Test
    void detachRequiresNearbyNestedFurniture() {
        furniture("empty", world, 0, 0);

        execute(player, "nested", "detach");

        verify(player).sendMessage("No nearby parent with nested furniture found.");
        attachments.verifyNoInteractions();
    }

    @Test
    void detachHandlesAnEmptyActiveSlotWithoutTryingToDetachIt() {
        Furniture parent = furniture("table", world, 1, 0);
        parent.getOrCreatePlacedFurnitureSlot("empty");

        execute(player, "nested", "detach");

        verify(player).sendMessage("No nested furniture to detach.");
        attachments.verifyNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void detachChoosesTheFirstOccupiedSlotAndCarriesOnlyASuccessfullyDetachedPiece(boolean success) {
        furniture("closer-but-empty", world, 0, 0);
        Furniture parent = furniture("table", world, 1, 0);
        Furniture nested = new Furniture("crate", new Location(world, 1, 64, 0), UUID.randomUUID());
        nested.setAttachment(parent.getEntityId(), "b-first");
        parent.getOrCreatePlacedFurnitureSlot("z-last").setNested(mock(Furniture.class));
        parent.getOrCreatePlacedFurnitureSlot("a-empty");
        parent.getOrCreatePlacedFurnitureSlot("b-first").setNested(nested);
        attachments.when(() -> FurnitureAttachmentHandler.detach(parent, "b-first", player))
                .thenAnswer(call -> {
                    if (!success) return null;
                    // The attachment handler owns these transitions; the command owns starting carry.
                    parent.removeActiveFurnitureSlot("b-first");
                    nested.clearAttachment();
                    placed.put(nested.getEntityId(), nested);
                    return nested;
                });

        execute(player, "nested", "DeTaCh");

        attachments.verify(() -> FurnitureAttachmentHandler.detach(parent, "b-first", player));
        attachments.verifyNoMoreInteractions();
        assertEquals(success, nested.isCarried());
        assertEquals(!success, nested.isAttached());
        assertEquals(!success, parent.hasActiveFurnitureSlot("b-first"));
        verify(player).sendMessage(success
                ? "Detached crate from table slot b-first." : "Detach failed.");
    }

    @Test
    void detachWhileAlreadyCarryingPreservesBothTheCarryAndTheNestedPiece() {
        Furniture carried = carryPiece();
        Furniture parent = furniture("table", world, 1, 0);
        Furniture nested = new Furniture("nested-crate", new Location(world, 1, 64, 0), UUID.randomUUID());
        nested.setAttachment(parent.getEntityId(), "top");
        parent.getOrCreatePlacedFurnitureSlot("top").setNested(nested);
        attachments.when(() -> FurnitureAttachmentHandler.detach(parent, "top", player))
                .thenAnswer(call -> {
                    parent.removeActiveFurnitureSlot("top");
                    nested.clearAttachment();
                    placed.put(nested.getEntityId(), nested);
                    return nested;
                });

        execute(player, "nested", "detach");

        assertTrue(carried.isCarried());
        assertSame(player, carried.getHolder());
        assertFalse(nested.isCarried(), "detaching must not start a second simultaneous carry");
        assertTrue(nested.isAttached());
        assertSame(nested, parent.getActiveFurnitureSlot("top").orElseThrow().getNested());
        attachments.verifyNoInteractions();
        verify(player).sendMessage("Place the furniture you are carrying before detaching another piece.");
    }

    @ParameterizedTest
    @MethodSource("completions")
    void tabCompletionHonorsPermissionsAndTheCurrentPrefix(
            boolean reloadPermission, boolean debugPermission, String[] args, List<String> expected) {
        when(player.hasPermission(RELOAD)).thenReturn(reloadPermission);
        when(player.hasPermission(DEBUG)).thenReturn(debugPermission);

        assertEquals(expected, command.onTabComplete(player, null, "if", args));
    }

    private static Stream<Arguments> completions() {
        return Stream.of(
                Arguments.of(true, true, new String[]{""}, List.of("reload", "nested", "debug")),
                Arguments.of(false, false, new String[]{""}, List.of()),
                Arguments.of(true, false, new String[]{""}, List.of("reload", "nested")),
                Arguments.of(false, true, new String[]{""}, List.of("debug")),
                Arguments.of(true, true, new String[]{"N"}, List.of("nested")),
                Arguments.of(true, true, new String[]{"missing"}, List.of()),
                Arguments.of(true, false, new String[]{"NeStEd", ""}, List.of("attach", "detach")),
                Arguments.of(true, false, new String[]{"nested", "A"}, List.of("attach")),
                Arguments.of(false, true, new String[]{"nested", ""}, List.of()),
                Arguments.of(false, true, new String[]{"DeBuG", ""}, List.of("on", "off")),
                Arguments.of(false, true, new String[]{"debug", "OF"}, List.of("off")),
                Arguments.of(true, false, new String[]{"debug", ""}, List.of()),
                Arguments.of(true, true, new String[]{"reload", ""}, List.of()),
                Arguments.of(true, true, new String[]{}, List.of()),
                Arguments.of(true, true, new String[]{"nested", "attach", ""}, List.of()));
    }

    private void execute(CommandSender sender, String... args) {
        assertTrue(command.onCommand(sender, null, "if", args), "the command should consume its own usage/errors");
    }

    private Furniture furniture(String id, World in, double x, double z) {
        if (in != null) worlds.add(in);
        Furniture furniture = spy(new Furniture(id, new Location(in, x, 64, z), UUID.randomUUID()));
        doReturn(null).when(furniture).getType();
        placed.put(furniture.getEntityId(), furniture);
        return furniture;
    }

    private Furniture carryPiece() {
        Furniture carried = furniture("crate", world, 0, 0);
        carried.carry(player);
        return carried;
    }

    private void slots(Furniture parent, SlotDefinition... definitions) {
        FurnitureType type = mock(FurnitureType.class);
        Map<String, SlotDefinition> slots = new LinkedHashMap<>();
        for (SlotDefinition slot : definitions) slots.put(slot.getId(), slot);
        when(type.getSlots()).thenReturn(slots);
        doReturn(type).when(parent).getType();
    }

    private static SlotDefinition slot(String id, SlotType type) {
        return new SlotDefinition(id, 0, 0, 0, null, List.of("*"), null, null, null, type, true, true);
    }
}

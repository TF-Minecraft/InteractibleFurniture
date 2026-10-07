package net.tfminecraft.interactiblefurniture.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.interactiblefurniture.furniture.SlotType;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;

class InteractionDebugRendererTest extends DebugTestWorld {
    @Test
    void rendersTheLiveInteractionDimensionsAndLocation() throws Exception {
        Furniture furniture = furniture(type("interaction: {width: 1, height: 1}"), 0, 64, 0);
        Interaction interaction = mock(Interaction.class);
        UUID id = UUID.randomUUID();
        furniture.setInteractionEntityId(id);
        entities.put(id, interaction);
        when(interaction.getInteractionWidth()).thenReturn(2f);
        when(interaction.getInteractionHeight()).thenReturn(1f);
        when(interaction.getLocation()).thenReturn(new Location(world, 2, 65, 3));

        InteractionDebugRenderer.renderFor(player);

        assertBox(particles(player), CYAN, 342, 1, 65, 2, 3, 66, 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "missing", "wrong-type"})
    void fallsBackToConfiguredInteractionBoundsWhenTheEntityIsUnavailable(String entityState) throws Exception {
        Furniture furniture = furniture(type("""
                interaction:
                  width: 1
                  height: 0.5
                  offset: {x: 0.5, y: 1, z: -0.5}
                """), 4, 64, 6);
        if (!entityState.equals("absent")) {
            UUID id = UUID.randomUUID();
            furniture.setInteractionEntityId(id);
            if (entityState.equals("wrong-type")) entities.put(id, mock(ItemDisplay.class));
        }

        InteractionDebugRenderer.renderFor(player);

        assertBox(particles(player), CYAN, 110, 4, 65, 5, 5, 65.5, 6);
    }

    @Test
    void solidFurnitureRendersLoadedBarriersInsteadOfInteractionOrOriginBounds() throws Exception {
        Furniture furniture = furniture(type("solid: true\ninteraction: {width: 2, height: 2}"), 0, 64, 0);
        furniture.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
        furniture.addBarrierBlock(block(2, 63, 2));
        furniture.getBarrierBlocks().add(null);
        furniture.addBarrierBlock(block(32, 63, 0));
        when(chunk(2, 0).isLoaded()).thenReturn(false);

        InteractionDebugRenderer.renderFor(player);

        assertBox(particles(player), RED, 150, 2, 63, 2, 3, 64, 3);
    }

    @Test
    void nonSolidFurnitureSkipsAnOriginInAnUnloadedChunk() throws Exception {
        Furniture furniture = furniture(type(""), 0, 64, 0);
        furniture.setOriginBlock(new Location(world, 32, 63, 0), BlockFace.UP);
        when(chunk(2, 0).isLoaded()).thenReturn(false);

        InteractionDebugRenderer.renderFor(player);

        assertTrue(particles(player).isEmpty());
    }

    @Test
    void slotBoxesUseTheDisplayRotationAndDistinguishInteractibleSlots() throws Exception {
        FurnitureType type = type("");
        type.getSlots().put("active", slot("active", new Vector(1, 0, 0), true));
        type.getSlots().put("decorative", slot("decorative", new Vector(0, 1, 0), false));
        Furniture furniture = furniture(type, 4, 64, 6);
        ItemDisplay display = mock(ItemDisplay.class);
        when(display.getTransformation()).thenReturn(new Transformation(new Vector3f(),
                new Quaternionf().rotateY((float) (Math.PI / 2)), new Vector3f(1), new Quaternionf()));
        entities.put(furniture.getEntityId(), display);

        InteractionDebugRenderer.renderFor(player);

        List<Dust> particles = particles(player);
        assertEquals(48, particles.size());
        assertBox(particles.stream().filter(d -> d.color().equals(GREEN)).toList(), GREEN,
                24, 3.85, 63.85, 4.85, 4.15, 64.15, 5.15);
        assertBox(particles.stream().filter(d -> d.color().equals(GRAY)).toList(), GRAY,
                24, 3.85, 64.85, 5.85, 4.15, 65.15, 6.15);
    }

    @Test
    void missingOrWrongDisplayEntitiesStillShowTheOriginButNoSlotBoxes() throws Exception {
        FurnitureType type = type("");
        type.getSlots().put("top", slot("top", new Vector(), true));
        Furniture first = furniture(type, 0, 64, 0);
        first.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
        Furniture second = furniture(type, 2, 64, 0);
        second.setOriginBlock(new Location(world, 2, 63, 0), BlockFace.UP);
        entities.put(second.getEntityId(), mock(Interaction.class));

        InteractionDebugRenderer.renderFor(player);

        assertEquals(300, particles(player).size());
        assertTrue(particles(player).stream().allMatch(d -> d.color().equals(BLUE)));
    }

    @Test
    void nestedFurnitureIsRenderedOnceEvenWhenAlsoPresentInTheRootMap() throws Exception {
        FurnitureType type = type("");
        Furniture parent = furniture(type, 0, 64, 0);
        parent.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
        Furniture nested = furniture(type, 2, 64, 0);
        nested.setOriginBlock(new Location(world, 2, 63, 0), BlockFace.UP);
        nested.setAttachment(parent.getEntityId(), "top");
        parent.getOrCreatePlacedFurnitureSlot("top").setNested(nested);
        parent.getOrCreatePlacedFurnitureSlot("empty");

        InteractionDebugRenderer.renderFor(player);

        List<Dust> particles = particles(player);
        assertEquals(300, particles.size());
        assertBox(particles.stream().filter(d -> d.x() < 2).toList(), BLUE,
                150, 0, 63, 0, 1, 64, 1);
        assertBox(particles.stream().filter(d -> d.x() >= 2).toList(), BLUE,
                150, 2, 63, 0, 3, 64, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-furniture", "null-id", "null-location", "null-world",
            "different-world", "unloaded", "too-far", "unknown-type", "carried"})
    void ignoresFurnitureThatCannotBeRenderedWithoutHidingValidFurniture(String reason) throws Exception {
        FurnitureType type = type("");
        Furniture visible = furniture(type, 0, 64, 0);
        visible.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
        Furniture rejected = furniture(type, 4, 64, 0);
        rejected.setOriginBlock(new Location(world, 4, 63, 0), BlockFace.UP);
        switch (reason) {
            case "null-furniture" -> manager.getPlacedFurniture().put(rejected.getEntityId(), null);
            case "null-id" -> rejected.setEntityId(null);
            case "null-location" -> rejected.setLoc(null);
            case "null-world" -> rejected.setLoc(new Location(null, 4, 64, 0));
            case "different-world" -> rejected.setLoc(new Location(mock(World.class), 4, 64, 0));
            case "unloaded" -> {
                rejected.setLoc(new Location(world, -1, 64, 0));
                when(chunk(-1, 0).isLoaded()).thenReturn(false);
            }
            case "too-far" -> rejected.setLoc(new Location(world, 16.01, 64, 0));
            case "unknown-type" -> {
                manager.getPlacedFurniture().remove(rejected.getEntityId());
                rejected = new Furniture("undefined", new Location(world, 4, 64, 0), UUID.randomUUID());
                rejected.setOriginBlock(new Location(world, 4, 63, 0), BlockFace.UP);
                manager.getPlacedFurniture().put(rejected.getEntityId(), rejected);
            }
            case "carried" -> {
                manager.start();
                rejected.carry(player);
                assertTrue(rejected.isCarried());
            }
            default -> throw new AssertionError(reason);
        }

        InteractionDebugRenderer.renderFor(player);

        assertBox(particles(player), BLUE, 150, 0, 63, 0, 1, 64, 1);
    }

    @Test
    void includesFurnitureExactlyAtTheSearchRadius() throws Exception {
        Furniture furniture = furniture(type(""), 16, 64, 0);
        furniture.setOriginBlock(new Location(world, 16, 63, 0), BlockFace.UP);

        InteractionDebugRenderer.renderFor(player);

        assertBox(particles(player), BLUE, 150, 16, 63, 0, 17, 64, 1);
    }

    @Test
    void aPlayerWithoutAWorldGetsNoParticles() throws Exception {
        Furniture furniture = furniture(type(""), 0, 64, 0);
        furniture.setOriginBlock(new Location(world, 0, 63, 0), BlockFace.UP);
        when(player.getLocation()).thenReturn(new Location(null, 0, 64, 0));

        InteractionDebugRenderer.renderFor(player);

        assertTrue(particles(player).isEmpty());
    }

    private static SlotDefinition slot(String id, Vector offset, boolean interactible) {
        return new SlotDefinition(id, 0, 0, 0, offset, List.of("*"), null, null, null,
                SlotType.ITEM, interactible, true);
    }
}

/** Real furniture and geometry, with only server/client interfaces replaced. */
abstract class DebugTestWorld {
    static final Color CYAN = Color.fromRGB(0, 255, 255);
    static final Color GREEN = Color.fromRGB(0, 255, 0);
    static final Color GRAY = Color.fromRGB(160, 160, 160);
    static final Color RED = Color.fromRGB(255, 0, 0);
    static final Color BLUE = Color.fromRGB(0, 120, 255);
    @TempDir Path dataFolder;
    InteractibleFurniture plugin;
    FurnitureManager manager;
    Player player;
    World world;
    final Map<UUID, Entity> entities = new HashMap<>();
    final Map<UUID, Player> players = new HashMap<>();
    final List<Scheduled> scheduled = new ArrayList<>();
    private final Map<String, Chunk> chunks = new HashMap<>();
    private final Map<Player, List<Dust>> output = new HashMap<>();
    private Map<String, FurnitureType> previousTypes;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<InteractibleFurniture> instance;

    @BeforeEach
    void createWorld() {
        previousTypes = new HashMap<>(FurnitureLoader.getMap());
        FurnitureLoader.getMap().clear();
        plugin = mock(InteractibleFurniture.class);
        manager = new FurnitureManager();
        when(plugin.getFurnitureManager()).thenReturn(manager);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        world = mock(World.class);
        when(world.getName()).thenReturn("debug-world");
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> chunk(call.getArgument(0), call.getArgument(1)));
        when(world.getChunkAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return chunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        });
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            Scheduled task = new Scheduled(call.getArgument(1), call.getArgument(2), call.getArgument(3));
            scheduled.add(task);
            return task.handle;
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(call -> entities.get(call.getArgument(0)));
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> players.get(call.getArgument(0)));
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        instance = mockStatic(InteractibleFurniture.class);
        instance.when(InteractibleFurniture::getInstance).thenReturn(plugin);
        player = addPlayer();
    }

    @AfterEach
    void closeWorld() {
        instance.close();
        bukkit.close();
        FurnitureLoader.getMap().clear();
        FurnitureLoader.getMap().putAll(previousTypes);
    }

    Player addPlayer() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(player.isOnline()).thenReturn(true);
        List<Dust> particles = new ArrayList<>();
        output.put(player, particles);
        players.put(id, player);
        doAnswer(call -> {
            assertEquals(Particle.DUST, call.getArgument(0));
            assertEquals(1, (int) call.getArgument(4));
            for (int i = 5; i <= 8; i++) assertEquals(0.0, (double) call.getArgument(i));
            Particle.DustOptions dust = assertInstanceOf(Particle.DustOptions.class, call.getArgument(9));
            assertEquals(1f, dust.getSize());
            assertEquals(true, call.getArgument(10));
            particles.add(new Dust(call.getArgument(1), call.getArgument(2), call.getArgument(3), dust.getColor()));
            return null;
        }).when(player).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyBoolean());
        return player;
    }

    FurnitureType type(String yaml) throws InvalidConfigurationException {
        String id = "debug-" + UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        FurnitureType type = new FurnitureType(id, config);
        FurnitureLoader.getMap().put(id, type);
        return type;
    }

    Furniture furniture(FurnitureType type, double x, double y, double z) {
        Furniture furniture = new Furniture(type.getId(), new Location(world, x, y, z), UUID.randomUUID());
        manager.getPlacedFurniture().put(furniture.getEntityId(), furniture);
        return furniture;
    }

    Chunk chunk(int x, int z) {
        return chunks.computeIfAbsent(x + ":" + z, key -> {
            Chunk chunk = mock(Chunk.class);
            when(chunk.isLoaded()).thenReturn(true);
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(x);
            when(chunk.getZ()).thenReturn(z);
            return chunk;
        });
    }

    Block block(int x, int y, int z) {
        Block block = mock(Block.class);
        Chunk chunk = chunk(x >> 4, z >> 4);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        when(block.getChunk()).thenReturn(chunk);
        return block;
    }

    List<Dust> particles(Player player) {
        return output.get(player);
    }

    static void assertBox(List<Dust> particles, Color color, int count,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        assertEquals(count, particles.size(), "particle count for the six box faces");
        assertTrue(particles.stream().allMatch(d -> d.color().equals(color)));
        assertEquals(minX, particles.stream().mapToDouble(Dust::x).min().orElseThrow(), 0.000001);
        assertEquals(maxX, particles.stream().mapToDouble(Dust::x).max().orElseThrow(), 0.000001);
        assertEquals(minY, particles.stream().mapToDouble(Dust::y).min().orElseThrow(), 0.000001);
        assertEquals(maxY, particles.stream().mapToDouble(Dust::y).max().orElseThrow(), 0.000001);
        assertEquals(minZ, particles.stream().mapToDouble(Dust::z).min().orElseThrow(), 0.000001);
        assertEquals(maxZ, particles.stream().mapToDouble(Dust::z).max().orElseThrow(), 0.000001);
        for (Dust p : particles) {
            assertTrue(p.x() >= minX - 0.000001 && p.x() <= maxX + 0.000001);
            assertTrue(p.y() >= minY - 0.000001 && p.y() <= maxY + 0.000001);
            assertTrue(p.z() >= minZ - 0.000001 && p.z() <= maxZ + 0.000001);
            assertTrue(near(p.x(), minX) || near(p.x(), maxX) || near(p.y(), minY)
                    || near(p.y(), maxY) || near(p.z(), minZ) || near(p.z(), maxZ), "dust must lie on a box face");
        }
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 0.000001;
    }

    record Dust(double x, double y, double z, Color color) {}

    static final class Scheduled {
        final Runnable callback;
        final long delay;
        final long period;
        final BukkitTask handle = mock(BukkitTask.class);
        boolean cancelled;

        Scheduled(Runnable callback, long delay, long period) {
            this.callback = callback;
            this.delay = delay;
            this.period = period;
            doAnswer(call -> {
                cancelled = true;
                return null;
            }).when(handle).cancel();
        }

        void tick() {
            assertFalse(cancelled, "cancelled tasks must not run again");
            callback.run();
        }
    }
}

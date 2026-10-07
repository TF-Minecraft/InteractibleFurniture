package net.tfminecraft.interactiblefurniture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.MockedStatic;

import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.furniture.PlacedSlot;
import net.tfminecraft.interactiblefurniture.furniture.data.ModelData;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurniturePlacementHandler;
import net.tfminecraft.interactiblefurniture.protection.FurnitureProtection;
import net.tfminecraft.tlibs.TLibs;

/**
 * Runs the real plugin on a mock server with two test furniture types.
 *
 * TLibs is stubbed so each test item path maps to one material: the crate is
 * a barrel, the table a crafting table, and slot contents are plain items.
 */
@ExtendWith(FurnitureTestServer.FailUnimplemented.class)
public abstract class FurnitureTestServer {
    protected static final String CRATE = "test_crate";
    protected static final String TABLE = "test_table";
    protected static final String STOOL = "test_stool";
    protected static final int GROUND_Y = 64;

    private static final Map<String, Material> ITEMS = Map.of(
            "test.crate", Material.BARREL,
            "test.table", Material.CRAFTING_TABLE,
            "test.stool", Material.OAK_LOG);

    protected ServerMock server;
    protected InteractibleFurniture plugin;
    protected FurnitureManager manager;
    protected WorldMock world;
    protected PlayerMock player;
    private MockedStatic<TLibs> tlibs;
    private MockedStatic<InteractibleFurniture> instance;
    private MockedStatic<FurniturePlacementHandler> displays;
    private final List<File> blockedWrites = new ArrayList<>();

    @BeforeEach
    void startServer() throws InvalidConfigurationException {
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin("TLibs");
        tlibs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
        when(TLibs.getItemAPI().getCreator().getItemFromPath(anyString()))
                .thenAnswer(call -> new ItemStack(ITEMS.getOrDefault(call.getArgument(0, String.class), Material.PAPER)));
        when(TLibs.getItemAPI().getChecker().checkItemWithPath(any(), anyString()))
                .thenAnswer(call -> {
                    ItemStack item = call.getArgument(0);
                    Material expected = ITEMS.get(call.getArgument(1, String.class));
                    return item != null && expected != null && item.getType() == expected;
                });
        // JavaPlugin.getPlugin only accepts Paper's plugin class loader, which MockBukkit does not use.
        instance = mockStatic(InteractibleFurniture.class, CALLS_REAL_METHODS);
        instance.when(InteractibleFurniture::getInstance)
                .thenAnswer(call -> Bukkit.getPluginManager().getPlugin("InteractibleFurniture"));
        // MockBukkit does not implement Display#setBillboard, so spawn furniture displays without it.
        displays = mockStatic(FurniturePlacementHandler.class, CALLS_REAL_METHODS);
        displays.when(() -> FurniturePlacementHandler.spawnDisplayAt(
                        any(FurnitureType.class), any(Location.class), anyFloat(), any(BlockFace.class),
                        any(ModelData.class)))
                .thenAnswer(call -> {
                    Location at = call.getArgument(1);
                    return at.getWorld().spawn(at, ItemDisplay.class,
                            display -> FurniturePlacementHandler.tagDisplay(display, display.getUniqueId()));
                });

        plugin = MockBukkit.load(InteractibleFurniture.class);
        manager = plugin.getFurnitureManager();
        registerType(CRATE, """
                item: test.crate
                placement_options:
                  floor: true
                carry: true
                pickup: true
                model:
                  display: ITEM_DISPLAY
                  model: test.crate_model
                slots:
                  top:
                    location: "1,1,1"
                    lid:
                      interactible: true
                      whitelist:
                        - "*"
                """);
        registerType(TABLE, """
                item: test.table
                placement_options:
                  floor: true
                model:
                  display: ITEM_DISPLAY
                  model: test.table_model
                slots:
                  top:
                    location: "1,1,1"
                    surface:
                      slot-type: furniture
                      interactible: true
                      whitelist:
                        - "test.crate"
                """);
        // Used through its Interaction entity rather than the block under it.
        registerType(STOOL, """
                item: test.stool
                placement_options:
                  floor: true
                carry: true
                model:
                  display: ITEM_DISPLAY
                  model: test.stool_model
                interaction:
                  width: 1.0
                  height: 1.0
                """);
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        player.teleport(new Location(world, 0.5, GROUND_Y + 1, 0.5));
    }

    @AfterEach
    void stopServer() {
        try {
            allowWrites();
            MockBukkit.unmock();
        } finally {
            FurnitureProtection.use(null);
            FurnitureLoader.getMap().clear();
            displays.close();
            instance.close();
            tlibs.close();
        }
    }

    /** MockBukkit aborts, rather than fails, a test that reaches an unimplemented method. */
    static final class FailUnimplemented
            implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable thrown) throws Throwable {
            throw failed(thrown);
        }

        @Override
        public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable thrown)
                throws Throwable {
            throw failed(thrown);
        }

        private static Throwable failed(Throwable thrown) {
            if (thrown instanceof UnimplementedOperationException) {
                return new AssertionError("MockBukkit does not implement a call this test needs", thrown);
            }
            return thrown;
        }
    }

    private void registerType(String id, String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        FurnitureLoader.getMap().put(id, new FurnitureType(id, config));
        // Restart tests must reload the same definitions from disk, just like the server.
        YamlConfiguration file = new YamlConfiguration();
        file.set(id, config);
        try {
            file.save(new File(plugin.getDataFolder(), "furniture/" + id + ".yml"));
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot save test furniture definition", e);
        }
    }

    protected static ItemStack itemFor(String furnitureId) {
        return new ItemStack(ITEMS.get(FurnitureLoader.getByString(furnitureId).getItemPath()));
    }

    /** Places furniture on top of a stone block at the given column, as a player would. */
    protected Furniture place(String furnitureId, int x, int z) {
        Block ground = ground(x, z);
        ItemStack held = itemFor(furnitureId);
        player.getInventory().setItemInMainHand(held);
        assertTrue(FurniturePlacementHandler.handlePlacement(
                player, ground, BlockFace.UP, held, manager.getPlacedFurniture()));
        // Placing used up the item; a real server leaves an empty hand, MockBukkit a stack of 0.
        player.getInventory().setItemInMainHand(null);
        Furniture placed = manager.getPlacedFurniture().values().stream()
                .filter(f -> !f.isCarried() && f.isOriginBlock(ground))
                .findFirst()
                .orElse(null);
        assertNotNull(placed, "furniture was not placed");
        return placed;
    }

    protected Block ground(int x, int z) {
        Block ground = world.getBlockAt(x, GROUND_Y, z);
        ground.setType(Material.STONE);
        return ground;
    }

    /**
     * Puts an item in a slot with its display stand and saves it, the state a
     * player filling the slot leaves (MockBukkit cannot spawn the stand the
     * plugin's way).
     */
    protected void fill(Furniture furniture, String slotId, ItemStack item) {
        PlacedSlot slot = furniture.getOrCreatePlacedSlot(slotId);
        slot.setModel(item);
        ItemDisplay stand = world.spawn(furniture.getLoc(), ItemDisplay.class, display -> display.setItemStack(item));
        slot.setDisplayStandId(stand.getUniqueId());
        manager.persistFurniture(furniture);
    }

    /** Lets the next click through; the manager ignores clicks within 200 ms of the last. */
    protected void clearCooldowns() {
        try {
            Field cooldown = FurnitureManager.class.getDeclaredField("cooldown");
            cooldown.setAccessible(true);
            ((Map<?, ?>) cooldown.get(manager)).clear();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    protected void standAt(int x, int z) {
        player.teleport(new Location(world, x + 0.5, GROUND_Y + 1, z + 0.5));
    }

    protected void unloadChunk(int chunkX, int chunkZ) {
        manager.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(chunkX, chunkZ)));
    }

    protected void loadChunk(int chunkX, int chunkZ) {
        manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(chunkX, chunkZ), false));
    }

    /** Item entities on the ground in the test world, by material name. */
    protected List<Material> droppedItems() {
        return droppedItems(world);
    }

    protected static List<Material> droppedItems(World in) {
        return in.getEntitiesByClass(Item.class).stream()
                .map(item -> item.getItemStack().getType())
                .sorted(Comparator.comparing(Material::name))
                .toList();
    }

    /** The folder holding this world's chunk files. */
    protected File chunkFolder() {
        return new File(plugin.getDataFolder(), "data/chunks/" + world.getName());
    }

    /**
     * Makes a chunk's files impossible to rewrite, as a full or failing disk
     * would: every write goes through a temporary file, and a folder stands
     * where it would go.
     */
    protected void blockWrites(int chunkX, int chunkZ) {
        for (String name : temporaryFiles(chunkX, chunkZ)) {
            File blocker = new File(chunkFolder(), name);
            assertTrue(blocker.mkdirs(), "could not block " + name);
            blockedWrites.add(blocker);
        }
    }

    protected void allowWrites() {
        blockedWrites.forEach(File::delete);
        blockedWrites.clear();
    }

    private static List<String> temporaryFiles(int chunkX, int chunkZ) {
        String base = chunkX + "_" + chunkZ + ".json";
        return List.of(base + ".tmp", base + ".bak.tmp");
    }

    protected List<Furniture> savedIn(int chunkX, int chunkZ) {
        return manager.getDatabase().loadChunk(world.getName(), chunkX, chunkZ);
    }
}

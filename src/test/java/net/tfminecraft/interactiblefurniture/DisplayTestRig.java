package net.tfminecraft.interactiblefurniture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;
import org.mockito.MockedStatic;
import net.tfminecraft.interactiblefurniture.furniture.*;
import net.tfminecraft.interactiblefurniture.loaders.*;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;
import net.tfminecraft.tlibs.TLibs;

/** Real furniture/inventories with recording boundaries for Paper's unsupported Display operations. */
public abstract class DisplayTestRig {
    protected ServerMock server;
    protected PlayerMock player;
    protected World world;
    protected InteractibleFurniture plugin;
    protected FurnitureManager manager;
    protected final Map<UUID, Entity> entities = new HashMap<>();
    protected final Map<UUID, Furniture> placed = new HashMap<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<InteractibleFurniture> instance;
    private MockedStatic<TLibs> tlibs;

    @BeforeEach
    void startDisplayRig() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        world = mock(World.class);
        when(world.getName()).thenReturn("display-test");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getEntities()).thenAnswer(c -> new ArrayList<>(entities.values()));
        when(world.spawnEntity(any(Location.class), eq(EntityType.ITEM_DISPLAY)))
                .thenAnswer(c -> display(c.getArgument(0)));
        when(world.spawn(any(Location.class), eq(ItemDisplay.class), any(java.util.function.Consumer.class)))
                .thenAnswer(c -> {
                    ItemDisplay display = display(c.getArgument(0));
                    java.util.function.Consumer<ItemDisplay> initialize = c.getArgument(2);
                    initialize.accept(display);
                    return display;
                });
        manager = mock(FurnitureManager.class);
        when(manager.getPlacedFurniture()).thenReturn(placed);
        plugin = mock(InteractibleFurniture.class);
        when(plugin.getName()).thenReturn("InteractibleFurniture");
        when(plugin.namespace()).thenReturn("interactiblefurniture");
        when(plugin.getFurnitureManager()).thenReturn(manager);
        when(plugin.getServer()).thenReturn(server);
        instance = mockStatic(InteractibleFurniture.class);
        instance.when(InteractibleFurniture::getInstance).thenReturn(plugin);
        tlibs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
        when(TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenAnswer(c -> new ItemStack(Material.PAPER));
        when(TLibs.getItemAPI().getChecker().getAsStringPath(any())).thenReturn("v.stone");
        when(TLibs.getItemAPI().getChecker().checkItemWithPath(any(), anyString()))
                .thenAnswer(c -> ((ItemStack)c.getArgument(0)).getType().name().equalsIgnoreCase(c.getArgument(1)));
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(c -> entities.get(c.getArgument(0)));
    }

    @AfterEach
    void closeDisplayRig() {
        if (bukkit != null) bukkit.close();
        if (tlibs != null) tlibs.close();
        if (instance != null) instance.close();
        FurnitureLoader.getMap().clear();
        SoundLoader.getMap().clear();
        MockBukkit.unmock();
    }

    protected FurnitureType type(String name, String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration(); cfg.loadFromString(yaml);
        FurnitureType type = new FurnitureType(name, cfg);
        FurnitureLoader.getMap().put(name, type);
        return type;
    }

    protected Furniture furniture(String name) {
        var at = new Location(world, 0, 64, 0);
        Furniture f = new Furniture(name, at, display(at).getUniqueId());
        placed.put(f.getEntityId(), f);
        return f;
    }

    protected ItemDisplay display(Location at) {
        ItemDisplay display = mock(ItemDisplay.class);
        UUID id = UUID.randomUUID();
        AtomicReference<Location> location = new AtomicReference<>(at.clone());
        AtomicReference<Transformation> transform = new AtomicReference<>(
                new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()));
        AtomicReference<ItemStack> item = new AtomicReference<>();
        when(display.getUniqueId()).thenReturn(id);
        when(display.getWorld()).thenAnswer(c -> location.get().getWorld());
        when(display.getLocation()).thenAnswer(c -> location.get().clone());
        when(display.teleport(any(Location.class))).thenAnswer(c -> {location.set(c.getArgument(0)); return true;});
        when(display.getTransformation()).thenAnswer(c -> transform.get());
        doAnswer(c -> {transform.set(c.getArgument(0)); return null;}).when(display).setTransformation(any());
        doAnswer(c -> {item.set(c.getArgument(0)); return null;}).when(display).setItemStack(any());
        when(display.getItemStack()).thenAnswer(c -> item.get());
        when(display.getPersistentDataContainer()).thenReturn(new PersistentDataContainerMock());
        doAnswer(c -> {entities.remove(id); return null;}).when(display).remove();
        entities.put(id, display);
        return display;
    }
}

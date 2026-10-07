package net.tfminecraft.interactiblefurniture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.loaders.SoundLoader;

class ConfigurationReloadTest extends FurnitureTestServer {
    @Test
    void malformedFurnitureFilePreservesBothRegistriesAndReportsFailure() throws Exception {
        var furniture = Map.copyOf(FurnitureLoader.getMap());
        var sounds = Map.copyOf(SoundLoader.getMap());
        Files.writeString(plugin.getDataFolder().toPath().resolve("furniture/broken.yml"), "broken: [\n");

        assertFalse(plugin.reloadAll());
        assertEquals(furniture, FurnitureLoader.getMap());
        assertEquals(sounds, SoundLoader.getMap());
        assertSame(furniture.get(CRATE), FurnitureLoader.getByString(CRATE));
    }

    @Test
    void invalidFurnitureValuesDoNotPublishPartiallyLoadedDefinitions() throws Exception {
        var furniture = Map.copyOf(FurnitureLoader.getMap());
        var sounds = Map.copyOf(SoundLoader.getMap());
        Files.writeString(plugin.getDataFolder().toPath().resolve("furniture/broken.yml"), """
                broken:
                  slots:
                    top:
                      location: 'not-a-number,1,1'
                """);

        assertFalse(plugin.reloadAll());
        assertEquals(furniture, FurnitureLoader.getMap());
        assertEquals(sounds, SoundLoader.getMap());
    }

    @Test
    void brokenOrMissingSoundsLeaveFurnitureAndSoundsUntouched() throws Exception {
        var furniture = Map.copyOf(FurnitureLoader.getMap());
        var sounds = Map.copyOf(SoundLoader.getMap());
        Path file = plugin.getDataFolder().toPath().resolve("sounds.yml");
        Files.writeString(file, "sounds: [\n");
        assertFalse(plugin.reloadAll());
        assertEquals(furniture, FurnitureLoader.getMap());
        assertEquals(sounds, SoundLoader.getMap());
        Files.delete(file);
        assertFalse(plugin.reloadAll());
        assertEquals(furniture, FurnitureLoader.getMap());
        assertEquals(sounds, SoundLoader.getMap());
    }

    @Test
    void successfulReloadReplacesDefinitionsWhileKeepingRegistryObjects() throws Exception {
        var furniture = FurnitureLoader.getMap();
        var sounds = SoundLoader.getMap();
        Path folder = plugin.getDataFolder().toPath().resolve("furniture");
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.writeString(folder.resolve("new.yml"), "new_type:\n  item: test.crate\n");
        Files.writeString(folder.resolve("ignored.txt"), "not yaml: [");
        Files.createDirectory(folder.resolve("ignored.yml"));
        Files.writeString(plugin.getDataFolder().toPath().resolve("sounds.yml"), "sounds: ['new sound.new']\n");

        assertTrue(plugin.reloadAll());
        assertSame(furniture, FurnitureLoader.getMap());
        assertSame(sounds, SoundLoader.getMap());
        assertEquals(java.util.Set.of("new_type"), furniture.keySet());
        assertEquals(Map.of("new", "sound.new"), sounds);
    }
    @Test
    void missingFurnitureDirectoryRejectsReloadWithoutClearingDefinitions() throws Exception {
        Path directory=plugin.getDataFolder().toPath().resolve("furniture"), backup=directory.resolveSibling("furniture.saved");
        var before=Map.copyOf(FurnitureLoader.getMap()); Files.move(directory,backup);
        try { assertFalse(plugin.reloadAll()); assertEquals(before,FurnitureLoader.getMap()); }
        finally { Files.move(backup,directory); }
        assertNotNull(plugin.getInteractionDebugService()); plugin.registerListeners();
        assertSame(manager,plugin.getFurnitureManager());
    }
    @Test
    void missingCommandMetadataLogsAnActionableStartupError() {
        var startup=org.mockito.Mockito.spy(plugin); var logger=org.mockito.Mockito.mock(java.util.logging.Logger.class);
        org.mockito.Mockito.doReturn(null).when(startup).getCommand("if");
        org.mockito.Mockito.doReturn(logger).when(startup).getLogger();
        try {
            startup.onEnable();
            org.mockito.Mockito.verify(logger).severe("Command 'if' missing from plugin.yml");
        } finally {
            // The spy is not a registered plugin, so MockBukkit shutdown cannot unregister its listeners.
            org.bukkit.event.HandlerList.unregisterAll(startup);
        }
    }

}

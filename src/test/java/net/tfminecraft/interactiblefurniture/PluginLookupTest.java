package net.tfminecraft.interactiblefurniture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class PluginLookupTest {
    @Test void resolvesThePluginFromItsOwningPluginClassLoader() {
        InteractibleFurniture plugin = mock(InteractibleFurniture.class);
        try (var loader = mockStatic(JavaPlugin.class)) {
            loader.when(() -> JavaPlugin.getPlugin(InteractibleFurniture.class)).thenReturn(plugin);
            assertSame(plugin, InteractibleFurniture.getInstance());
        }
    }
}

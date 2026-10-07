package net.tfminecraft.interactiblefurniture.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public class SoundLoader implements LoaderInterface{
    private static final HashMap<String, String> map = new HashMap<>();

    public static HashMap<String, String> getMap() {
        return map;
    }

    @Override
    public void load(File configFile) {
        map.putAll(read(configFile));
    }

    /** Read sounds without changing the live registry. */
    public static Map<String, String> read(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalArgumentException("Cannot load " + configFile + ": " + e.getMessage(), e);
        }

        Map<String, String> loaded = new HashMap<>();
        for(String s : config.getStringList("sounds")) {
            String[] parts = s.split(" ");
            if(parts.length != 2) continue;
            loaded.put(parts[0], parts[1]);
        }
        return loaded;
    }

    public static boolean has(String path) {
        return map.containsKey(path);
    }

    public static String getByString(String id) {
        return map.get(id);
    }
}

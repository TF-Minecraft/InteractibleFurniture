package net.tfminecraft.interactiblefurniture;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.interactiblefurniture.command.IfCommand;
import net.tfminecraft.interactiblefurniture.debug.InteractionDebugService;
import net.tfminecraft.interactiblefurniture.manager.FurnitureManager;
import net.tfminecraft.interactiblefurniture.protection.FurnitureProtection;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.loaders.SoundLoader;

public class InteractibleFurniture extends JavaPlugin{
    private final FurnitureManager furnitureManager = new FurnitureManager();
    private final InteractionDebugService interactionDebugService = new InteractionDebugService(this);

    @Override
    public void onEnable() {
        createConfigs();
        loadConfigs();
        // register our furniture manager
        getServer().getPluginManager().registerEvents(furnitureManager, this);
        getServer().getPluginManager().registerEvents(interactionDebugService, this);
        FurnitureProtection.hook(this);
        furnitureManager.start();
        furnitureManager.loadAlreadyLoadedChunks();

        IfCommand ifCommand = new IfCommand();
        var ifCmd = getCommand("if");
        if (ifCmd != null) {
            ifCmd.setExecutor(ifCommand);
            ifCmd.setTabCompleter(ifCommand);
        } else {
            getLogger().severe("Command 'if' missing from plugin.yml");
        }

        getLogger().info("InteractibleFurniture has been enabled!");
    }

    @Override
    public void onDisable() {
        interactionDebugService.stop();
        furnitureManager.deleteCarried();
        furnitureManager.saveAllLoadedChunks();
        // Pending removals are only kept in memory; this is their last chance.
        furnitureManager.retryPendingCarriedRecords();
        getLogger().info("InteractibleFurniture has been disabled.");
    }

    public void registerListeners() {
        // kept for compatibility; specific listeners are registered in onEnable
    }

    public void createConfigs() {
    String[] files = {
        "furniture/example.yml",
        "furniture/magic.yml",
        "furniture/cooking.yml",
        "furniture/shelf.yml",
        "sounds.yml"
        };
		for(String s : files) {
			File newConfigFile = new File(getDataFolder(), s);
	        if (!newConfigFile.exists()) {
	        	newConfigFile.getParentFile().mkdirs();
	            saveResource(s, false);
	        }
		}
	}

    public void loadConfigs() {
        File folder = new File(getDataFolder(), "furniture");
        File[] files = folder.listFiles();
        if (files == null) {
            throw new IllegalArgumentException("Cannot read furniture directory " + folder);
        }
        Map<String, FurnitureType> furniture = new HashMap<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".yml")) {
                furniture.putAll(FurnitureLoader.read(file));
            }
        }
        Map<String, String> sounds = SoundLoader.read(new File(getDataFolder(), "sounds.yml"));
        // Publish only after every file has loaded. Keep map identities used by integrations.
        FurnitureLoader.getMap().clear();
        FurnitureLoader.getMap().putAll(furniture);
        SoundLoader.getMap().clear();
        SoundLoader.getMap().putAll(sounds);
    }

    public boolean reloadAll() {
        try {
            loadConfigs();
            getLogger().info("InteractibleFurniture configs reloaded ("
                    + net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader.getMap().size() + " types).");
            return true;
        } catch (Exception ex) {
            getLogger().severe("Reload failed: " + ex.getMessage());
            return false;
        }
    }

    public static InteractibleFurniture getInstance() {
        return JavaPlugin.getPlugin(InteractibleFurniture.class);
    }

    public FurnitureManager getFurnitureManager() {
        return furnitureManager;
    }

    public InteractionDebugService getInteractionDebugService() {
        return interactionDebugService;
    }
}

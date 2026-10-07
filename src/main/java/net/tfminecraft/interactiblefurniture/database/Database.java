package net.tfminecraft.interactiblefurniture.database;

import com.google.gson.*;
import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedFurnitureSlot;
import net.tfminecraft.interactiblefurniture.furniture.PlacedSlot;
import net.tfminecraft.interactiblefurniture.furniture.SlotType;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;
import net.tfminecraft.interactiblefurniture.furniture.data.ModelData;
import net.tfminecraft.interactiblefurniture.enums.Display;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles saving and loading furniture data to disk.
 * Each chunk's furniture is stored in its own JSON file:
 *   data/chunks/<world>/<chunkX>_<chunkZ>.json
 */
public class Database {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private final File chunkDataFolder;

    public Database() {
        File baseFolder = new File(InteractibleFurniture.getInstance().getDataFolder(), "data/chunks");
        if (!baseFolder.exists()) baseFolder.mkdirs();
        this.chunkDataFolder = baseFolder;
    }

    // ------------------------------------------------------------------------
    //  CHUNK STRUCTURE
    // ------------------------------------------------------------------------

    public record ChunkKey(String world, int x, int z) {
        public static ChunkKey fromLocation(Location loc) {
            return new ChunkKey(
                    loc.getWorld().getName(),
                    loc.getBlockX() >> 4,
                    loc.getBlockZ() >> 4
            );
        }

        public static ChunkKey fromChunk(Chunk c) {
            return new ChunkKey(c.getWorld().getName(), c.getX(), c.getZ());
        }

        File toFile(File root) {
            File worldDir = new File(root, world);
            if (!worldDir.exists()) worldDir.mkdirs();
            return new File(worldDir, x + "_" + z + ".json");
        }
    }

    // ------------------------------------------------------------------------
    //  SAVE / LOAD CHUNK
    // ------------------------------------------------------------------------

    public void saveChunk(String world, int chunkX, int chunkZ, Collection<Furniture> furniture) {
        File file = new ChunkKey(world, chunkX, chunkZ).toFile(chunkDataFolder);

        List<Map<String, Object>> serialized = furniture.stream()
                .map(this::serializeFurniture)
                .toList();

        writeChunkFile(file, Map.of("furniture", serialized), true, "chunk " + world + " " + chunkX + "," + chunkZ);
    }

    /**
     * Writes a chunk file through a temporary file so a failed write never
     * leaves it half written. When {@code keepBackup} is set, the previous file
     * is copied to {@code .bak} first.
     */
    private boolean writeChunkFile(File file, Object content, boolean keepBackup, String label) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        File bak = new File(file.getParentFile(), file.getName() + ".bak");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            GSON.toJson(content, writer);
        } catch (IOException e) {
            InteractibleFurniture.getInstance().getLogger()
                    .warning("Failed to save furniture for " + label);
            e.printStackTrace();
            return false;
        }

        try {
            if (keepBackup && file.exists()) {
                Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            InteractibleFurniture.getInstance().getLogger()
                    .warning("Failed to replace furniture file for " + label);
            e.printStackTrace();
            return false;
        }
    }

    public List<Furniture> loadChunk(String world, int chunkX, int chunkZ) {
        File file = new ChunkKey(world, chunkX, chunkZ).toFile(chunkDataFolder);
        File bak = new File(file.getParentFile(), file.getName() + ".bak");

        List<Furniture> loaded = tryReadChunkFile(file);
        if (loaded != null) return loaded;

        loaded = tryReadChunkFile(bak);
        if (loaded != null) {
            InteractibleFurniture.getInstance().getLogger()
                    .warning("Loaded furniture backup for chunk " + world + " " + chunkX + "," + chunkZ);
            return loaded;
        }
        return List.of();
    }

    public void visitSavedFurniture(Consumer<Furniture> visitor) {
        if (visitor == null || chunkDataFolder == null || !chunkDataFolder.isDirectory()) {
            return;
        }
        File[] worlds = chunkDataFolder.listFiles(File::isDirectory);
        if (worlds == null) {
            return;
        }
        for (File worldDir : worlds) {
            String[] names = worldDir.list((dir, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
            if (names == null) {
                continue;
            }
            Set<String> chunkNames = new LinkedHashSet<>();
            for (String name : names) {
                chunkNames.add(name.endsWith(".bak") ? name.substring(0, name.length() - 4) : name);
            }
            for (String name : chunkNames) {
                File file = new File(worldDir, name);
                List<Furniture> loaded = tryReadChunkFile(file);
                if (loaded == null) {
                    loaded = tryReadChunkFile(new File(worldDir, name + ".bak"));
                }
                if (loaded == null) {
                    continue;
                }
                for (Furniture furniture : loaded) {
                    if (furniture == null || furniture.isCarried() || furniture.isPersistedCarried()) {
                        continue;
                    }
                    visitor.accept(furniture);
                }
            }
        }
    }

    private List<Furniture> tryReadChunkFile(File file) {
        JsonObject root = readChunkJson(file);
        if (root == null) return null;

        try {
            List<Furniture> list = new ArrayList<>();
            for (JsonElement el : furnitureRecords(root)) {
                if (!el.isJsonObject()) continue;
                Furniture f = deserializeFurniture(el.getAsJsonObject());
                if (f != null) list.add(f);
            }
            return list;
        } catch (Exception e) {
            InteractibleFurniture.getInstance().getLogger()
                    .warning("Failed to parse furniture file " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private JsonObject readChunkJson(File file) {
        if (file == null || !file.exists()) return null;

        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) return null;
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            InteractibleFurniture.getInstance().getLogger()
                    .warning("Failed to parse furniture file " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * A chunk file's JSON, or null if loading the chunk would not use it: it
     * is missing, does not parse, or its records cannot be read.
     */
    private JsonObject readLoadableChunkJson(File file) {
        return tryReadChunkFile(file) != null ? readChunkJson(file) : null;
    }

    private static JsonArray furnitureRecords(JsonObject root) {
        if (!root.has("furniture") || !root.get("furniture").isJsonArray()) {
            return new JsonArray();
        }
        return root.getAsJsonArray("furniture");
    }

    // ------------------------------------------------------------------------
    //  CARRIED RECORDS
    // ------------------------------------------------------------------------

    /**
     * Removes one piece's carried record from a chunk's saved file.
     *
     * Carrying saves the piece, marked as carried, into the chunk it was picked
     * up from, so a crash mid-carry can bring it back. Once the carry ends that
     * record is stale: restoring it would drop a second copy of the piece and
     * its contents. The file is edited directly, and every other record is
     * kept as written, so this is safe whether or not the chunk is loaded.
     *
     * @return true once loading the chunk can no longer restore the record,
     *         false if it is still there because a file could not be written
     */
    public boolean removeCarriedRecord(ChunkKey key, UUID entityId) {
        if (key == null || entityId == null) return true;
        File file = key.toFile(chunkDataFolder);
        File bak = new File(file.getParentFile(), file.getName() + ".bak");
        Removal main = removeCarriedRecord(file, entityId);
        // The backup is only read when the main file is unreadable, but it must not bring the record back either.
        Removal backup = removeCarriedRecord(bak, entityId);
        return switch (main) {
            case REMOVED, ABSENT -> true;
            case MISSING, UNREADABLE -> backup != Removal.FAILED;
            case FAILED -> false;
        };
    }

    private enum Removal {
        /** The record was in the file and has been written out of it. */
        REMOVED,
        /** The file is readable and does not hold the record. */
        ABSENT,
        MISSING,
        UNREADABLE,
        /** The file holds the record but could not be rewritten. */
        FAILED
    }

    private Removal removeCarriedRecord(File file, UUID entityId) {
        if (!file.exists()) return Removal.MISSING;
        JsonObject root = readLoadableChunkJson(file);
        if (root == null) return Removal.UNREADABLE;

        JsonArray kept = new JsonArray();
        boolean removed = false;
        for (JsonElement record : furnitureRecords(root)) {
            if (isCarriedRecord(record) && entityId.equals(recordEntityId(record))) {
                removed = true;
                continue;
            }
            kept.add(record);
        }
        if (!removed) return Removal.ABSENT;

        root.add("furniture", kept);
        return writeChunkFile(file, root, false, "file " + file.getName()) ? Removal.REMOVED : Removal.FAILED;
    }

    /**
     * Removes carried records for pieces that are saved as placed furniture in
     * another record. Builds before this fix never cleared the record a carry
     * left behind, so existing data can still hold these.
     *
     * @param notRemoved told about each stale record whose file could not be
     *                   rewritten, so it can be kept from being restored
     * @return the number of records removed
     */
    public int removeStaleCarriedRecords(BiConsumer<ChunkKey, UUID> notRemoved) {
        File[] worlds = chunkDataFolder.listFiles(File::isDirectory);
        if (worlds == null) return 0;

        Map<ChunkKey, List<UUID>> carriedByChunk = new LinkedHashMap<>();
        Set<UUID> placedIds = new HashSet<>();
        for (File worldDir : worlds) {
            Set<ChunkKey> keys = new LinkedHashSet<>();
            String[] names = worldDir.list((dir, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
            if (names == null) continue;
            for (String name : names) {
                ChunkKey key = chunkKeyOf(worldDir.getName(), name.substring(0, name.indexOf(".json")));
                if (key != null) keys.add(key);
            }
            for (ChunkKey key : keys) {
                // Read what loading the chunk would read: the main file, else its backup.
                File file = new File(worldDir, key.x() + "_" + key.z() + ".json");
                JsonObject root = readLoadableChunkJson(file);
                if (root == null) root = readLoadableChunkJson(new File(worldDir, file.getName() + ".bak"));
                if (root == null) continue;
                for (JsonElement record : furnitureRecords(root)) {
                    UUID id = recordEntityId(record);
                    if (id == null) continue;
                    if (isCarriedRecord(record)) {
                        carriedByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                    } else {
                        // Only a copy that loading can restore makes a carry recovery stale.
                        // Removed types and slots can leave raw records that are intentionally skipped.
                        collectFurnitureIds(deserializeFurniture(record.getAsJsonObject()), placedIds);
                    }
                }
            }
        }

        int removed = 0;
        for (Map.Entry<ChunkKey, List<UUID>> entry : carriedByChunk.entrySet()) {
            for (UUID id : entry.getValue()) {
                if (!placedIds.contains(id)) continue;
                if (removeCarriedRecord(entry.getKey(), id)) {
                    removed++;
                } else {
                    notRemoved.accept(entry.getKey(), id);
                }
            }
        }
        return removed;
    }

    /** Parses a chunk file's base name such as {@code 3_-2}; null for anything else. */
    private static ChunkKey chunkKeyOf(String world, String baseName) {
        String[] coords = baseName.split("_");
        if (coords.length != 2) return null;
        try {
            return new ChunkKey(world, Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private File pendingRemovalsFile() {
        return new File(chunkDataFolder.getParentFile(), "pending-carried-records.json");
    }

    /**
     * Saves the carried records that could not be removed yet, so that after a
     * restart they are still skipped rather than restored.
     *
     * @return false if the list could not be saved
     */
    public boolean savePendingCarriedRecords(Map<ChunkKey, Set<UUID>> pending) {
        File file = pendingRemovalsFile();
        if (pending.isEmpty()) {
            return !file.exists() || file.delete();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<ChunkKey, Set<UUID>> entry : pending.entrySet()) {
            ChunkKey key = entry.getKey();
            for (UUID id : entry.getValue()) {
                entries.add(Map.of("world", key.world(), "x", key.x(), "z", key.z(), "entityId", id.toString()));
            }
        }
        return writeChunkFile(file, Map.of("pending", entries), false, "pending carried records");
    }

    public Map<ChunkKey, Set<UUID>> loadPendingCarriedRecords() {
        Map<ChunkKey, Set<UUID>> pending = new HashMap<>();
        JsonObject root = readChunkJson(pendingRemovalsFile());
        if (root == null || !root.has("pending") || !root.get("pending").isJsonArray()) return pending;
        for (JsonElement el : root.getAsJsonArray("pending")) {
            try {
                JsonObject entry = el.getAsJsonObject();
                ChunkKey key = new ChunkKey(entry.get("world").getAsString(),
                        entry.get("x").getAsInt(), entry.get("z").getAsInt());
                pending.computeIfAbsent(key, k -> new HashSet<>())
                        .add(UUID.fromString(entry.get("entityId").getAsString()));
            } catch (RuntimeException ignored) {
                // Skip a malformed entry; the rest still apply.
            }
        }
        return pending;
    }

    private static boolean isCarriedRecord(JsonElement record) {
        if (!record.isJsonObject()) return false;
        JsonElement carried = record.getAsJsonObject().get("carried");
        return carried != null && carried.isJsonPrimitive() && carried.getAsJsonPrimitive().isBoolean()
                && carried.getAsBoolean();
    }

    private static UUID recordEntityId(JsonElement record) {
        if (!record.isJsonObject()) return null;
        JsonElement raw = record.getAsJsonObject().get("entityId");
        if (raw == null || !raw.isJsonPrimitive()) return null;
        try {
            return UUID.fromString(raw.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void collectFurnitureIds(Furniture furniture, Set<UUID> out) {
        if (furniture == null) return;
        out.add(furniture.getEntityId());
        for (PlacedFurnitureSlot slot : furniture.getActiveFurnitureSlots().values()) {
            collectFurnitureIds(slot.getNested(), out);
        }
    }

    // ------------------------------------------------------------------------
    //  FURNITURE SERIALIZATION
    // ------------------------------------------------------------------------

    private Map<String, Object> serializeFurniture(Furniture f) {
        return serializeFurnitureState(f, false);
    }

    private Map<String, Object> serializeFurnitureState(Furniture f, boolean attached) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("id", f.getId());
        obj.put("type", f.getId());
        obj.put("entityId", f.getEntityId().toString());
        obj.put("yaw", f.getYaw());

        if (!attached) {
            obj.put("location", serializeLocation(f.getLoc()));
            obj.put("carried", f.isCarried() || f.isPersistedCarried());

            f.getOriginBlockLocation().ifPresent(loc -> obj.put("originBlock", serializeLocation(loc)));
            f.getOriginBlockFace().ifPresent(face -> obj.put("originBlockFace", face.name()));

            if (!f.getBarrierBlocks().isEmpty()) {
                obj.put("barrierBlocks", f.getBarrierBlocks().stream()
                        .map(b -> serializeLocation(b.getLocation()))
                        .toList());
            }

            if (f.getInteractionEntityId() != null) {
                obj.put("interactionEntityId", f.getInteractionEntityId().toString());
            }
        }

        serializeActiveSlots(f, obj);
        serializeActiveFurnitureSlots(f, obj);

        Map<String, Object> dataMap = new HashMap<>();

        if (!f.getVariables().isEmpty()) {
            dataMap.put("variables", f.getVariables());
        }

        if (f.getModelOverride() != null) {
            ModelData m = f.getModelOverride();
            dataMap.put("modelOverride", Map.of(
                    "display", m.getDisplay().name(),
                    "model", m.getModel()
            ));
        }

        if (!dataMap.isEmpty()) {
            obj.put("data", dataMap);
        }

        return obj;
    }

    private void serializeActiveSlots(Furniture f, Map<String, Object> obj) {
        if (f.getActiveSlots().isEmpty()) {
            return;
        }
        Map<String, Object> slots = new HashMap<>();
        for (var entry : f.getActiveSlots().entrySet()) {
            PlacedSlot slot = entry.getValue();
            Map<String, Object> slotMap = new HashMap<>();
            if (slot.getDisplayStandId() != null) {
                slotMap.put("displayStandId", slot.getDisplayStandId().toString());
            }
            ItemStack item = slot.getCurrentItem();
            if (item == null && slot.getDisplayStandId() != null) {
                Entity stand = Bukkit.getEntity(slot.getDisplayStandId());
                if (stand instanceof ItemDisplay display) {
                    item = display.getItemStack();
                }
            }
            String encoded = ItemStackCodec.serialize(item);
            if (encoded != null) {
                slotMap.put("item", encoded);
            }
            slots.put(entry.getKey(), slotMap);
        }
        obj.put("activeSlots", slots);
    }

    private void serializeActiveFurnitureSlots(Furniture f, Map<String, Object> obj) {
        if (f.getActiveFurnitureSlots().isEmpty()) {
            return;
        }
        Map<String, Object> furnitureSlots = new HashMap<>();
        for (var entry : f.getActiveFurnitureSlots().entrySet()) {
            PlacedFurnitureSlot placed = entry.getValue();
            Furniture nested = placed.getNested();
            if (nested == null) {
                continue;
            }
            furnitureSlots.put(entry.getKey(), serializeFurnitureState(nested, true));
        }
        if (!furnitureSlots.isEmpty()) {
            obj.put("activeFurnitureSlots", furnitureSlots);
        }
    }

    private Furniture deserializeFurniture(JsonObject obj) {
        return deserializeFurnitureState(obj, false, null);
    }

    private Furniture deserializeFurnitureState(JsonObject obj, boolean attached, Furniture parent) {
        String typeId = obj.has("type") ? obj.get("type").getAsString() : obj.get("id").getAsString();
        if (FurnitureLoader.getByString(typeId) == null) return null;

        UUID entityId = UUID.fromString(obj.get("entityId").getAsString());
        Location loc;
        Location originLoc = null;
        BlockFace originFace = null;

        if (attached) {
            loc = parent.getLoc().clone();
        } else {
            loc = deserializeLocation(obj.getAsJsonObject("location"));
            if (loc == null) return null;

            if (obj.has("originBlock")) {
                originLoc = deserializeLocation(obj.getAsJsonObject("originBlock"));
            }
            if (obj.has("originBlockFace")) {
                try {
                    originFace = BlockFace.valueOf(obj.get("originBlockFace").getAsString());
                } catch (Exception ignored) {}
            }
        }

        Furniture furniture = new Furniture(typeId, loc, entityId, originLoc, originFace);

        if (obj.has("yaw")) {
            furniture.setYaw(obj.get("yaw").getAsFloat());
        }
        if (!attached && obj.has("carried") && obj.get("carried").getAsBoolean()) {
            furniture.setPersistedCarried(true);
        }

        if (!attached && obj.has("interactionEntityId")) {
            try {
                furniture.setInteractionEntityId(UUID.fromString(obj.get("interactionEntityId").getAsString()));
            } catch (IllegalArgumentException ignored) {}
        }

        if (!attached && obj.has("barrierBlocks") && obj.get("barrierBlocks").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("barrierBlocks")) {
                if (!el.isJsonObject()) continue;
                Location barrierLoc = deserializeLocation(el.getAsJsonObject());
                if (barrierLoc == null) continue;
                furniture.addBarrierBlock(barrierLoc.getBlock());
            }
        }

        deserializeActiveSlots(obj, furniture);
        deserializeActiveFurnitureSlots(obj, furniture);

        if (obj.has("data")) {
            JsonObject dataObj = obj.getAsJsonObject("data");
            if (dataObj.has("variables")) {
                Map<String, Object> vars = new HashMap<>();
                JsonObject varObj = dataObj.getAsJsonObject("variables");
                for (String key : varObj.keySet()) {
                    JsonElement v = varObj.get(key);
                    if (v.isJsonPrimitive()) {
                        JsonPrimitive p = v.getAsJsonPrimitive();
                        if (p.isNumber()) vars.put(key, p.getAsNumber());
                        else if (p.isBoolean()) vars.put(key, p.getAsBoolean());
                        else vars.put(key, p.getAsString());
                    }
                }
                furniture.setVariables(vars);
            }

            if (dataObj.has("modelOverride")) {
                JsonObject o = dataObj.getAsJsonObject("modelOverride");
                try {
                    Display d = Display.valueOf(o.get("display").getAsString());
                    String model = o.get("model").getAsString();
                    furniture.setModelOverride(new ModelData(d, model));
                } catch (Exception ignored) {}
            }
        }

        return furniture;
    }

    private void deserializeActiveSlots(JsonObject obj, Furniture furniture) {
        if (!obj.has("activeSlots")) {
            return;
        }
        JsonObject slots = obj.getAsJsonObject("activeSlots");
        for (String key : slots.keySet()) {
            JsonObject sObj = slots.getAsJsonObject(key);
            if (furniture.getType() == null || furniture.getType().getSlot(key) == null) continue;
            PlacedSlot slot = furniture.getOrCreatePlacedSlot(key);

            if (sObj.has("displayStandId")) {
                UUID dispId = UUID.fromString(sObj.get("displayStandId").getAsString());
                slot.setDisplayStandId(dispId);
            }
            if (sObj.has("item")) {
                ItemStack item = ItemStackCodec.deserialize(sObj.get("item").getAsString());
                if (item != null) {
                    slot.setModel(item);
                }
            }
        }
    }

    private void deserializeActiveFurnitureSlots(JsonObject obj, Furniture furniture) {
        if (!obj.has("activeFurnitureSlots")) {
            return;
        }
        JsonObject furnitureSlots = obj.getAsJsonObject("activeFurnitureSlots");
        for (String slotId : furnitureSlots.keySet()) {
            if (furniture.getType() == null) continue;
            var slotDef = furniture.getType().getSlot(slotId);
            if (slotDef == null || slotDef.getSlotType() != SlotType.FURNITURE) continue;

            JsonObject nestedObj = furnitureSlots.getAsJsonObject(slotId);
            Furniture nested = deserializeFurnitureState(nestedObj, true, furniture);
            if (nested == null) continue;

            nested.setAttachment(furniture.getEntityId(), slotId);
            PlacedFurnitureSlot placed = furniture.getOrCreatePlacedFurnitureSlot(slotId);
            placed.setNested(nested);
            placed.setParent(furniture);
        }
    }

    // ------------------------------------------------------------------------
    //  LOCATION
    // ------------------------------------------------------------------------

    private Map<String, Object> serializeLocation(Location loc) {
        Map<String, Object> m = new HashMap<>();
        m.put("world", loc.getWorld().getName());
        m.put("x", loc.getX());
        m.put("y", loc.getY());
        m.put("z", loc.getZ());
        return m;
    }

    private Location deserializeLocation(JsonObject obj) {
        if (obj == null) return null;
        World w = Bukkit.getWorld(obj.get("world").getAsString());
        if (w == null) return null;
        return new Location(
                w,
                obj.get("x").getAsDouble(),
                obj.get("y").getAsDouble(),
                obj.get("z").getAsDouble()
        );
    }

    // ------------------------------------------------------------------------
    //  EVENTS
    // ------------------------------------------------------------------------

    public void saveChunk(Chunk chunk, Collection<Furniture> furniture) {
        saveChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), furniture);
    }

    public List<Furniture> loadChunk(Chunk chunk) {
        return loadChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }
}

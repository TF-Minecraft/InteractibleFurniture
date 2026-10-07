package net.tfminecraft.interactiblefurniture.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.enums.Display;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.FurnitureType;
import net.tfminecraft.interactiblefurniture.furniture.PlacedSlot;
import net.tfminecraft.interactiblefurniture.furniture.data.ModelData;
import net.tfminecraft.interactiblefurniture.loaders.FurnitureLoader;

/** Exercises persisted state and recovery using actual files in the mock server's isolated data directory. */
class DatabaseCoverageTest extends FurnitureTestServer {
    @TempDir Path scratch;

    @Test
    void roundTripPreservesSolidFurnitureGeometryVariablesModelAndInteractionIdentity() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("solid: true\nitem: test.crate");
        FurnitureLoader.getMap().put("solid-test", new FurnitureType("solid-test", config));
        Furniture furniture = record("solid-test", 1, 2);
        furniture.setYaw(37.5f);
        furniture.setOriginBlock(new Location(world, 17, 64, 33), BlockFace.EAST);
        furniture.addBarrierBlock(world.getBlockAt(17, 65, 33));
        furniture.addBarrierBlock(world.getBlockAt(18, 65, 33));
        UUID interaction = UUID.randomUUID();
        furniture.setInteractionEntityId(interaction);
        furniture.setVariables(Map.of("count", 7, "locked", true, "name", "Crème 木"));
        furniture.setModelOverride(new ModelData(Display.ITEM_DISPLAY, "test.special"));

        database().saveChunk(world.getChunkAt(1, 2), List.of(furniture));
        Furniture restored = database().loadChunk(world.getChunkAt(1, 2)).getFirst();

        assertEquals(furniture.getEntityId(), restored.getEntityId());
        assertEquals(furniture.getLoc(), restored.getLoc());
        assertEquals(37.5f, restored.getYaw());
        assertEquals(furniture.getOriginBlockLocation(), restored.getOriginBlockLocation());
        assertEquals(BlockFace.EAST, restored.getOriginBlockFace().orElseThrow());
        assertEquals(furniture.getBarrierBlocks(), restored.getBarrierBlocks());
        assertEquals(interaction, restored.getInteractionEntityId());
        assertEquals(7, ((Number) restored.getVariables().get("count")).intValue());
        assertEquals(true, restored.getVariables().get("locked"));
        assertEquals("Crème 木", restored.getVariables().get("name"));
        assertEquals(Display.ITEM_DISPLAY, restored.getModelOverride().getDisplay());
        assertEquals("test.special", restored.getModelOverride().getModel());
        assertFalse(restored.isPersistedCarried());
    }

    @Test
    void legacySlotItemsAreRecoveredFromLiveDisplayEntitiesWhenTheModelIsAbsent() throws Exception {
        Furniture furniture = record(CRATE, 0, 0);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 3);
        ItemDisplay display = world.spawn(furniture.getLoc(), ItemDisplay.class, stand -> stand.setItemStack(diamonds));
        furniture.getOrCreatePlacedSlot("lid").setDisplayStandId(display.getUniqueId());

        save(0, 0, furniture);
        Furniture restored = database().loadChunk(world.getName(), 0, 0).getFirst();

        PlacedSlot slot = restored.getActiveSlot("lid").orElseThrow();
        assertEquals(display.getUniqueId(), slot.getDisplayStandId());
        assertEquals(diamonds, slot.getCurrentItem());
        display.remove();
        save(0, 0, furniture);
        assertNull(database().loadChunk(world.getName(), 0, 0).getFirst()
                .getActiveSlot("lid").orElseThrow().getCurrentItem());
    }

    @Test
    void nestedFurnitureRoundTripsInsideItsParentWithoutSavingASecondWorldPosition() throws Exception {
        Furniture parent = record(TABLE, 0, 0);
        Furniture nested = record(CRATE, 1, 0);
        nested.setAttachment(parent.getEntityId(), "surface");
        nested.setVariables(Map.of("owner", "Alice"));
        nested.getOrCreatePlacedSlot("lid").setModel(new ItemStack(Material.EMERALD, 2));
        parent.getOrCreatePlacedFurnitureSlot("surface").setNested(nested);
        parent.getOrCreatePlacedFurnitureSlot("empty");

        save(0, 0, parent);

        JsonObject serialized = root(0, 0).getAsJsonArray("furniture").get(0).getAsJsonObject();
        JsonObject serializedNested = serialized.getAsJsonObject("activeFurnitureSlots").getAsJsonObject("surface");
        assertFalse(serializedNested.has("location"));
        assertFalse(serializedNested.has("originBlock"));
        assertFalse(serialized.getAsJsonObject("activeFurnitureSlots").has("empty"));
        Furniture restoredParent = database().loadChunk(world.getName(), 0, 0).getFirst();
        Furniture restored = restoredParent.getActiveFurnitureSlot("surface").orElseThrow().getNested();
        assertEquals(nested.getEntityId(), restored.getEntityId());
        assertEquals(restoredParent.getEntityId(), restored.getParentEntityId());
        assertEquals("surface", restored.getParentSlotId());
        assertEquals(restoredParent.getLoc(), restored.getLoc());
        assertEquals("Alice", restored.getVariables().get("owner"));
        assertEquals(new ItemStack(Material.EMERALD, 2), restored.getActiveSlot("lid").orElseThrow().getCurrentItem());

        parent.clearActiveFurnitureSlots();
        parent.getOrCreatePlacedFurnitureSlot("surface");
        save(0, 0, parent);
        assertFalse(root(0, 0).getAsJsonArray("furniture").get(0).getAsJsonObject().has("activeFurnitureSlots"));
    }

    @Test
    void obsoleteOptionalMetadataIsSkippedWithoutLosingTheFurniture() throws Exception {
        Furniture furniture = record(CRATE, 0, 0);
        save(0, 0, furniture);
        edit(0, 0, obj -> {
            JsonObject record = obj.getAsJsonArray("furniture").get(0).getAsJsonObject();
            record.remove("type"); // Old saves only used id.
            record.remove("yaw");
            record.addProperty("originBlockFace", "INVALID_FACE");
            record.addProperty("interactionEntityId", "not-a-uuid");
            record.add("barrierBlocks", JsonParser.parseString("""
                    [42,{"world":"missing-world","x":0,"y":0,"z":0}]
                    """));
            record.add("data", JsonParser.parseString("""
                    {"variables":{"valid":3,"unsupported":[1,2]},
                     "modelOverride":{"display":"UNKNOWN_DISPLAY","model":"unknown"}}
                    """));
            record.add("activeSlots", JsonParser.parseString("""
                    {"lid":{"item":"invalid item bytes"},"deleted-slot":{}}
                    """));
        });

        Furniture restored = database().loadChunk(world.getName(), 0, 0).getFirst();

        assertEquals(furniture.getEntityId(), restored.getEntityId());
        assertEquals(0f, restored.getYaw());
        assertTrue(restored.getOriginBlockFace().isEmpty());
        assertNull(restored.getInteractionEntityId());
        assertTrue(restored.getBarrierBlocks().isEmpty());
        assertEquals(Set.of("valid"), restored.getVariables().keySet());
        assertNull(restored.getModelOverride());
        assertEquals(Set.of("lid"), restored.getActiveSlots().keySet());
        assertNull(restored.getActiveSlot("lid").orElseThrow().getCurrentItem());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"furniture\":false}", "{\"furniture\":[1,null,\"ignored\"]}",
            "{\"furniture\":[{\"type\":\"unknown\"}]}", "[]", "null", "{ invalid json"})
    void absentOrUnreadableRecordsDoNotInventFurniture(String content) throws Exception {
        write(chunk(0, 0), content);

        assertTrue(database().loadChunk(world.getName(), 0, 0).isEmpty());
        assertTrue(database().loadChunk(world.getName(), 8, 8).isEmpty());
    }

    @Test
    void missingLocationsAndUnavailableWorldsAreSkipped() throws Exception {
        Furniture first = record(CRATE, 0, 0);
        Furniture second = record(CRATE, 0, 0);
        save(0, 0, first, second);
        edit(0, 0, root -> {
            root.getAsJsonArray("furniture").get(0).getAsJsonObject().remove("location");
            root.getAsJsonArray("furniture").get(1).getAsJsonObject()
                    .getAsJsonObject("location").addProperty("world", "unavailable");
        });

        assertTrue(database().loadChunk(world.getName(), 0, 0).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed-json", "invalid-record"})
    void savedFurnitureVisitorUsesTheSameRecoverableBackupAsChunkLoading(String corruption) throws Exception {
        Furniture furniture = record(CRATE, 2, 3);
        save(2, 3, furniture);
        save(2, 3, furniture);
        if (corruption.equals("missing")) Files.delete(chunk(2, 3));
        else if (corruption.equals("malformed-json")) write(chunk(2, 3), "{ broken");
        else write(chunk(2, 3), "{\"furniture\":[{\"type\":\"" + CRATE + "\",\"entityId\":\"bad\"}]}");

        List<UUID> visited = new ArrayList<>();
        database().visitSavedFurniture(f -> visited.add(f.getEntityId()));

        assertEquals(List.of(furniture.getEntityId()), database().loadChunk(world.getName(), 2, 3)
                .stream().map(Furniture::getEntityId).toList());
        assertEquals(List.of(furniture.getEntityId()), visited,
                "offline indexes must see the same recoverable furniture that a chunk load will restore");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"furniture\":null}", "{\"furniture\":false}",
            "{\"furniture\":{}}", "{\"furniture\":7}", "{\"furniture\":\"lost\"}"})
    void invalidFurnitureContainersUseTheValidBackupForLoadingAndOfflineVisits(String content) throws Exception {
        Furniture placed = record(CRATE, 2, 3);
        placed.getOrCreatePlacedSlot("lid").setModel(new ItemStack(Material.DIAMOND, 3));
        save(2, 3, placed);
        save(2, 3, placed);
        Path backup = chunk(2, 3).resolveSibling("2_3.json.bak");
        String committed = Files.readString(backup);
        write(chunk(2, 3), content);

        List<Furniture> loaded = database().loadChunk(world.getName(), 2, 3);
        assertEquals(List.of(placed.getEntityId()), loaded.stream().map(Furniture::getEntityId).toList());
        assertEquals(new ItemStack(Material.DIAMOND, 3), loaded.getFirst().getActiveSlot("lid").orElseThrow().getCurrentItem());
        List<UUID> visited = new ArrayList<>();
        database().visitSavedFurniture(f -> visited.add(f.getEntityId()));
        assertEquals(List.of(placed.getEntityId()), visited);
        assertEquals(content, Files.readString(chunk(2, 3)), "Recovery must not rewrite the damaged main file while reading it");
        assertEquals(committed, Files.readString(backup));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"furniture\":null}", "{\"furniture\":false}",
            "{\"furniture\":{}}", "{\"furniture\":7}", "{\"furniture\":\"lost\"}"})
    void startupUsesBackupPlacedRecordsToPreventDuplicateCarriedFurnitureAndContents(String content) throws Exception {
        Furniture moved = record(CRATE, 1, 0);
        moved.setPersistedCarried(true);
        moved.getOrCreatePlacedSlot("lid").setModel(new ItemStack(Material.DIAMOND, 3));
        Furniture recoverable = record(CRATE, 1, 0);
        recoverable.setPersistedCarried(true);
        save(1, 0, moved, recoverable);
        save(1, 0, moved, recoverable);
        Furniture placed = new Furniture(CRATE, new Location(world, 33.5, 65, 1.5), moved.getEntityId(),
                new Location(world, 33, 64, 1), BlockFace.UP);
        placed.getOrCreatePlacedSlot("lid").setModel(new ItemStack(Material.DIAMOND, 3));
        save(2, 0, placed);
        save(2, 0, placed);
        write(chunk(2, 0), content);

        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        List<UUID> remainingRecovery = database().loadChunk(world.getName(), 1, 0)
                .stream().map(Furniture::getEntityId).toList();
        loadChunk(1, 0);

        assertEquals(List.of(Material.BARREL), droppedItems(),
                "Only the unrelated recovery record may drop; the placed crate and its diamonds already survive in the backup");
        assertEquals(List.of(recoverable.getEntityId()), remainingRecovery);
        assertFalse(Files.readString(chunk(1, 0).resolveSibling("1_0.json.bak")).contains(moved.getEntityId().toString()));
        Furniture restored = database().loadChunk(world.getName(), 2, 0).getFirst();
        assertEquals(moved.getEntityId(), restored.getEntityId());
        assertFalse(restored.isPersistedCarried());
        assertEquals(new ItemStack(Material.DIAMOND, 3), restored.getActiveSlot("lid").orElseThrow().getCurrentItem());
        loadChunk(1, 0);
        assertEquals(List.of(Material.BARREL), droppedItems(), "Recovery is consumed exactly once");
    }

    @Test
    void explicitEmptyFurnitureArrayRemainsAuthoritativeOverAnOlderBackup() throws Exception {
        Furniture carried = record(CRATE, 1, 0);
        carried.setPersistedCarried(true);
        save(1, 0, carried);
        Furniture formerPlaced = new Furniture(CRATE, new Location(world, 33.5, 65, 1.5), carried.getEntityId(),
                new Location(world, 33, 64, 1), BlockFace.UP);
        save(2, 0, formerPlaced);
        save(2, 0, formerPlaced);
        write(chunk(2, 0), "{\"furniture\":[]}");

        assertTrue(database().loadChunk(world.getName(), 2, 0).isEmpty());
        List<Furniture> visited = new ArrayList<>();
        database().visitSavedFurniture(visited::add);
        assertTrue(visited.isEmpty());
        assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
            throw new AssertionError("The older backup must not override an explicitly empty main file");
        }));
        assertEquals(List.of(carried.getEntityId()), database().loadChunk(world.getName(), 1, 0)
                .stream().map(Furniture::getEntityId).toList());
    }

    @Test
    void visitorSkipsCarryRecoveryTemporaryFilesAndUnrecoverableChunks() throws Exception {
        Furniture visible = record(CRATE, 0, 0);
        Furniture carried = record(CRATE, 0, 0);
        carried.setPersistedCarried(true);
        save(0, 0, visible, carried);
        save(0, 0, visible, carried);
        Files.copy(chunk(0, 0), chunk(0, 0).resolveSibling("9_9.json.tmp"));
        write(chunk(1, 0), "{ invalid");
        write(chunk(1, 0).resolveSibling("1_0.json.bak"), "{ also invalid");
        write(chunksRoot().resolve("unrelated-file"), "not a world");
        List<UUID> visited = new ArrayList<>();

        database().visitSavedFurniture(f -> visited.add(f.getEntityId()));
        database().visitSavedFurniture(null);

        assertEquals(List.of(visible.getEntityId()), visited);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted-slot", "item-slot", "unknown-nested-type", "unknown-parent-type"})
    void staleCleanupKeepsRecoveryWhenTheSupposedPlacedCopyCannotBeRestored(String reason) throws Exception {
        Furniture carried = record(CRATE, 3, 0);
        carried.setPersistedCarried(true);
        save(3, 0, carried);
        Furniture parent = record(reason.equals("item-slot") ? CRATE : TABLE, 4, 0);
        save(4, 0, parent);
        edit(4, 0, root -> {
            JsonObject serialized = root.getAsJsonArray("furniture").get(0).getAsJsonObject();
            if (reason.equals("unknown-parent-type")) {
                serialized.addProperty("type", "deleted-type");
                serialized.addProperty("entityId", carried.getEntityId().toString());
                return;
            }
            JsonObject nested = new JsonObject();
            nested.addProperty("type", reason.equals("unknown-nested-type") ? "deleted-type" : CRATE);
            nested.addProperty("entityId", carried.getEntityId().toString());
            JsonObject slots = new JsonObject();
            slots.add(reason.equals("item-slot") ? "lid" : reason.equals("deleted-slot") ? "deleted" : "surface", nested);
            serialized.add("activeFurnitureSlots", slots);
        });
        List<Furniture> supposedCopy = database().loadChunk(world.getName(), 4, 0);
        assertTrue(supposedCopy.isEmpty() || supposedCopy.getFirst().getActiveFurnitureSlots().isEmpty());

        assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
            throw new AssertionError("no deletion should have been attempted");
        }));

        assertEquals(List.of(carried.getEntityId()), database().loadChunk(world.getName(), 3, 0)
                .stream().map(Furniture::getEntityId).toList(), "the only restorable copy must survive cleanup");
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "nested", "unknown-parent-type", "unknown-nested-type", "deleted-slot", "item-slot"})
    void staleCleanupRecognizesDefinedCopiesBeforeTheirWorldLoads(String shape) throws Exception {
        Furniture carried = record(CRATE, 3, 0);
        carried.setPersistedCarried(true);
        save(3, 0, carried);
        save(4, 0, record(shape.equals("item-slot") ? CRATE : TABLE, 4, 0));
        edit(4, 0, root -> {
            JsonObject placed = root.getAsJsonArray("furniture").get(0).getAsJsonObject();
            placed.getAsJsonObject("location").addProperty("world", "not_loaded_yet");
            if (shape.equals("root") || shape.equals("unknown-parent-type")) {
                placed.addProperty("entityId", carried.getEntityId().toString());
                if (shape.equals("unknown-parent-type")) placed.addProperty("type", "deleted-type");
            } else {
                JsonObject nested = new JsonObject();
                nested.addProperty("type", shape.equals("unknown-nested-type") ? "deleted-type" : CRATE);
                nested.addProperty("entityId", carried.getEntityId().toString());
                JsonObject slots = new JsonObject();
                slots.add(shape.equals("deleted-slot") ? "deleted" : shape.equals("item-slot") ? "lid" : "surface", nested);
                placed.add("activeFurnitureSlots", slots);
            }
        });
        assertTrue(database().loadChunk(world.getName(), 4, 0).isEmpty());
        boolean restorable = shape.equals("root") || shape.equals("nested");
        assertEquals(restorable ? 1 : 0, database().removeStaleCarriedRecords((key, id) -> {
            throw new AssertionError("recovery deletion must succeed");
        }));
        assertEquals(restorable ? List.of() : List.of(carried.getEntityId()),
                database().loadChunk(world.getName(), 3, 0).stream().map(Furniture::getEntityId).toList());
    }

    @Test
    void staleCleanupIgnoresInvalidFileNamesAndMalformedUnknownRecords() throws Exception {
        Furniture furniture = record(CRATE, 0, 0);
        save(0, 0, furniture);
        edit(0, 0, root -> {
            root.getAsJsonArray("furniture").add(12);
            JsonObject unknown = new JsonObject();
            unknown.addProperty("type", "unknown");
            unknown.addProperty("entityId", "invalid-uuid");
            root.getAsJsonArray("furniture").add(unknown);
            JsonObject missingId = new JsonObject();
            missingId.addProperty("type", "unknown");
            root.getAsJsonArray("furniture").add(missingId);
        });
        write(chunkFolder().toPath().resolve("not_a_number.json"), "{}");
        write(chunkFolder().toPath().resolve("1_n.json.bak"), "{}");
        write(chunkFolder().toPath().resolve("single.json"), "{}");
        write(chunkFolder().toPath().resolve("2_3.json"), "[]");

        assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
            throw new AssertionError("valid placed records must not be deleted");
        }));
        assertEquals(List.of(furniture.getEntityId()), database().loadChunk(world.getName(), 0, 0)
                .stream().map(Furniture::getEntityId).toList());
    }

    @Test
    void removingRecoveryFromABackupReportsFailureAndCanBeRetriedWithoutLosingOtherData() throws Exception {
        Furniture carried = record(CRATE, 5, 0);
        carried.setPersistedCarried(true);
        Furniture placed = record(CRATE, 5, 0);
        save(5, 0, carried, placed);
        edit(5, 0, root -> root.addProperty("unknown-metadata", "must survive"));
        Files.copy(chunk(5, 0), chunk(5, 0).resolveSibling("5_0.json.bak"));
        write(chunk(5, 0), "{ broken");
        Path blocked = chunk(5, 0).resolveSibling("5_0.json.bak.tmp");
        Files.createDirectory(blocked);
        Database.ChunkKey key = new Database.ChunkKey(world.getName(), 5, 0);

        assertFalse(database().removeCarriedRecord(key, carried.getEntityId()));
        assertEquals(2, database().loadChunk(world.getName(), 5, 0).size());
        Files.delete(blocked);
        assertTrue(database().removeCarriedRecord(key, carried.getEntityId()));
        assertEquals(List.of(placed.getEntityId()), database().loadChunk(world.getName(), 5, 0)
                .stream().map(Furniture::getEntityId).toList());
        JsonObject backup = JsonParser.parseString(Files.readString(chunk(5, 0).resolveSibling("5_0.json.bak"))).getAsJsonObject();
        assertEquals("must survive", backup.get("unknown-metadata").getAsString());
        assertTrue(database().removeCarriedRecord(null, carried.getEntityId()));
        assertTrue(database().removeCarriedRecord(key, null));
        assertTrue(database().removeCarriedRecord(new Database.ChunkKey(world.getName(), 99, 99), carried.getEntityId()));
    }

    @Test
    void failedBackupReplacementPreservesThePreviousChunk() throws Exception {
        Furniture first = record(CRATE, 0, 0);
        Furniture replacement = record(CRATE, 0, 0);
        save(0, 0, first);
        String baseline = Files.readString(chunk(0, 0));
        Path backupBlocker = chunk(0, 0).resolveSibling("0_0.json.bak");
        Files.createDirectory(backupBlocker);
        write(backupBlocker.resolve("keep"), "preexisting data");

        save(0, 0, replacement);

        assertEquals(baseline, Files.readString(chunk(0, 0)));
        assertEquals("preexisting data", Files.readString(backupBlocker.resolve("keep")));
        assertEquals(first.getEntityId(), database().loadChunk(world.getName(), 0, 0).getFirst().getEntityId());
        Files.delete(backupBlocker.resolve("keep"));
        Files.delete(backupBlocker);
        save(0, 0, replacement);
        assertEquals(replacement.getEntityId(), database().loadChunk(world.getName(), 0, 0).getFirst().getEntityId());
        assertEquals(baseline, Files.readString(backupBlocker));
    }

    @Test
    void pendingRecordsRoundTripDeduplicateAndIgnoreMalformedEntries() throws Exception {
        Database.ChunkKey key = new Database.ChunkKey(world.getName(), -3, 8);
        UUID id = UUID.randomUUID();
        Map<Database.ChunkKey, Set<UUID>> pending = Map.of(key, Set.of(id));
        assertTrue(database().loadPendingCarriedRecords().isEmpty());
        assertTrue(database().savePendingCarriedRecords(pending));
        JsonObject root = JsonParser.parseString(Files.readString(pendingFile())).getAsJsonObject();
        JsonArray entries = root.getAsJsonArray("pending");
        entries.add(entries.get(0).deepCopy());
        entries.add(5);
        entries.add(new JsonObject());
        write(pendingFile(), root.toString());

        assertEquals(pending, database().loadPendingCarriedRecords());
        assertTrue(database().savePendingCarriedRecords(Map.of()));
        assertFalse(Files.exists(pendingFile()));
        assertTrue(database().savePendingCarriedRecords(Map.of()));
        for (String invalid : List.of("{}", "{\"pending\":false}", "{ invalid")) {
            write(pendingFile(), invalid);
            assertTrue(database().loadPendingCarriedRecords().isEmpty());
        }
    }

    @Test
    void pendingWritesFallBackFromAtomicReplacementAndProtectNonEmptyObstructions() throws Exception {
        Map<Database.ChunkKey, Set<UUID>> pending = Map.of(
                new Database.ChunkKey(world.getName(), 1, 1), Set.of(UUID.randomUUID()));
        Files.createDirectory(pendingFile());

        assertTrue(database().savePendingCarriedRecords(pending), "ordinary replacement can replace an empty directory");
        assertEquals(pending, database().loadPendingCarriedRecords());
        Files.delete(pendingFile());
        Files.createDirectory(pendingFile());
        write(pendingFile().resolve("keep"), "important data");

        assertFalse(database().savePendingCarriedRecords(pending));
        assertFalse(database().savePendingCarriedRecords(Map.of()));
        assertEquals("important data", Files.readString(pendingFile().resolve("keep")));
        Files.delete(pendingFile().resolve("keep"));
        Files.delete(pendingFile());
    }

    @Test
    void failedPendingTemporaryWriteKeepsThePreviouslyPersistedRecoveryList() throws Exception {
        Map<Database.ChunkKey, Set<UUID>> pending = Map.of(
                new Database.ChunkKey(world.getName(), 1, 1), Set.of(UUID.randomUUID()));
        assertTrue(database().savePendingCarriedRecords(pending));
        Path blocked = pendingFile().resolveSibling("pending-carried-records.json.tmp");
        Files.createDirectory(blocked);

        assertFalse(database().savePendingCarriedRecords(Map.of(
                new Database.ChunkKey(world.getName(), 2, 2), Set.of(UUID.randomUUID()))));
        assertEquals(pending, database().loadPendingCarriedRecords());
        Files.delete(blocked);
    }

    @Test
    void missingStorageDirectoriesAreTreatedAsEmptyByVisitorsAndStaleCleanup() throws Exception {
        Path root = chunksRoot();
        Path moved = scratch.resolve("preserved-chunks");
        Files.move(root, moved);
        try {
            List<Furniture> visited = new ArrayList<>();
            database().visitSavedFurniture(visited::add);
            assertTrue(visited.isEmpty());
            assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
                throw new AssertionError("there are no records to remove");
            }));
        } finally {
            Files.move(moved, root);
        }
    }

    @Test
    void unreadableStorageDirectoriesDoNotBreakVisitorsOrStaleCleanup() throws Exception {
        Path root = chunksRoot();
        Set<PosixFilePermission> old = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_EXECUTE));
        try {
            assertNull(root.toFile().listFiles(), "this filesystem must enforce the requested directory read denial");
            List<Furniture> visited = new ArrayList<>();
            database().visitSavedFurniture(visited::add);
            assertTrue(visited.isEmpty());
            assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
                throw new AssertionError("unreadable records cannot be removed");
            }));
        } finally {
            Files.setPosixFilePermissions(root, old);
        }
    }

    @Test
    void unreadableWorldDirectoriesDoNotHideOtherReadableWorlds() throws Exception {
        Furniture visible = record(CRATE, 0, 0);
        save(0, 0, visible);
        Path unreadable = chunksRoot().resolve("unreadable-world");
        Files.createDirectory(unreadable);
        Set<PosixFilePermission> old = Files.getPosixFilePermissions(unreadable);
        Files.setPosixFilePermissions(unreadable, Set.of(PosixFilePermission.OWNER_EXECUTE));
        try {
            assertNull(unreadable.toFile().listFiles(), "the unreadable-world scenario must be real");
            List<UUID> visited = new ArrayList<>();
            database().visitSavedFurniture(f -> visited.add(f.getEntityId()));
            assertEquals(List.of(visible.getEntityId()), visited);
            assertEquals(0, database().removeStaleCarriedRecords((key, id) -> {
                throw new AssertionError("no stale records were saved");
            }));
        } finally {
            Files.setPosixFilePermissions(unreadable, old);
        }
    }

    private Database database() {
        return manager.getDatabase();
    }

    private Furniture record(String type, int chunkX, int chunkZ) {
        return new Furniture(type, new Location(world, chunkX * 16 + 1.5, 65, chunkZ * 16 + 1.5),
                UUID.randomUUID(), new Location(world, chunkX * 16 + 1, 64, chunkZ * 16 + 1), BlockFace.UP);
    }

    private void save(int x, int z, Furniture... furniture) {
        database().saveChunk(world.getName(), x, z, List.of(furniture));
    }

    private Path chunk(int x, int z) {
        return chunkFolder().toPath().resolve(x + "_" + z + ".json");
    }

    private Path chunksRoot() {
        return new File(plugin.getDataFolder(), "data/chunks").toPath();
    }

    private Path pendingFile() {
        return chunksRoot().getParent().resolve("pending-carried-records.json");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private JsonObject root(int x, int z) throws IOException {
        return JsonParser.parseString(Files.readString(chunk(x, z))).getAsJsonObject();
    }

    private void edit(int x, int z, Consumer<JsonObject> editor) throws IOException {
        JsonObject root = root(x, z);
        editor.accept(root);
        write(chunk(x, z), root.toString());
    }
}

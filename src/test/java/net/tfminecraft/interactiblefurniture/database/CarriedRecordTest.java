package net.tfminecraft.interactiblefurniture.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import net.tfminecraft.interactiblefurniture.FurnitureTestServer;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.manager.handlers.FurnitureAttachmentHandler;

/** Carried records are edited in the chunk files directly, loaded or not. */
class CarriedRecordTest extends FurnitureTestServer {

    private Furniture record(UUID id, int chunkX, int chunkZ, boolean carried) {
        Furniture furniture = new Furniture(CRATE,
                new Location(world, chunkX * 16 + 4.5, GROUND_Y + 1.5, chunkZ * 16 + 4.5), id,
                new Location(world, chunkX * 16 + 4, GROUND_Y, chunkZ * 16 + 4), null);
        furniture.setPersistedCarried(carried);
        return furniture;
    }

    private void save(int chunkX, int chunkZ, Furniture... furniture) {
        manager.getDatabase().saveChunk(world.getName(), chunkX, chunkZ, List.of(furniture));
    }

    private List<UUID> idsIn(int chunkX, int chunkZ) {
        return savedIn(chunkX, chunkZ).stream().map(Furniture::getEntityId).toList();
    }

    private String backupOf(int chunkX, int chunkZ) throws IOException {
        File chunks = new File(plugin.getDataFolder(), "data/chunks/" + world.getName());
        return Files.readString(new File(chunks, chunkX + "_" + chunkZ + ".json.bak").toPath());
    }

    @Test
    void removingACarriedRecordKeepsTheRestOfAnUnloadedChunk() throws IOException {
        UUID carried = UUID.randomUUID();
        UUID placed = UUID.randomUUID();
        save(5, 5, record(carried, 5, 5, true), record(placed, 5, 5, false));
        save(5, 5, record(carried, 5, 5, true), record(placed, 5, 5, false));
        assertFalse(world.isChunkLoaded(5, 5));

        Database.ChunkKey key = new Database.ChunkKey(world.getName(), 5, 5);
        assertTrue(manager.getDatabase().removeCarriedRecord(key, carried));

        assertEquals(List.of(placed), idsIn(5, 5));
        assertFalse(backupOf(5, 5).contains(carried.toString()), "the backup must not bring the record back");
        assertTrue(backupOf(5, 5).contains(placed.toString()));
        assertFalse(manager.getDatabase().removeCarriedRecord(key, carried));
        assertFalse(manager.getDatabase().removeCarriedRecord(key, placed), "only carried records are removed");
        assertEquals(List.of(placed), idsIn(5, 5));
    }

    @Test
    void startupRemovesCarriedRecordsForPiecesSavedElsewhereAndKeepsRecoveryRecords() {
        UUID moved = UUID.randomUUID();
        save(5, 5, record(moved, 5, 5, true));
        save(6, 5, record(moved, 6, 5, false));
        // A piece attached to a table is saved inside the table's record.
        Furniture table = place(TABLE, 7 * 16 + 4, 5 * 16 + 4);
        Furniture crate = place(CRATE, 7 * 16 + 8, 5 * 16 + 8);
        assertTrue(FurnitureAttachmentHandler.attach(table, "surface", crate, player));
        save(8, 5, record(crate.getEntityId(), 8, 5, true));
        // Nothing else holds this one: it is what a crash mid-carry leaves.
        UUID recoverable = UUID.randomUUID();
        save(9, 5, record(recoverable, 9, 5, true));

        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);

        assertEquals(List.of(), idsIn(5, 5));
        assertEquals(List.of(moved), idsIn(6, 5));
        assertEquals(List.of(), idsIn(8, 5));
        assertEquals(List.of(table.getEntityId()), idsIn(7, 5));
        assertEquals(List.of(recoverable), idsIn(9, 5));
    }
}

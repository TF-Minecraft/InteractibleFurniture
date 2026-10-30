package net.tfminecraft.interactiblefurniture.furniture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import net.tfminecraft.interactiblefurniture.database.Database;

class PlacedFurnitureMapTest {

    private final World world = world("world");
    private final World nether = world("world_nether");
    private final PlacedFurnitureMap map = new PlacedFurnitureMap();

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private Furniture at(World in, double x, double z) {
        return new Furniture("crate", new Location(in, x, 65, z), UUID.randomUUID());
    }

    private Set<Furniture> in(World in, int x, int z) {
        return new HashSet<>(map.inChunk(new Database.ChunkKey(in.getName(), x, z)));
    }

    @Test
    void editingAPassedOrReturnedLocationCannotMoveAPieceBehindTheIndex() {
        Location placed = new Location(world, 0.5, 65, 0.5);
        Furniture piece = new Furniture("crate", placed, UUID.randomUUID());
        map.put(piece.getEntityId(), piece);

        placed.add(100, 0, 100);
        piece.getLoc().add(200, 0, 200);

        assertEquals(0.5, piece.getLoc().getX());
        assertEquals(Set.of(piece), in(world, 0, 0));
        piece.setLoc(new Location(world, 40, 65, 40));
        assertEquals(Set.of(piece), in(world, 2, 2));
        assertTrue(in(world, 0, 0).isEmpty());
    }

    @Test
    void piecesAreFoundByTheChunkOfTheirBlock() {
        Furniture origin = at(world, 0.5, 15.9);
        Furniture west = at(world, -0.1, 3);
        Furniture far = at(world, 100, -100);
        Furniture other = at(nether, 0.5, 0.5);
        for (Furniture f : List.of(origin, west, far, other)) {
            assertNull(map.put(f.getEntityId(), f));
        }

        assertEquals(Set.of(origin), in(world, 0, 0));
        assertEquals(Set.of(west), in(world, -1, 0));
        assertEquals(Set.of(far), in(world, 6, -7));
        assertEquals(Set.of(other), in(nether, 0, 0));
        assertEquals(Set.of(), in(world, 1, 1));
        assertThrows(UnsupportedOperationException.class,
                () -> map.inChunk(new Database.ChunkKey("world", 0, 0)).clear());
    }

    @Test
    void movingAPieceMovesItInTheIndexAndAPieceWithoutAWorldIsInNoChunk() {
        Furniture crate = at(world, 1, 1);
        map.put(crate.getEntityId(), crate);

        crate.setLoc(new Location(world, 2, 70, 3));
        assertEquals(Set.of(crate), in(world, 0, 0));
        crate.setLoc(new Location(world, 40, 65, 1));
        assertEquals(Set.of(), in(world, 0, 0));
        assertEquals(Set.of(crate), in(world, 2, 0));
        crate.setLoc(null);
        assertEquals(Set.of(), in(world, 2, 0));
        crate.setLoc(new Location(null, 1, 1, 1));
        crate.setLoc(new Location(nether, 1, 1, 1));
        assertEquals(Set.of(crate), in(nether, 0, 0));

        Location unloaded = spy(new Location(world, 1, 1, 1));
        doThrow(new IllegalArgumentException("World unloaded")).when(unloaded).getWorld();
        assertNull(PlacedFurnitureMap.chunkOf(unloaded));
    }

    @Test
    void aPieceLeavesTheIndexOnlyWithItsLastKeyAndStopsReportingMoves() {
        Furniture crate = at(world, 1, 1);
        UUID second = UUID.randomUUID();
        map.put(crate.getEntityId(), crate);
        map.put(second, crate);
        assertSame(crate, map.put(second, crate));

        assertSame(crate, map.remove(crate.getEntityId()));
        assertEquals(Set.of(crate), in(world, 0, 0));
        assertNull(map.remove(UUID.randomUUID()));
        map.remove(second);
        assertEquals(Set.of(), in(world, 0, 0));

        crate.setLoc(new Location(world, 1, 1, 1));
        assertEquals(Set.of(), in(world, 0, 0));
        map.relocate(crate);
        assertTrue(map.isEmpty());
    }

    @Test
    void replacingAValueReindexesUnderTheSameKey() {
        Furniture before = at(world, 1, 1);
        Furniture after = at(world, 20, 1);
        UUID key = before.getEntityId();
        map.put(key, before);
        assertSame(before, map.put(key, after));
        assertEquals(Set.of(), in(world, 0, 0));
        assertEquals(Set.of(after), in(world, 1, 0));

        Map.Entry<UUID, Furniture> entry = map.entrySet().iterator().next();
        assertSame(after, entry.setValue(before));
        assertEquals(Set.of(before), in(world, 0, 0));
        assertEquals(Set.of(), in(world, 1, 0));
        assertEquals(Map.entry(key, before), entry);
        assertTrue(entry.equals(Map.entry(key, before)));
        assertEquals(Map.entry(key, before).hashCode(), entry.hashCode());

        map.put(key, null);
        assertEquals(Set.of(), in(world, 0, 0));
        assertTrue(map.containsKey(key));
        map.put(key, null);
        assertNull(map.get(key));
    }

    @Test
    void removingThroughEveryViewLeavesTheIndex() {
        List<Furniture> pieces = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Furniture f = at(world, 1 + i, 1);
            pieces.add(f);
            map.put(f.getEntityId(), f);
        }
        Furniture byKey = pieces.get(0);
        Furniture byValue = pieces.get(1);
        Furniture byEntry = pieces.get(2);

        assertTrue(map.keySet().contains(byKey.getEntityId()));
        assertTrue(map.values().contains(byValue));
        assertTrue(map.containsValue(byEntry));
        assertEquals(6, map.keySet().size());
        assertEquals(6, map.values().size());
        assertEquals(6, map.entrySet().size());

        assertTrue(map.keySet().remove(byKey.getEntityId()));
        assertTrue(map.values().removeIf(f -> f == byValue));
        Iterator<Map.Entry<UUID, Furniture>> entries = map.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == byEntry) {
                entries.remove();
            }
        }
        assertEquals(new HashSet<>(pieces.subList(3, 6)), in(world, 0, 0));
        assertFalse(map.keySet().contains(byKey.getEntityId()));
        assertFalse(map.values().contains(byValue));

        map.values().clear();
        assertEquals(Set.of(), in(world, 0, 0));
        assertTrue(map.isEmpty());
    }

    @Test
    void clearingEmptiesTheIndexAndForgetsOnlyItsOwnPieces() {
        Furniture mine = at(world, 1, 1);
        Furniture shared = at(world, 2, 2);
        PlacedFurnitureMap other = new PlacedFurnitureMap();
        map.put(mine.getEntityId(), mine);
        map.put(shared.getEntityId(), shared);
        other.put(shared.getEntityId(), shared);

        map.clear();
        assertEquals(Set.of(), in(world, 0, 0));
        assertNull(mine.index);
        assertSame(other, shared.index);
        shared.setLoc(new Location(world, 30, 1, 1));
        assertEquals(Set.of(shared), new HashSet<>(other.inChunk(new Database.ChunkKey("world", 1, 0))));
        assertEquals(Set.of(), in(world, 1, 0));

        map.put(shared.getEntityId(), shared);
        other.remove(shared.getEntityId());
        assertSame(map, shared.index);
    }

    @Test
    void iteratesInTheSameOrderAsAHashMap() {
        Map<UUID, Furniture> plain = new HashMap<>();
        Map<UUID, Furniture> mixed = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            Furniture f = at(world, i * 7, i * 3);
            plain.put(f.getEntityId(), f);
            mixed.put(f.getEntityId(), f);
        }
        map.putAll(mixed);

        assertEquals(new ArrayList<>(plain.keySet()), new ArrayList<>(map.keySet()));
        assertEquals(new ArrayList<>(plain.values()), new ArrayList<>(map.values()));
        assertEquals(plain, map);
        assertEquals(plain.hashCode(), map.hashCode());
    }
}

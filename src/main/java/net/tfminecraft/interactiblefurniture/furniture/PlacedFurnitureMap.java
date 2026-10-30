package net.tfminecraft.interactiblefurniture.furniture;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import net.tfminecraft.interactiblefurniture.database.Database;

/**
 * The live furniture by display id, also indexed by the chunk each piece's
 * location is in, so finding one chunk's furniture does not walk all of it.
 *
 * It wraps a {@link HashMap} and iterates in the same order. Every change
 * made through the map, its views or their iterators keeps the index current,
 * and a piece reports its own moves ({@link Furniture#setLoc} and the other
 * writes of its location). A piece's location must not be changed in place.
 */
public final class PlacedFurnitureMap extends AbstractMap<UUID, Furniture> {

    private final HashMap<UUID, Furniture> entries = new HashMap<>();
    /** Each indexed piece, compared by identity, with its chunk and the number of keys it is under. */
    private final Map<Furniture, Indexed> indexed = new IdentityHashMap<>();
    private final Map<Database.ChunkKey, Set<Furniture>> byChunk = new HashMap<>();
    private Set<Map.Entry<UUID, Furniture>> entrySet;
    private Set<UUID> keySet;
    private Collection<Furniture> values;

    private static final class Indexed {
        private Database.ChunkKey chunk;
        private int keys;
    }

    /**
     * The pieces whose location is in this chunk, carried ones included, as a
     * read-only live view. Pieces without a location or world are in no chunk.
     */
    public Collection<Furniture> inChunk(Database.ChunkKey chunk) {
        Set<Furniture> found = byChunk.get(chunk);
        return found == null ? Set.of() : Collections.unmodifiableSet(found);
    }

    /** The chunk a location is in, as {@link Database.ChunkKey#fromLocation} names it, or null. */
    static Database.ChunkKey chunkOf(Location loc) {
        if (loc == null) {
            return null;
        }
        World world;
        try {
            world = loc.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
        if (world == null) {
            return null;
        }
        return new Database.ChunkKey(world.getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /** Called by a piece after its location changed. */
    void relocate(Furniture furniture) {
        Indexed entry = indexed.get(furniture);
        if (entry == null) {
            return;
        }
        Database.ChunkKey now = chunkOf(furniture.getLoc());
        if (Objects.equals(now, entry.chunk)) {
            return;
        }
        unbucket(entry.chunk, furniture);
        entry.chunk = now;
        bucket(now, furniture);
    }

    private void link(Furniture furniture) {
        if (furniture == null) {
            return;
        }
        Indexed entry = indexed.get(furniture);
        if (entry == null) {
            entry = new Indexed();
            entry.chunk = chunkOf(furniture.getLoc());
            indexed.put(furniture, entry);
            bucket(entry.chunk, furniture);
            furniture.index = this;
        }
        entry.keys++;
    }

    private void unlink(Furniture furniture) {
        Indexed entry = indexed.get(furniture);
        if (entry == null || --entry.keys > 0) {
            return;
        }
        indexed.remove(furniture);
        unbucket(entry.chunk, furniture);
        if (furniture.index == this) {
            furniture.index = null;
        }
    }

    private void bucket(Database.ChunkKey chunk, Furniture furniture) {
        if (chunk != null) {
            byChunk.computeIfAbsent(chunk, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(furniture);
        }
    }

    private void unbucket(Database.ChunkKey chunk, Furniture furniture) {
        Set<Furniture> found = chunk != null ? byChunk.get(chunk) : null;
        if (found != null && found.remove(furniture) && found.isEmpty()) {
            byChunk.remove(chunk);
        }
    }

    private void replaced(Furniture old, Furniture now) {
        if (old != now) {
            link(now);
            unlink(old);
        }
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return entries.containsValue(value);
    }

    @Override
    public Furniture get(Object key) {
        return entries.get(key);
    }

    @Override
    public Furniture put(UUID key, Furniture value) {
        Furniture old = entries.put(key, value);
        replaced(old, value);
        return old;
    }

    @Override
    public Furniture remove(Object key) {
        Furniture old = entries.remove(key);
        unlink(old);
        return old;
    }

    @Override
    public void clear() {
        entries.clear();
        for (Furniture furniture : indexed.keySet()) {
            if (furniture.index == this) {
                furniture.index = null;
            }
        }
        indexed.clear();
        byChunk.clear();
    }

    @Override
    public Set<Map.Entry<UUID, Furniture>> entrySet() {
        if (entrySet == null) {
            entrySet = new AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<UUID, Furniture>> iterator() {
                    return new Tracking<>() {
                        @Override
                        Map.Entry<UUID, Furniture> view(Map.Entry<UUID, Furniture> entry) {
                            return new Entry(entry);
                        }
                    };
                }

                @Override
                public int size() {
                    return entries.size();
                }
            };
        }
        return entrySet;
    }

    @Override
    public Set<UUID> keySet() {
        if (keySet == null) {
            keySet = new AbstractSet<>() {
                @Override
                public Iterator<UUID> iterator() {
                    return new Tracking<>() {
                        @Override
                        UUID view(Map.Entry<UUID, Furniture> entry) {
                            return entry.getKey();
                        }
                    };
                }

                @Override
                public int size() {
                    return entries.size();
                }

                @Override
                public boolean contains(Object key) {
                    return entries.containsKey(key);
                }
            };
        }
        return keySet;
    }

    @Override
    public Collection<Furniture> values() {
        if (values == null) {
            values = new AbstractCollection<>() {
                @Override
                public Iterator<Furniture> iterator() {
                    return new Tracking<>() {
                        @Override
                        Furniture view(Map.Entry<UUID, Furniture> entry) {
                            return entry.getValue();
                        }
                    };
                }

                @Override
                public int size() {
                    return entries.size();
                }

                @Override
                public boolean contains(Object value) {
                    return entries.containsValue(value);
                }
            };
        }
        return values;
    }

    /** Walks the wrapped map, so removing through the iterator also leaves the index. */
    private abstract class Tracking<T> implements Iterator<T> {
        private final Iterator<Map.Entry<UUID, Furniture>> walk = entries.entrySet().iterator();
        private Map.Entry<UUID, Furniture> last;

        abstract T view(Map.Entry<UUID, Furniture> entry);

        @Override
        public boolean hasNext() {
            return walk.hasNext();
        }

        @Override
        public T next() {
            last = walk.next();
            return view(last);
        }

        @Override
        public void remove() {
            walk.remove();
            unlink(last.getValue());
        }
    }

    private final class Entry implements Map.Entry<UUID, Furniture> {
        private final Map.Entry<UUID, Furniture> wrapped;

        private Entry(Map.Entry<UUID, Furniture> wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public UUID getKey() {
            return wrapped.getKey();
        }

        @Override
        public Furniture getValue() {
            return wrapped.getValue();
        }

        @Override
        public Furniture setValue(Furniture value) {
            Furniture old = wrapped.setValue(value);
            replaced(old, value);
            return old;
        }

        @Override
        public boolean equals(Object other) {
            return wrapped.equals(other);
        }

        @Override
        public int hashCode() {
            return wrapped.hashCode();
        }
    }
}

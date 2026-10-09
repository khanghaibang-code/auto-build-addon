package com.example.addon.autobuild;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads .litematic (Litematica), .nbt (vanilla structure) and .schem (Sponge v2/v3). */
public final class SchematicLoader {
    private SchematicLoader() {}

    /** Resolves a name/path. Relative names are looked up in <minecraft>/schematics. */
    public static File resolve(String name, File runDirectory) {
        File f = new File(name);
        if (!f.isAbsolute()) f = new File(new File(runDirectory, "schematics"), name);
        if (f.isFile()) return f;
        for (String ext : new String[]{".litematic", ".schem", ".nbt"}) {
            File g = new File(f.getPath() + ext);
            if (g.isFile()) return g;
        }
        return f;
    }

    public static Schematic load(File file) throws IOException {
        String n = file.getName().toLowerCase();
        if (n.endsWith(".litematic")) return litematic(file);
        if (n.endsWith(".schem")) return sponge(file);
        if (n.endsWith(".nbt")) return structure(file);
        throw new IOException("Unsupported format (use .litematic, .schem or .nbt)");
    }

    // ------------------------------------------------------------------ litematic

    private static Schematic litematic(File file) throws IOException {
        Map<String, Object> root = Nbt.readGzip(file);
        Map<String, Object> regions = Nbt.map(root.get("Regions"));

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object o : regions.values()) {
            int[] b = regionBounds(Nbt.map(o)); // minX,minY,minZ,sizeX,sizeY,sizeZ (sizes are absolute)
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[0] + b[3]);
            maxY = Math.max(maxY, b[1] + b[4]);
            maxZ = Math.max(maxZ, b[2] + b[5]);
        }
        if (regions.isEmpty()) throw new IOException("Schematic has no regions");

        Schematic schem = new Schematic(maxX - minX, maxY - minY, maxZ - minZ);

        for (Object o : regions.values()) {
            Map<String, Object> region = Nbt.map(o);
            int[] b = regionBounds(region);

            List<Object> paletteTag = Nbt.list(region.get("BlockStatePalette"));
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                Map<String, Object> entry = Nbt.map(paletteTag.get(i));
                Map<String, String> props = new HashMap<>();
                if (entry.containsKey("Properties")) {
                    for (Map.Entry<String, Object> e : Nbt.map(entry.get("Properties")).entrySet()) {
                        props.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                palette[i] = state((String) entry.get("Name"), props);
            }

            long[] data = (long[]) region.get("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            long mask = (1L << bits) - 1L;
            int ax = b[3], ay = b[4], az = b[5];
            long total = (long) ax * ay * az;

            for (long i = 0; i < total; i++) {
                // Litematica packs values back to back across longs (no padding).
                long bitIndex = i * bits;
                int start = (int) (bitIndex >>> 6);
                int end = (int) (((i + 1) * bits - 1) >>> 6);
                int offset = (int) (bitIndex & 63L);
                long v;
                if (start == end) v = (data[start] >>> offset) & mask;
                else v = ((data[start] >>> offset) | (data[end] << (64 - offset))) & mask;

                int id = (int) v;
                if (id >= palette.length) continue;
                int x = (int) (i % ax);
                int z = (int) ((i / ax) % az);
                int y = (int) (i / ((long) ax * az));
                schem.set(b[0] - minX + x, b[1] - minY + y, b[2] - minZ + z, palette[id]);
            }
        }
        return schem;
    }

    /** Returns {minX,minY,minZ,absSizeX,absSizeY,absSizeZ}. Litematica sizes may be negative. */
    private static int[] regionBounds(Map<String, Object> region) {
        Map<String, Object> pos = Nbt.map(region.get("Position"));
        Map<String, Object> size = Nbt.map(region.get("Size"));
        int[] out = new int[6];
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            int p = Nbt.intOf(pos.get(axes[i]));
            int s = Nbt.intOf(size.get(axes[i]));
            out[i] = s >= 0 ? p : p + s + 1;
            out[3 + i] = Math.abs(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ vanilla structure (.nbt)

    private static Schematic structure(File file) throws IOException {
        Map<String, Object> root = Nbt.readGzip(file);
        List<Object> size = Nbt.list(root.get("size"));
        Schematic schem = new Schematic(Nbt.intOf(size.get(0)), Nbt.intOf(size.get(1)), Nbt.intOf(size.get(2)));

        List<Object> paletteTag = Nbt.list(root.get("palette"));
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            Map<String, Object> entry = Nbt.map(paletteTag.get(i));
            Map<String, String> props = new HashMap<>();
            if (entry.containsKey("Properties")) {
                for (Map.Entry<String, Object> e : Nbt.map(entry.get("Properties")).entrySet()) {
                    props.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            palette[i] = state((String) entry.get("Name"), props);
        }

        for (Object bo : Nbt.list(root.get("blocks"))) {
            Map<String, Object> block = Nbt.map(bo);
            List<Object> p = Nbt.list(block.get("pos"));
            int id = Nbt.intOf(block.get("state"));
            if (id < palette.length) schem.set(Nbt.intOf(p.get(0)), Nbt.intOf(p.get(1)), Nbt.intOf(p.get(2)), palette[id]);
        }
        return schem;
    }

    // ------------------------------------------------------------------ Sponge (.schem)

    private static Schematic sponge(File file) throws IOException {
        Map<String, Object> root = Nbt.readGzip(file);
        if (root.containsKey("Schematic")) root = Nbt.map(root.get("Schematic")); // v3 wrapper

        int w = Nbt.intOf(root.get("Width"));
        int h = Nbt.intOf(root.get("Height"));
        int l = Nbt.intOf(root.get("Length"));

        Map<String, Object> paletteTag;
        byte[] data;
        if (root.containsKey("Blocks")) { // v3
            Map<String, Object> blocks = Nbt.map(root.get("Blocks"));
            paletteTag = Nbt.map(blocks.get("Palette"));
            data = (byte[]) blocks.get("Data");
        } else { // v2
            paletteTag = Nbt.map(root.get("Palette"));
            data = (byte[]) root.get("BlockData");
        }

        int maxId = 0;
        for (Object v : paletteTag.values()) maxId = Math.max(maxId, Nbt.intOf(v));
        BlockState[] palette = new BlockState[maxId + 1];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            palette[Nbt.intOf(e.getValue())] = fromString(e.getKey());
        }

        Schematic schem = new Schematic(w, h, l);
        int idx = 0;
        long total = (long) w * h * l;
        for (long i = 0; i < total && idx < data.length; i++) {
            int value = 0, shift = 0, b;
            do { // varint
                b = data[idx++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && idx < data.length);

            if (value < palette.length && palette[value] != null) {
                int x = (int) (i % w);
                int z = (int) ((i / w) % l);
                int y = (int) (i / ((long) w * l));
                schem.set(x, y, z, palette[value]);
            }
        }
        return schem;
    }

    // ------------------------------------------------------------------ block state parsing

    /** "minecraft:oak_stairs[facing=north,half=bottom]" */
    private static BlockState fromString(String s) {
        int br = s.indexOf('[');
        String name = br < 0 ? s : s.substring(0, br);
        Map<String, String> props = new HashMap<>();
        if (br >= 0 && s.endsWith("]")) {
            for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return state(name, props);
    }

    private static BlockState state(String name, Map<String, String> props) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !Registries.BLOCK.containsId(id)) return Blocks.AIR.getDefaultState(); // unknown block -> skipped
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        for (Map.Entry<String, String> e : props.entrySet()) {
            Property<?> property = state.getBlock().getStateManager().getProperty(e.getKey());
            if (property != null) state = withValue(state, property, e.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(v -> state.with(property, v)).orElse(state);
    }
}

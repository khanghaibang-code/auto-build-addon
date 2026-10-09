package com.example.addon.autobuild;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Tiny NBT reader. Returns plain Java objects:
 * compound -> Map, list -> List, byte[]/int[]/long[] -> arrays, numbers -> boxed Number, string -> String.
 * Deliberately independent of Minecraft's own NBT classes so it keeps working when those change.
 */
public final class Nbt {
    private Nbt() {}

    public static Map<String, Object> readGzip(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("Root tag is not a compound");
            in.readUTF(); // root name
            return compound(in);
        }
    }

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        int type;
        while ((type = in.readUnsignedByte()) != 0) {
            String name = in.readUTF();
            map.put(name, value(in, type));
        }
        return map;
    }

    private static Object value(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) list.add(value(in, elementType));
                return list;
            }
            case 10: return compound(in);
            case 11: {
                int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }

    // ---- small helpers so callers don't need casts everywhere ----

    public static int intOf(Object o) {
        return ((Number) o).intValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return (List<Object>) o;
    }
}

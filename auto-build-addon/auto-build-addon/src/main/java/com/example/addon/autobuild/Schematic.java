package com.example.addon.autobuild;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.Arrays;

/** A loaded schematic: a box of block states, local coordinates start at (0,0,0). */
public final class Schematic {
    public final int sizeX, sizeY, sizeZ;
    private final BlockState[] states;
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    public Schematic(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.states = new BlockState[sizeX * sizeY * sizeZ];
        Arrays.fill(states, AIR);
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public BlockState get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return AIR;
        return states[(y * sizeZ + z) * sizeX + x];
    }

    public void set(int x, int y, int z, BlockState state) {
        if (inBounds(x, y, z)) states[(y * sizeZ + z) * sizeX + x] = state;
    }
}

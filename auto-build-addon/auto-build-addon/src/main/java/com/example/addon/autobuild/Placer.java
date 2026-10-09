package com.example.addon.autobuild;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Works out HOW to place a block so the resulting state equals the schematic state.
 *
 * Instead of hard-coding stairs / slabs / logs / pistons ..., it asks the game itself:
 * for every candidate (face to click, hit height, yaw, pitch) it runs Block#getPlacementState with a
 * fake ItemPlacementContext and keeps the first candidate whose result matches the target.
 */
public final class Placer {
    private Placer() {}

    public enum Result { OK, NO_SUPPORT, TOO_FAR, ENTITY, NO_STATE }

    public record Plan(BlockPos clicked, Direction side, Vec3d hit, float yaw, float pitch, boolean sneak) {}

    public record Solution(Result result, Plan plan) {
        static Solution of(Result r) { return new Solution(r, null); }
    }

    private record Face(BlockPos clicked, Direction side, Vec3d center) {}

    /** Only these properties are decided by HOW we place; the rest (powered, shape, open...) is ignored. */
    private static final Set<String> PLACEMENT_PROPS = Set.of(
        "facing", "half", "type", "axis", "face", "rotation", "hanging", "orientation", "attachment", "part",
        "layers", "candles", "pickles", "eggs"
    );

    private static final float[] YAWS = {0f, 90f, 180f, 270f};
    private static final float[] PITCHES = {0f, 90f, -90f};

    // ------------------------------------------------------------------ comparison

    /** True if 'actual' is the same block and has the same placement-relevant properties as 'target'. */
    public static boolean same(BlockState actual, BlockState target) {
        if (actual.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (PLACEMENT_PROPS.contains(p.getName()) && !actual.get(p).equals(target.get(p))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ sneak

    /** Blocks that open a GUI / toggle when right clicked: we must sneak to place against them. */
    public static boolean needsSneak(BlockState state) {
        Block b = state.getBlock();
        return b instanceof BlockWithEntity
            || b instanceof CraftingTableBlock
            || b instanceof AnvilBlock
            || b instanceof DoorBlock
            || b instanceof TrapdoorBlock
            || b instanceof FenceGateBlock
            || b instanceof ButtonBlock
            || b instanceof LeverBlock
            || b instanceof BedBlock
            || b instanceof NoteBlock
            || b instanceof RepeaterBlock
            || b instanceof ComparatorBlock
            || b instanceof GrindstoneBlock
            || b instanceof StonecutterBlock
            || b instanceof LoomBlock
            || b instanceof CartographyTableBlock
            || b instanceof SmithingTableBlock
            || b instanceof FlowerPotBlock
            || b instanceof CakeBlock
            || b instanceof ComposterBlock
            || b instanceof AbstractCauldronBlock
            || b instanceof RespawnAnchorBlock
            || b instanceof DragonEggBlock;
    }

    // ------------------------------------------------------------------ normal blocks

    public static Solution solveBlock(BlockState target, BlockPos pos, double reach) {
        Block block = target.getBlock();
        Item item = block.asItem();
        if (item == Items.AIR) return Solution.of(Result.NO_STATE);

        ClientPlayerEntity player = mc.player;
        Vec3d eye = player.getEyePos();
        BlockState existing = mc.world.getBlockState(pos);

        List<Face> faces = faces(pos, existing, block, eye);
        if (faces.isEmpty()) return Solution.of(Result.NO_SUPPORT);

        ItemStack stack = new ItemStack(item);
        float oldYaw = player.getYaw(), oldPitch = player.getPitch();
        boolean tooFar = false, entity = false;

        try {
            for (Face face : faces) {
                for (Vec3d hit : hits(face)) {
                    if (eye.distanceTo(hit) > reach) {
                        tooFar = true;
                        continue;
                    }

                    BlockHitResult hitResult = new BlockHitResult(hit, face.side(), face.clicked(), false);

                    for (float pitch : PITCHES) {
                        for (float yaw : YAWS) {
                            player.setYaw(yaw);
                            player.setPitch(pitch);

                            ItemPlacementContext ctx = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hitResult);
                            if (!ctx.getBlockPos().equals(pos)) continue; // would land somewhere else

                            BlockState state = block.getPlacementState(ctx);
                            if (state == null || !same(state, target)) continue;
                            if (!state.canPlaceAt(mc.world, pos)) continue;

                            if (!mc.world.canPlace(state, pos, ShapeContext.of(player))) {
                                entity = true; // something (probably us) is standing in the way
                                continue;
                            }

                            boolean sneak = needsSneak(mc.world.getBlockState(face.clicked()));
                            return new Solution(Result.OK, new Plan(face.clicked(), face.side(), hit, yaw, pitch, sneak));
                        }
                    }
                }
            }
        } finally {
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
        }

        if (entity) return Solution.of(Result.ENTITY);
        return Solution.of(tooFar ? Result.TOO_FAR : Result.NO_STATE);
    }

    private static List<Face> faces(BlockPos pos, BlockState existing, Block block, Vec3d eye) {
        List<Face> list = new ArrayList<>();

        // Click an existing neighbour; the face looking at 'pos' is the opposite of the direction we offset by.
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = mc.world.getBlockState(n);
            if (ns.isAir() || ns.isReplaceable()) continue; // can't click air / water / grass
            Direction side = d.getOpposite();
            list.add(new Face(n, side, faceCenter(n, side)));
        }

        // Same block already there (e.g. second half of a double slab, more candles...): click it directly.
        if (existing.getBlock() == block) {
            list.add(new Face(pos, Direction.UP, faceCenter(pos, Direction.UP)));
            list.add(new Face(pos, Direction.DOWN, faceCenter(pos, Direction.DOWN)));
        }

        list.sort(Comparator.comparingDouble(f -> f.center().squaredDistanceTo(eye)));
        return list;
    }

    private static Vec3d faceCenter(BlockPos b, Direction side) {
        return new Vec3d(
            b.getX() + 0.5 + side.getOffsetX() * 0.5,
            b.getY() + 0.5 + side.getOffsetY() * 0.5,
            b.getZ() + 0.5 + side.getOffsetZ() * 0.5
        );
    }

    /** Hit points to try on a face. On side faces: lower half (bottom slab/stairs) and upper half (top). */
    private static List<Vec3d> hits(Face face) {
        List<Vec3d> list = new ArrayList<>(2);
        if (face.side().getAxis().isVertical()) {
            list.add(face.center());
        } else {
            Vec3d c = face.center();
            double base = face.clicked().getY();
            list.add(new Vec3d(c.x, base + 0.25, c.z));
            list.add(new Vec3d(c.x, base + 0.75, c.z));
        }
        return list;
    }

    // ------------------------------------------------------------------ buckets (water / lava)

    /** Where to aim a bucket so the fluid appears at 'pos' (it is placed on the face of a neighbour). */
    public static Solution solveFluid(BlockPos pos, boolean water, double reach) {
        Vec3d eye = mc.player.getEyePos();
        boolean support = false, tooFar = false;

        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = mc.world.getBlockState(n);
            if (ns.isAir() || ns.isReplaceable()) continue;
            // Water would fill a waterloggable neighbour instead of our target cell.
            if (water && ns.getBlock() instanceof FluidFillable) continue;

            support = true;
            Solution s = aim(n, d.getOpposite(), eye, reach);
            if (s.result() == Result.OK) return s;
            if (s.result() == Result.TOO_FAR) tooFar = true;
        }

        if (!support) return Solution.of(Result.NO_SUPPORT);
        return Solution.of(tooFar ? Result.TOO_FAR : Result.NO_STATE);
    }

    /** Aim a water bucket at an already placed waterloggable block so that it becomes waterlogged. */
    public static Solution solveWaterlog(BlockPos pos, double reach) {
        Vec3d eye = mc.player.getEyePos();
        boolean tooFar = false;
        for (Direction d : Direction.values()) {
            Solution s = aim(pos, d, eye, reach);
            if (s.result() == Result.OK) return s;
            if (s.result() == Result.TOO_FAR) tooFar = true;
        }
        return Solution.of(tooFar ? Result.TOO_FAR : Result.NO_STATE);
    }

    private static Solution aim(BlockPos clicked, Direction side, Vec3d eye, double reach) {
        Vec3d target = faceCenter(clicked, side);
        if (eye.distanceTo(target) > reach) return Solution.of(Result.TOO_FAR);

        // Buckets use a real ray cast on the server, so make sure the ray really hits that face first.
        Vec3d dir = target.subtract(eye).normalize();
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye, target.add(dir.multiply(0.2)),
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player
        ));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(clicked) || hit.getSide() != side) {
            return Solution.of(Result.NO_STATE);
        }

        float yaw = (float) Rotations.getYaw(target);
        float pitch = (float) Rotations.getPitch(target);
        return new Solution(Result.OK, new Plan(clicked, side, target, yaw, pitch, false));
    }
}

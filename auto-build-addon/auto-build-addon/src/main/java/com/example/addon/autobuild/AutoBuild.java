package com.example.addon.autobuild;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto Build - builds a schematic (.litematic / .schem / .nbt) in the world.
 *
 * - places the exact block state (stairs, slabs, logs, pistons...) using Placer
 * - sneaks when it has to click an interactable block (chest, door, button...) or when "always sneak" is on
 * - places water / lava with buckets (fluids are done last)
 * - places temporary support blocks when a block has nothing to be placed against, and removes them afterwards
 * - walks (Baritone if installed) to blocks that are out of reach
 *
 * Put the schematic in <minecraft>/schematics, set its name in the module settings and enable the module
 * while standing where the schematic's minimum corner (lowest x/y/z) should be.
 */
public class AutoBuild extends Module {
    private static final int MAX_SOLVES_PER_TICK = 6;
    private static final int SCAN_BUDGET = 512;

    private static final Color SIDE = new Color(60, 160, 255, 40);
    private static final Color LINE = new Color(60, 160, 255, 200);
    private static final Color SCAFFOLD_SIDE = new Color(255, 190, 40, 40);
    private static final Color SCAFFOLD_LINE = new Color(255, 190, 40, 200);

    // ------------------------------------------------------------------ settings

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSupport = settings.createGroup("Support & Fluids");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> schematicFile = sgGeneral.add(new StringSetting.Builder()
        .name("schematic")
        .description("File name (in .minecraft/schematics) or an absolute path. .litematic, .schem and .nbt are supported.")
        .defaultValue("")
        .build());

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-x").description("Origin offset from your position when enabling.")
        .defaultValue(0).sliderRange(-64, 64).build());

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y").description("Origin offset from your position when enabling.")
        .defaultValue(0).sliderRange(-64, 64).build());

    private final Setting<Integer> offsetZ = sgGeneral.add(new IntSetting.Builder()
        .name("offset-z").description("Origin offset from your position when enabling.")
        .defaultValue(0).sliderRange(-64, 64).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Maximum placing distance (also limited by the game's reach).")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks to wait after each placement.")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Integer> retryDelay = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay").description("Ticks to wait for the server to confirm a block before trying it again.")
        .defaultValue(10).min(2).sliderMax(40).build());

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts").description("How many times to try one block before giving up on it.")
        .defaultValue(5).min(1).sliderMax(15).build());

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Hotbar slot (1-9) used to bring blocks from the inventory.")
        .defaultValue(9).range(1, 9).sliderRange(1, 9).build());

    private final Setting<Boolean> autoSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sneak").description("Sneak while clicking blocks that would otherwise open a GUI / toggle (chests, doors, buttons...).")
        .defaultValue(true).build());

    private final Setting<Boolean> alwaysSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("always-sneak").description("Sneak for every placement (not while walking).")
        .defaultValue(false).build());

    private final Setting<Boolean> autoMove = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-move").description("Walk to blocks that are out of reach (uses Baritone if installed).")
        .defaultValue(true).build());

    private final Setting<Boolean> creativeGive = sgGeneral.add(new BoolSetting.Builder()
        .name("creative-give").description("In creative mode, give yourself the needed blocks.")
        .defaultValue(true).build());

    private final Setting<Boolean> replaceWrong = sgGeneral.add(new BoolSetting.Builder()
        .name("replace-wrong-blocks").description("Break blocks that don't match the schematic (uses whatever is in your hand).")
        .defaultValue(false).build());

    private final Setting<Boolean> buildFluids = sgSupport.add(new BoolSetting.Builder()
        .name("build-fluids").description("Place water and lava source blocks with buckets (done after all other blocks).")
        .defaultValue(true).build());

    private final Setting<Boolean> useScaffold = sgSupport.add(new BoolSetting.Builder()
        .name("support-blocks").description("Place temporary blocks when a block has nothing to be placed against.")
        .defaultValue(true).build());

    private final Setting<List<Block>> scaffoldBlocks = sgSupport.add(new BlockListSetting.Builder()
        .name("support-block-types").description("Blocks used as temporary supports (first one you have is used).")
        .defaultValue(Blocks.COBBLESTONE, Blocks.DIRT, Blocks.NETHERRACK, Blocks.STONE)
        .build());

    private final Setting<Integer> scaffoldDepth = sgSupport.add(new IntSetting.Builder()
        .name("support-depth").description("Maximum length of a support chain.")
        .defaultValue(4).range(1, 10).sliderRange(1, 8).build());

    private final Setting<Boolean> removeScaffold = sgSupport.add(new BoolSetting.Builder()
        .name("remove-supports").description("Break the temporary supports when the build is finished.")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Highlight the current target and the planned supports.")
        .defaultValue(true).build());

    // ------------------------------------------------------------------ state

    private static final class Target {
        final BlockPos pos;
        final BlockState state;
        final int phase; // 0 = normal blocks, 1 = fluids (placed last)
        int index;

        Target(BlockPos pos, BlockState state, int phase) {
            this.pos = pos;
            this.state = state;
            this.phase = phase;
        }
    }

    private enum Step { PLACED, WAIT, FAIL, TOO_FAR, NO_SUPPORT, ENTITY, MISSING }

    private final Mover mover = new Mover();

    private Schematic schematic;
    private BlockPos origin;
    private Target[] queue = new Target[0];
    private boolean[] done = new boolean[0];
    private int remaining, cursor, cooldown, tickCount, scanBudget, idlePasses, placedCount;
    private boolean passProgress, stuck, finished, sneakByUs;
    private BlockPos current;

    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> moves = new HashMap<>();
    private final Map<BlockPos, Integer> supportPlans = new HashMap<>();
    private final Map<BlockPos, Integer> cooldowns = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private final Map<Item, Integer> missing = new HashMap<>();

    private final Deque<BlockPos> scaffoldQueue = new ArrayDeque<>();
    private final Set<BlockPos> scaffoldTried = new HashSet<>();
    private final List<BlockPos> placedScaffold = new ArrayList<>();

    public AutoBuild() {
        super(Categories.World, "auto-build", "Builds a schematic: exact block states, sneaking, fluids and support blocks.");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        File file = SchematicLoader.resolve(schematicFile.get().trim(), mc.runDirectory);
        if (!file.isFile()) {
            error("File not found: %s (put it in .minecraft/schematics and set the 'schematic' setting).", file.getPath());
            toggle();
            return;
        }

        try {
            schematic = SchematicLoader.load(file);
        } catch (Exception e) {
            error("Could not load schematic: %s", String.valueOf(e));
            toggle();
            return;
        }

        origin = mc.player.getBlockPos().add(offsetX.get(), offsetY.get(), offsetZ.get());

        attempts.clear();
        moves.clear();
        supportPlans.clear();
        cooldowns.clear();
        failed.clear();
        missing.clear();
        scaffoldQueue.clear();
        scaffoldTried.clear();
        placedScaffold.clear();
        cursor = cooldown = tickCount = idlePasses = placedCount = 0;
        passProgress = stuck = finished = false;
        current = null;

        buildQueue();
        info("Loaded %dx%dx%d, %d blocks to place. Origin: %d %d %d.",
            schematic.sizeX, schematic.sizeY, schematic.sizeZ, queue.length, origin.getX(), origin.getY(), origin.getZ());
    }

    @Override
    public void onDeactivate() {
        mover.stop();
        if (sneakByUs) setSneak(false);
        InvUtils.swapBack();
        schematic = null;
        queue = new Target[0];
        done = new boolean[0];
        scaffoldQueue.clear();
        current = null;
    }

    @Override
    public String getInfoString() {
        return schematic == null ? null : remaining + " left";
    }

    private void buildQueue() {
        List<Target> list = new ArrayList<>();
        for (int y = 0; y < schematic.sizeY; y++) {
            for (int z = 0; z < schematic.sizeZ; z++) {
                for (int x = 0; x < schematic.sizeX; x++) {
                    BlockState s = schematic.get(x, y, z);
                    if (!buildable(s)) continue;
                    list.add(new Target(origin.add(x, y, z), s, s.getBlock() instanceof FluidBlock ? 1 : 0));
                }
            }
        }

        // Normal blocks first, fluids last; bottom-up; zig-zag inside a layer to keep walking short.
        list.sort(Comparator
            .comparingInt((Target t) -> t.phase)
            .thenComparingInt(t -> t.pos.getY())
            .thenComparingInt(t -> t.pos.getZ())
            .thenComparingInt(t -> (t.pos.getZ() & 1) == 0 ? t.pos.getX() : -t.pos.getX()));

        queue = list.toArray(new Target[0]);
        for (int i = 0; i < queue.length; i++) queue[i].index = i;
        done = new boolean[queue.length];
        remaining = queue.length;
    }

    private boolean buildable(BlockState s) {
        if (s.isAir()) return false;
        Block b = s.getBlock();

        if (b instanceof FluidBlock) { // only still sources, flowing water/lava appears by itself
            return buildFluids.get() && (b == Blocks.WATER || b == Blocks.LAVA) && s.get(FluidBlock.LEVEL) == 0;
        }

        if (b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON || b == Blocks.STRUCTURE_VOID) return false;
        // Second halves are created together with the first half.
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return false;

        return b.asItem() != Items.AIR;
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (schematic == null || finished || mc.player == null || mc.world == null) return;
        tickCount++;
        scanBudget = SCAN_BUDGET;
        current = null;

        // Walking: don't sneak, let the mover work.
        if (mover.isMoving()) {
            if (sneakByUs && !alwaysSneak.get()) setSneak(false);
            mover.tick();
            if (mover.isMoving()) return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // A support chain is being built for some block.
        if (!scaffoldQueue.isEmpty()) {
            stepScaffold();
            return;
        }

        for (int i = 0; i < MAX_SOLVES_PER_TICK; i++) {
            Target t = nextTarget();
            if (t == null) break;
            current = t.pos;
            if (process(t)) {
                passProgress = true;
                return;
            }
        }

        if (stuck) {
            warning("%d blocks could not be placed (no valid way to place them).", remaining);
            failRemaining();
        }

        if (remaining <= 0) {
            if (removeScaffold.get() && !placedScaffold.isEmpty()) stepCleanup();
            else finishBuild();
        }
    }

    /** Finds the next block that still needs work. Already correct blocks are skipped cheaply. */
    private Target nextTarget() {
        while (scanBudget > 0 && remaining > 0) {
            scanBudget--;

            if (cursor >= queue.length) { // finished a pass over the whole queue
                cursor = 0;
                idlePasses = passProgress ? 0 : idlePasses + 1;
                passProgress = false;
                if (idlePasses >= 2) {
                    stuck = true;
                    return null;
                }
            }

            Target t = queue[cursor++];
            if (done[t.index]) continue;

            BlockState actual = mc.world.getBlockState(t.pos);
            if (isSatisfied(actual, t.state) && !needsWaterlog(actual, t.state)) {
                markDone(t);
                continue;
            }

            if (cooldowns.getOrDefault(t.pos, 0) > tickCount) { // waiting for the server
                passProgress = true;
                continue;
            }
            return t;
        }
        return null;
    }

    /** @return true if something was done this tick (placed / waiting / moving) */
    private boolean process(Target t) {
        BlockPos pos = t.pos;
        BlockState target = t.state;
        BlockState actual = mc.world.getBlockState(pos);

        if (target.getBlock() instanceof FluidBlock) return processFluid(t, actual);
        if (isSatisfied(actual, target)) return processWaterlog(t); // block is right, only water missing

        // Something else is in the way.
        if (!actual.isAir() && !actual.isReplaceable() && actual.getBlock() != target.getBlock()) {
            if (replaceWrong.get() && BlockUtils.canBreak(pos)) {
                if (!syncSneak(false)) return true;
                if (eyeDistance(pos) > reach()) return moveTo(t);
                BlockUtils.breakBlock(pos, true);
                return true;
            }
            fail(t);
            return false;
        }

        switch (attempt(pos, target)) {
            case PLACED:
                onPlaced(t);
                return true;
            case WAIT:
                return true;
            case TOO_FAR:
                return moveTo(t);
            case ENTITY:
                return nudgeAway(t);
            case NO_SUPPORT:
                return planSupport(t);
            case MISSING:
                fail(t);
                return false;
            default: // FAIL: no valid placement right now (e.g. needs a support block that comes later) -> retry in next pass
                return false;
        }
    }

    // ------------------------------------------------------------------ placing

    private Step attempt(BlockPos pos, BlockState state) {
        Placer.Solution sol = Placer.solveBlock(state, pos, reach());
        switch (sol.result()) {
            case NO_SUPPORT: return Step.NO_SUPPORT;
            case TOO_FAR: return Step.TOO_FAR;
            case ENTITY: return Step.ENTITY;
            case NO_STATE: return Step.FAIL;
            default: break;
        }

        Item item = state.getBlock().asItem();
        int slot = prepareItem(item);
        if (slot < 0) {
            missing.merge(item, 1, Integer::sum);
            return Step.MISSING;
        }

        Placer.Plan plan = sol.plan();
        if (!syncSneak(plan.sneak())) return Step.WAIT; // sneak state has to reach the server first

        InvUtils.swap(slot, true);
        Rotations.rotate(plan.yaw(), plan.pitch(), 50, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(plan.hit(), plan.side(), plan.clicked(), false));
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        });
        return Step.PLACED;
    }

    private void onPlaced(Target t) {
        placedCount++;
        cooldown = delay.get();
        cooldowns.put(t.pos, tickCount + retryDelay.get());
        if (bump(attempts, t.pos) > maxAttempts.get()) fail(t);
    }

    /** Makes sure 'item' is in the hotbar. Returns the hotbar slot (0-8) or -1 if we don't have it. */
    private int prepareItem(Item item) {
        FindItemResult inHotbar = InvUtils.findInHotbar(item);
        if (inHotbar.found()) return inHotbar.slot();

        int slot = hotbarSlot.get() - 1;

        FindItemResult inInventory = InvUtils.find(item);
        if (inInventory.found()) {
            InvUtils.move().from(inInventory.slot()).toHotbar(slot);
            return slot;
        }

        if (creativeGive.get() && mc.player.getAbilities().creativeMode) {
            ItemStack stack = new ItemStack(item, item.getMaxCount());
            mc.player.getInventory().setStack(slot, stack);
            mc.interactionManager.clickCreativeStack(stack, 36 + slot);
            return slot;
        }
        return -1;
    }

    private boolean hasItem(Item item) {
        return InvUtils.find(item).found() || (creativeGive.get() && mc.player.getAbilities().creativeMode);
    }

    // ------------------------------------------------------------------ sneak

    /** @return true when the sneak state already matches (we can act now), false if we had to change it (wait a tick). */
    private boolean syncSneak(boolean wanted) {
        boolean want = alwaysSneak.get() || (autoSneak.get() && wanted);
        if (mc.player.isSneaking() == want) return true;
        setSneak(want);
        return false;
    }

    private void setSneak(boolean sneak) {
        mc.options.sneakKey.setPressed(sneak);
        sneakByUs = sneak;
    }

    // ------------------------------------------------------------------ fluids

    private boolean processFluid(Target t, BlockState actual) {
        if (!actual.isAir() && !actual.isReplaceable()) { // a solid block is where the fluid should be
            fail(t);
            return false;
        }

        boolean water = t.state.getBlock() == Blocks.WATER;
        Item bucket = water ? Items.WATER_BUCKET : Items.LAVA_BUCKET;

        Placer.Solution sol = Placer.solveFluid(t.pos, water, reach());
        switch (sol.result()) {
            case OK: {
                int slot = prepareItem(bucket);
                if (slot < 0) {
                    missing.merge(bucket, 1, Integer::sum);
                    fail(t);
                    return false;
                }
                return useBucket(sol.plan(), slot, t);
            }
            case TOO_FAR:
                return moveTo(t);
            default: // no wall to aim at yet -> retry later
                return false;
        }
    }

    /** Block is placed already but the schematic wants it waterlogged. */
    private boolean processWaterlog(Target t) {
        Placer.Solution sol = Placer.solveWaterlog(t.pos, reach());
        switch (sol.result()) {
            case OK: {
                int slot = prepareItem(Items.WATER_BUCKET);
                if (slot < 0) {
                    missing.merge(Items.WATER_BUCKET, 1, Integer::sum);
                    fail(t);
                    return false;
                }
                return useBucket(sol.plan(), slot, t);
            }
            case TOO_FAR:
                return moveTo(t);
            default:
                fail(t);
                return false;
        }
    }

    private boolean useBucket(Placer.Plan plan, int slot, Target t) {
        if (!syncSneak(false)) return true;

        InvUtils.swap(slot, true);
        Rotations.rotate(plan.yaw(), plan.pitch(), 50, () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        });
        onPlaced(t);
        return true;
    }

    private boolean needsWaterlog(BlockState actual, BlockState target) {
        return buildFluids.get()
            && target.contains(Properties.WATERLOGGED) && target.get(Properties.WATERLOGGED)
            && actual.contains(Properties.WATERLOGGED) && !actual.get(Properties.WATERLOGGED);
    }

    private boolean isSatisfied(BlockState actual, BlockState target) {
        if (target.getBlock() instanceof FluidBlock) {
            return actual.getBlock() == target.getBlock() && actual.get(FluidBlock.LEVEL) == 0;
        }
        return Placer.same(actual, target);
    }

    // ------------------------------------------------------------------ support blocks

    /** Block has nothing to click against: plan a chain of temporary blocks from the nearest solid block. */
    private boolean planSupport(Target t) {
        if (!useScaffold.get()) return false;
        if (bump(supportPlans, t.pos) > 5) {
            fail(t);
            return false;
        }

        List<BlockPos> chain = findSupportChain(t.pos);
        if (chain == null || chain.isEmpty()) return false; // maybe possible later, e.g. after other blocks are placed

        scaffoldQueue.addAll(chain);
        return true;
    }

    /** Breadth-first search through free air cells. Returns cells in placing order (first one touches a solid block). */
    private List<BlockPos> findSupportChain(BlockPos target) {
        int maxDepth = scaffoldDepth.get();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        Deque<BlockPos> open = new ArrayDeque<>();

        for (Direction d : Direction.values()) {
            BlockPos c = target.offset(d);
            if (isFree(c)) {
                parent.put(c, target);
                depth.put(c, 1);
                open.add(c);
            }
        }

        while (!open.isEmpty()) {
            BlockPos c = open.poll();

            if (touchesSolid(c)) {
                List<BlockPos> chain = new ArrayList<>();
                for (BlockPos p = c; !p.equals(target); p = parent.get(p)) chain.add(p);
                return chain;
            }

            int d = depth.get(c);
            if (d >= maxDepth) continue;
            for (Direction dir : Direction.values()) {
                BlockPos n = c.offset(dir);
                if (!n.equals(target) && !parent.containsKey(n) && isFree(n)) {
                    parent.put(n, c);
                    depth.put(n, d + 1);
                    open.add(n);
                }
            }
        }
        return null;
    }

    /** Air and not a place where the schematic wants a block (so we never block our own build). */
    private boolean isFree(BlockPos p) {
        if (!mc.world.getBlockState(p).isAir()) return false;
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        return !schematic.inBounds(x, y, z) || schematic.get(x, y, z).isAir();
    }

    private boolean touchesSolid(BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockState s = mc.world.getBlockState(c.offset(d));
            if (!s.isAir() && !s.isReplaceable()) return true;
        }
        return false;
    }

    private void stepScaffold() {
        BlockPos p = scaffoldQueue.peekFirst();
        BlockState actual = mc.world.getBlockState(p);

        if (!actual.isAir() && !actual.isReplaceable()) { // placed (or already occupied)
            scaffoldQueue.pollFirst();
            if (scaffoldTried.contains(p)) placedScaffold.add(p); // only remove blocks that WE placed
            return;
        }
        if (cooldowns.getOrDefault(p, 0) > tickCount) return;

        BlockState state = null;
        for (Block b : scaffoldBlocks.get()) {
            if (hasItem(b.asItem())) {
                state = b.getDefaultState();
                break;
            }
        }
        if (state == null) {
            warning("No support blocks available (check 'support-block-types').");
            scaffoldQueue.clear();
            return;
        }

        switch (attempt(p, state)) {
            case PLACED:
                scaffoldTried.add(p);
                cooldown = delay.get();
                cooldowns.put(p, tickCount + retryDelay.get());
                if (bump(attempts, p) > maxAttempts.get()) scaffoldQueue.clear();
                break;
            case TOO_FAR:
                if (autoMove.get() && bump(moves, p) <= 6) mover.goNear(p, Math.max(1, (int) Math.floor(reach() - 2)));
                else scaffoldQueue.clear();
                break;
            case ENTITY: {
                double dx = mc.player.getX() - (p.getX() + 0.5), dz = mc.player.getZ() - (p.getZ() + 0.5);
                double len = Math.max(0.01, Math.hypot(dx, dz));
                mover.goNear(BlockPos.ofFloored(mc.player.getX() + dx / len * 3, mc.player.getY(), mc.player.getZ() + dz / len * 3), 1);
                break;
            }
            case WAIT:
                break;
            default: // NO_SUPPORT / FAIL / MISSING
                scaffoldQueue.clear();
                break;
        }
    }

    /** Breaks the temporary supports once everything else is built. */
    private void stepCleanup() {
        current = placedScaffold.get(0);
        BlockPos p = current;
        BlockState s = mc.world.getBlockState(p);

        if (s.isAir() || s.isReplaceable() || !BlockUtils.canBreak(p)) {
            placedScaffold.remove(0);
            return;
        }
        if (sneakByUs) setSneak(false);
        if (eyeDistance(p) > reach()) {
            if (autoMove.get() && bump(moves, p) <= 6) mover.goNear(p, Math.max(1, (int) Math.floor(reach() - 2)));
            else placedScaffold.remove(0);
            return;
        }
        BlockUtils.breakBlock(p, true);
    }

    // ------------------------------------------------------------------ movement helpers

    private boolean moveTo(Target t) {
        if (!autoMove.get() || bump(moves, t.pos) > 6) {
            fail(t);
            return false;
        }
        mover.goNear(t.pos, Math.max(1, (int) Math.floor(reach() - 2)));
        return true;
    }

    /** The block would be placed inside the player (or a mob): step away from it. */
    private boolean nudgeAway(Target t) {
        if (bump(moves, t.pos) > 6) {
            fail(t);
            return false;
        }
        double dx = mc.player.getX() - (t.pos.getX() + 0.5), dz = mc.player.getZ() - (t.pos.getZ() + 0.5);
        double len = Math.max(0.01, Math.hypot(dx, dz));
        mover.goNear(BlockPos.ofFloored(mc.player.getX() + dx / len * 3, mc.player.getY(), mc.player.getZ() + dz / len * 3), 1);
        return true;
    }

    private double reach() {
        return Math.min(range.get(), mc.player.getBlockInteractionRange());
    }

    private double eyeDistance(BlockPos p) {
        return mc.player.getEyePos().distanceTo(p.toCenterPos());
    }

    // ------------------------------------------------------------------ bookkeeping

    private int bump(Map<BlockPos, Integer> map, BlockPos pos) {
        return map.merge(pos, 1, Integer::sum);
    }

    private void markDone(Target t) {
        if (done[t.index]) return;
        done[t.index] = true;
        remaining--;
        passProgress = true;
    }

    private void fail(Target t) {
        if (done[t.index]) return;
        failed.add(t.pos);
        markDone(t);
    }

    private void failRemaining() {
        for (Target t : queue) {
            if (!done[t.index]) {
                failed.add(t.pos);
                done[t.index] = true;
            }
        }
        remaining = 0;
        stuck = false;
    }

    private void finishBuild() {
        finished = true;
        info("Build finished: %d placements, %d blocks skipped/failed.", placedCount, failed.size());
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Item, Integer> e : missing.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().getName().getString()).append(" x").append(e.getValue());
            }
            warning("Missing items: %s. Get them and enable the module again to finish the rest.", sb.toString());
        }
        toggle();
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || schematic == null) return;
        if (current != null) event.renderer.box(current, SIDE, LINE, ShapeMode.Both, 0);
        for (BlockPos p : scaffoldQueue) event.renderer.box(p, SCAFFOLD_SIDE, SCAFFOLD_LINE, ShapeMode.Both, 0);
    }
}

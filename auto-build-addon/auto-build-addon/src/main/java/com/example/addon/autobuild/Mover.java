package com.example.addon.autobuild;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Walks the player close to a position.
 * - If Baritone is installed it is used through reflection (so there is NO compile-time dependency on it).
 * - Otherwise a very simple "walk straight + jump when blocked" fallback is used.
 */
public class Mover {
    private static final int TIMEOUT_TICKS = 20 * 20;

    private boolean useBaritone = classExists("baritone.api.BaritoneAPI");
    private BlockPos goal;
    private int radius;
    private int ticks;

    public boolean isMoving() {
        return goal != null;
    }

    public void goNear(BlockPos pos, int radius) {
        if (pos.equals(goal)) return;
        goal = pos;
        this.radius = radius;
        ticks = 0;
        if (useBaritone && !baritoneGo(pos, radius)) useBaritone = false;
    }

    /** Call every tick while isMoving(). */
    public void tick() {
        if (goal == null) return;
        if (++ticks > TIMEOUT_TICKS || closeEnough()) {
            stop();
            return;
        }
        if (!useBaritone) walk();
    }

    public void stop() {
        if (goal == null) return;
        goal = null;
        if (useBaritone) baritoneCancel();
        else releaseKeys();
    }

    private boolean closeEnough() {
        double dx = mc.player.getX() - (goal.getX() + 0.5);
        double dy = mc.player.getY() - (goal.getY() + 0.5);
        double dz = mc.player.getZ() - (goal.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }

    // ------------------------------------------------------------------ fallback walker

    private void walk() {
        Vec3d target = Vec3d.ofBottomCenter(goal);
        mc.player.setYaw((float) Rotations.getYaw(target));
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(mc.player.horizontalCollision && mc.player.isOnGround());
    }

    private void releaseKeys() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    // ------------------------------------------------------------------ Baritone (reflection)

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object primaryBaritone() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static boolean baritoneGo(BlockPos pos, int radius) {
        try {
            Object baritone = primaryBaritone();
            Object process = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(baritone);
            Class<?> goalType = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = Class.forName("baritone.api.pathing.goals.GoalNear")
                .getConstructor(BlockPos.class, int.class).newInstance(pos, radius);
            Class.forName("baritone.api.process.ICustomGoalProcess").getMethod("setGoalAndPath", goalType).invoke(process, goal);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void baritoneCancel() {
        try {
            Object baritone = primaryBaritone();
            Object pathing = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(baritone);
            Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(pathing);
        } catch (Throwable ignored) {
        }
    }
}

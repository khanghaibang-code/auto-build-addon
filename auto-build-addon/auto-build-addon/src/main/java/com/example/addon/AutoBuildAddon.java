package com.example.addon;

import com.example.addon.autobuild.AutoBuild;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AutoBuildAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new AutoBuild());
    }

    // Must be the package that contains all classes with @EventHandler methods.
    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}

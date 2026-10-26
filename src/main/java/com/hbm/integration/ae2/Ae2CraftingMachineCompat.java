// SPDX-FileCopyrightText: 2026 SecretAgent12
// SPDX-License-Identifier: LGPL-3.0-only

package com.hbm.integration.ae2;

import com.hbm.blocks.multiblock.BlockMultiblockCell;
import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.registration.NeoForgeRegistrar;
import com.hbm.registration.RegistryHandle;
import java.util.ArrayList;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers every NTM multiblock as an AE2 crafting machine ({@code ICraftingMachine}): all
 * multiblock cores, every shared multiblock cell (the 1.12.2 dummy-port behaviour) on every
 * side. A Pattern Provider can therefore push a whole processing pattern straight into the
 * machine from any side instead of only into a buffer inventory.
 *
 * <p>All AE2 references live behind {@link #register}, which the caller only invokes when the
 * {@code ae2} mod is present — so this class (and {@link MultiblockCraftingMachine}) is never
 * loaded without AE2 installed and the mod stays playable standalone.
 */
public final class Ae2CraftingMachineCompat {

    private Ae2CraftingMachineCompat() {}

    public static void register(RegisterCapabilitiesEvent event) {
        ArrayList<Block> targets = new ArrayList<Block>();
        for (RegistryHandle<? extends Block> handle : NeoForgeRegistrar.registeredBlocks) {
            Block block = handle.get();
            if (block instanceof BlockMultiblockCore || block instanceof BlockMultiblockCell) {
                targets.add(block);
            }
        }
        event.registerBlock(
                appeng.api.AECapabilities.CRAFTING_MACHINE,
                (level, pos, state, be, side) ->
                        MultiblockCraftingMachine.at(level, pos, state, be, side),
                targets.toArray(new Block[0]));
    }
}

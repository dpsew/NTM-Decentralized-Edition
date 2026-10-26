// SPDX-FileCopyrightText: 2026 SecretAgent12
// SPDX-License-Identifier: LGPL-3.0-only

package com.hbm.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.hbm.api.fluidmk2.FluidTankEndpoint;
import com.hbm.blocks.multiblock.MultiblockSurface;
import com.hbm.blockentity.BlockEntityMachineBase;
import com.hbm.capability.MachineItemHandler;
import com.hbm.inventory.fluid.tank.FluidTankAccess;
import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.lib.neotransfer.item.ItemResource;
import com.hbm.lib.neotransfer.transaction.Transaction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * AE2 crafting-machine view of recipe multiblocks (chemical plant, refinery, cracking tower,
 * strand caster, electrolyser and the like). Reachable from the core block and from every
 * shared multiblock cell, on any side — the 1.12.2 dummy-port behaviour ({@code
 * TileEntityProxyCombo} forwarding to the core).
 *
 * <p>The Pattern Provider calls {@link #pushPattern} first, before falling back to generic
 * inventory insertion, so a whole processing pattern (items plus fluids) lands in the core
 * atomically. Insertion itself still obeys each machine's own slot/tank rules
 * ({@code canPlaceItemThroughFace}, tank types), so nothing foreign can end up inside.
 *
 * <p>Fluids are written into the core receiving tanks directly. Looking them up again on the cell
 * face (often DOWN, under the machine) returns no receiver, and AE2 then rolls the whole batch
 * back — including items that would have fitted.
 */
public final class MultiblockCraftingMachine implements ICraftingMachine {

    private final ServerLevel level;
    private final BlockPos corePos;

    private MultiblockCraftingMachine(ServerLevel level, BlockPos corePos) {
        this.level = level;
        this.corePos = corePos;
    }

    public static @Nullable MultiblockCraftingMachine at(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity be,
            @Nullable Direction side) {
        if (side == null || !(level instanceof ServerLevel server)) {
            return null;
        }
        BlockPos core = null;
        if (be instanceof BlockEntityMachineBase machine
                && !machine.isRemoved()
                && declares(machine)) {
            core = machine.getBlockPos();
        } else if (MultiblockSurface.isFoldedCell(state)) {
            BlockEntity owner =
                    MultiblockSurface.resolveOwner(level, pos, state, null, 0, null);
            if (owner instanceof BlockEntityMachineBase machine
                    && !machine.isRemoved()
                    && declares(machine)) {
                core = machine.getBlockPos();
            }
        }
        return core == null ? null : new MultiblockCraftingMachine(server, core);
    }

    /**
     * Machines a Pattern Provider may push into: anything that declares an item inventory (all
     * single-block machines plus multiblock cores), multiblocks that proxy items through their
     * cells, and fluid-only recipe machines such as the cracking tower.
     */
    private static boolean declares(BlockEntityMachineBase machine) {
        if (MultiblockSurface.ownerDeclares(machine, 32)) {
            return true;
        }
        if (machine instanceof FluidTankEndpoint endpoint
                && endpoint.getReceivingTanks().length > 0) {
            return true;
        }
        if (machine instanceof FluidTankAccess access && access.tankCount() > 0) {
            return true;
        }
        return false;
    }

    private @Nullable BlockEntityMachineBase live() {
        BlockEntity blockEntity = this.level.getBlockEntity(this.corePos);
        if (!(blockEntity instanceof BlockEntityMachineBase machine)
                || machine.isRemoved()) {
            return null;
        }
        return machine;
    }

    @Override
    public boolean acceptsPlans() {
        return this.live() != null;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        BlockEntityMachineBase machine = this.live();
        ItemStack icon = new ItemStack((ItemLike) this.level.getBlockState(this.corePos).getBlock().asItem());
        AEItemKey key = icon.isEmpty() ? null : AEItemKey.of(icon);
        Component name =
                machine == null
                        ? this.level.getBlockState(this.corePos).getBlock().getName()
                        : machine.getName();
        return new PatternContainerGroup(key, name, List.of());
    }

    @Override
    public boolean pushPattern(
            IPatternDetails patternDetails, KeyCounter[] inputs, Direction ejectionDirection) {
        BlockEntityMachineBase machine = this.live();
        if (machine == null) {
            return false;
        }
        List<ItemStack> itemInputs = new ArrayList<>();
        List<FluidStack> fluidInputs = new ArrayList<>();
        for (KeyCounter counter : inputs) {
            var it = counter.iterator();
            while (it.hasNext()) {
                var entry = it.next();
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (amount <= 0L || amount > Integer.MAX_VALUE) {
                    return false;
                }
                if (key instanceof AEItemKey itemKey) {
                    ItemStack stack = itemKey.toStack();
                    if (stack.isEmpty()) {
                        return false;
                    }
                    stack.setCount((int) amount);
                    itemInputs.add(stack);
                } else if (key instanceof AEFluidKey fluidKey) {
                    FluidStack stack = fluidKey.toStack((int) amount);
                    if (stack.isEmpty()) {
                        return false;
                    }
                    fluidInputs.add(stack);
                } else {
                    return false;
                }
            }
        }
        if (itemInputs.isEmpty() && fluidInputs.isEmpty()) {
            return false;
        }
        FluidTankNTM[] receiving = selectReceivingTanks(machine);
        int[] tankIndex = new int[fluidInputs.size()];
        int[] tankAmount = new int[fluidInputs.size()];
        if (!fluidInputs.isEmpty()
                && !planFluids(receiving, fluidInputs, tankIndex, tankAmount)) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            MachineItemHandler itemHandler = MachineItemHandler.of(machine, ejectionDirection);
            for (ItemStack stack : itemInputs) {
                int moved = itemHandler.insert(ItemResource.of(stack), stack.getCount(), tx);
                if (moved < stack.getCount()) {
                    return false;
                }
            }
            if (!fluidInputs.isEmpty()
                    && !commitFluids(receiving, fluidInputs, tankIndex, tankAmount)) {
                return false;
            }
            tx.commit();
            machine.setChanged();
            return true;
        }
    }

    /**
     * Select receiving tanks for fluid input. Prefers {@link FluidTankEndpoint.getReceivingTanks()};
     * falls back to {@link FluidTankAccess} for fluid-only machines such as the cracking tower.
     */
    private static @Nullable FluidTankNTM[] selectReceivingTanks(
            BlockEntityMachineBase machine) {
        if (machine instanceof FluidTankEndpoint endpoint) {
            return endpoint.getReceivingTanks();
        }
        if (machine instanceof FluidTankAccess access) {
            int count = access.tankCount();
            if (count == 0) return null;
            FluidTankNTM[] tanks = new FluidTankNTM[count];
            for (int i = 0; i < count; i++) tanks[i] = access.tank(i);
            return tanks;
        }
        return null;
    }

    /**
     * Reserve each fluid on a receiving tank, accounting cumulative reservations so the same fluid
     * in two inputs may share one tank. Simulation only, nothing is written.
     */
    private static boolean planFluids(
            FluidTankNTM[] tanks,
            List<FluidStack> fluids,
            int[] tankIndex,
            int[] tankAmount) {
        if (tanks == null) {
            return false;
        }
        int[] reserved = new int[tanks.length];
        for (int i = 0; i < fluids.size(); ++i) {
            Fluid fluid = fluids.get(i).getFluid();
            int need = fluids.get(i).getAmount();
            int found = -1;
            for (int t = 0; t < tanks.length; ++t) {
                if (!tanks[t].accepts(fluid)) {
                    continue;
                }
                if (tanks[t].fill(fluid, reserved[t] + need, false) < reserved[t] + need) {
                    continue;
                }
                found = t;
                break;
            }
            if (found < 0) {
                return false;
            }
            reserved[found] += need;
            tankIndex[i] = found;
            tankAmount[i] = need;
        }
        return true;
    }

    /**
     * Apply a plan that already fitted. Rolls back any partial fill if a later tank refuses.
     */
    private static boolean commitFluids(
            FluidTankNTM[] tanks,
            List<FluidStack> fluids,
            int[] tankIndex,
            int[] tankAmount) {
        int applied = 0;
        for (int i = 0; i < fluids.size(); ++i) {
            FluidTankNTM tank = tanks[tankIndex[i]];
            int moved = tank.fill(fluids.get(i).getFluid(), tankAmount[i], true);
            if (moved < tankAmount[i]) {
                if (moved > 0) {
                    tank.drain(moved, true);
                }
                for (int j = 0; j < applied; ++j) {
                    tanks[tankIndex[j]].drain(tankAmount[j], true);
                }
                return false;
            }
            ++applied;
        }
        return true;
    }
}

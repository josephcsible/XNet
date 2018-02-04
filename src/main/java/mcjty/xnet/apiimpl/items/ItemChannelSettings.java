package mcjty.xnet.apiimpl.items;

import mcjty.lib.varia.WorldTools;
import mcjty.xnet.XNet;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.helper.DefaultChannelSettings;
import mcjty.xnet.api.keys.ConsumerId;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.compat.RFToolsSupport;
import mcjty.xnet.config.GeneralConfiguration;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class ItemChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final String TAG_MODE = "mode";

    // Cache data
    private Map<SidedConsumer, ItemConnectorSettings> itemExtractors = null;
    private List<Pair<SidedConsumer, ItemConnectorSettings>> itemConsumers = null;


    enum ChannelMode {
        PRIORITY,
        ROUNDROBIN
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;
    private Map<ConsumerId, Integer> extractIndeces = new HashMap<>();

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    @Override
    public int getColors() {
        return 0;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        channelMode = ChannelMode.values()[tag.getByte("mode")];
        delay = tag.getInteger("delay");
        roundRobinOffset = tag.getInteger("offset");
        int[] cons = tag.getIntArray("extidx");
        for (int idx = 0 ; idx < cons.length ; idx += 2) {
            extractIndeces.put(new ConsumerId(cons[idx]), cons[idx+1]);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte("mode", (byte) channelMode.ordinal());
        tag.setInteger("delay", delay);
        tag.setInteger("offset", roundRobinOffset);

        if (!extractIndeces.isEmpty()) {
            int[] cons = new int[extractIndeces.size() * 2];
            int idx = 0;
            for (Map.Entry<ConsumerId, Integer> entry : extractIndeces.entrySet()) {
                cons[idx++] = entry.getKey().getId();
                cons[idx++] = entry.getValue();
            }
            tag.setIntArray("extidx", cons);
        }
    }

    private int getExtractIndex(ConsumerId consumer) {
        if (extractIndeces.containsKey(consumer)) {
            return extractIndeces.get(consumer);
        } else {
            return 0;
        }
    }

    private void rememberExtractIndex(ConsumerId consumer, int index) {
        extractIndeces.put(consumer, index);
    }

    private static class MInteger {
        private int i;

        public MInteger(int i) {
            this.i = i;
        }

        // This can return an index out of bounds!
        public int get() {
            return i;
        }

        // Safe get that is always in bounds
        public int getSafe(int bounds) {
            return bounds <= 0 ? i : (i % bounds);
        }

        public void set(int i) {
            this.i = i;
        }

        public void inc() {
            i++;
        }
    }

    private static Random random = new Random();

    @Override
    public void tick(int channel, IControllerContext context) {
        delay--;
        if (delay <= 0) {
            delay = 200*6;      // Multiply of the different speeds we have
        }
        if (delay % 5 != 0) {
            return;
        }
        int d = delay/5;

        updateCache(channel, context);
        World world = context.getControllerWorld();
        for (Map.Entry<SidedConsumer, ItemConnectorSettings> entry : itemExtractors.entrySet()) {
            ItemConnectorSettings settings = entry.getValue();
            if (d % settings.getSpeed() != 0) {
                continue;
            }

            ConsumerId consumerId = entry.getKey().getConsumerId();
            BlockPos extractorPos = context.findConsumerPosition(consumerId);
            if (extractorPos != null) {
                EnumFacing side = entry.getKey().getSide();
                BlockPos pos = extractorPos.offset(side);
                if (!WorldTools.chunkLoaded(world, pos)) {
                    continue;
                }

                if (checkRedstone(world, settings, extractorPos)) {
                    return;
                }
                if (!context.matchColor(settings.getColorsMask())) {
                    return;
                }

                TileEntity te = world.getTileEntity(pos);

                if (XNet.rftools && RFToolsSupport.isStorageScanner(te)) {
                    RFToolsSupport.tickStorageScanner(context, settings, te, this);
                } else {
                    IItemHandler handler = getItemHandlerAt(te, settings.getFacing());
                    if (handler != null) {
                        int idx = getStartExtractIndex(settings, consumerId, handler);
                        try {
                            idx = tickItemHandler(context, settings, handler, idx);
                        } catch(Throwable t) {
                            System.out.println("TileEntity and IItemHandler classes:");
                            System.out.println(te.getClass().getName());
                            System.out.println(handler.getClass().getName());
                            throw t;
                        }
                        if (handler.getSlots() > 0) {
                            rememberExtractIndex(consumerId, (idx + 1) % handler.getSlots());
                        }
                    }
                }
            }
        }
    }

    private int getStartExtractIndex(ItemConnectorSettings settings, ConsumerId consumerId, IItemHandler handler) {
        switch (settings.getExtractMode()) {
            case FIRST:
                return 0;
            case RND: {
                if (handler.getSlots() <= 0) {
                    return 0;
                }
                // Try 5 times to find a non empty slot
                for (int i = 0 ; i < 5 ; i++) {
                    int idx = random.nextInt(handler.getSlots());
                    if (!handler.getStackInSlot(idx).isEmpty()) {
                        return idx;
                    }
                }
                // Otherwise use a more complicated algorithm
                List<Integer> slots = new ArrayList<>();
                for (int i = 0 ; i < handler.getSlots() ; i++) {
                    if (!handler.getStackInSlot(i).isEmpty()) {
                        slots.add(i);
                    }
                }
                if (slots.isEmpty()) {
                    return 0;
                }
                return slots.get(random.nextInt(slots.size()));
            }
            case ORDER:
                return getExtractIndex(consumerId);
        }
        return 0;
    }


    private int tickItemHandler(IControllerContext context, ItemConnectorSettings settings, IItemHandler handler, int startIdx) {
        Predicate<ItemStack> extractMatcher = settings.getMatcher();

        Integer count = settings.getCount();
        int amount = 0;
        if (count != null) {
            amount = countItems(handler, extractMatcher);
            if (amount < count) {
                return startIdx;
            }
        }
        MInteger index = new MInteger(startIdx);
        while (true) {
            ItemStack stack = fetchItem(handler, true, extractMatcher, settings.getStackMode(), 64, index, startIdx);
            if (!stack.isEmpty()) {
                // Now that we have a stack we first reduce the amount of the stack if we want to keep a certain
                // number of items
                int toextract = stack.getCount();
                if (count != null) {
                    int canextract = amount-count;
                    if (canextract <= 0) {
                        index.inc();
                        continue;
                    }
                    if (canextract < toextract) {
                        toextract = canextract;
                        stack = stack.copy();
                        if (toextract <= 0) {
                            stack.setCount(0);
                        } else {
                            stack.setCount(toextract);
                        }
                    }
                }

                List<Pair<SidedConsumer, ItemConnectorSettings>> inserted = new ArrayList<>();
                int remaining = insertStackSimulate(inserted, context, stack);
                if (!inserted.isEmpty()) {
                    if (context.checkAndConsumeRF(GeneralConfiguration.controllerOperationRFT)) {
                        insertStackReal(context, inserted, fetchItem(handler, false, extractMatcher, settings.getStackMode(), toextract-remaining, index, startIdx));
                    }
                    break;
                } else {
                    index.inc();
                }
            } else {
                break;
            }
        }
        return index.getSafe(handler.getSlots());
    }

    // Returns what could not be inserted
    public int insertStackSimulate(@Nonnull List<Pair<SidedConsumer, ItemConnectorSettings>> inserted, @Nonnull IControllerContext context, @Nonnull ItemStack stack) {
        World world = context.getControllerWorld();
        if (channelMode == ChannelMode.PRIORITY) {
            roundRobinOffset = 0;       // Always start at 0
        }
        int total = stack.getCount();
        for (int j = 0 ; j < itemConsumers.size() ; j++) {
            int i = (j + roundRobinOffset) % itemConsumers.size();
            Pair<SidedConsumer, ItemConnectorSettings> entry = itemConsumers.get(i);
            ItemConnectorSettings settings = entry.getValue();

            if (settings.getMatcher().test(stack)) {
                BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
                if (consumerPos != null) {
                    if (!WorldTools.chunkLoaded(world, consumerPos)) {
                        continue;
                    }

                    if (checkRedstone(world, settings, consumerPos)) {
                        continue;
                    }
                    if (!context.matchColor(settings.getColorsMask())) {
                        continue;
                    }

                    EnumFacing side = entry.getKey().getSide();
                    BlockPos pos = consumerPos.offset(side);
                    TileEntity te = world.getTileEntity(pos);
                    int actuallyinserted;
                    int toinsert = total;
                    ItemStack remaining;
                    Integer count = settings.getCount();

                    if (XNet.rftools && RFToolsSupport.isStorageScanner(te)) {
                        if (count != null) {
                            int amount = RFToolsSupport.countItems(te, settings.getMatcher(), count);
                            int caninsert = count - amount;
                            if (caninsert <= 0) {
                                continue;
                            }
                            toinsert = Math.min(toinsert, caninsert);
                            stack = stack.copy();
                            if (toinsert <= 0) {
                                stack.setCount(0);
                            } else {
                                stack.setCount(toinsert);
                            }
                        }
                        remaining = RFToolsSupport.insertItem(te, stack, true);
                    } else {
                        IItemHandler handler = getItemHandlerAt(te, settings.getFacing());
                        if (handler != null) {
                            if (count != null) {
                                int amount = countItems(handler, settings.getMatcher());
                                int caninsert = count - amount;
                                if (caninsert <= 0) {
                                    continue;
                                }
                                toinsert = Math.min(toinsert, caninsert);
                                stack = stack.copy();
                                if (toinsert <= 0) {
                                    stack.setCount(0);
                                } else {
                                    stack.setCount(toinsert);
                                }
                            }
                            remaining = ItemHandlerHelper.insertItem(handler, stack, true);
                        } else {
                            continue;
                        }
                    }

                    actuallyinserted = toinsert - remaining.getCount();
                    if (count == null) {
                        // If we are not using a count then we restore 'stack' here as that is what
                        // we actually have to keep inserting until it is empty. If we are using a count
                        // then we don't do this as we don't want to risk stack getting null (on 1.10.2)
                        // from the insertItem() and then not being able to set stacksize a few lines
                        // above this
                        stack = remaining;
                    }
                    if (actuallyinserted > 0) {
                        inserted.add(entry);
                        total -= actuallyinserted;
                        if (total <= 0) {
                            return 0;
                        }
                    }
                }
            }
        }
        return total;
    }

    public void insertStackReal(@Nonnull IControllerContext context, @Nonnull List<Pair<SidedConsumer, ItemConnectorSettings>> inserted, @Nonnull ItemStack stack) {
        int total = stack.getCount();
        for (Pair<SidedConsumer, ItemConnectorSettings> entry : inserted) {
            BlockPos consumerPosition = context.findConsumerPosition(entry.getKey().getConsumerId());
            EnumFacing side = entry.getKey().getSide();
            ItemConnectorSettings settings = entry.getValue();
            BlockPos pos = consumerPosition.offset(side);
            TileEntity te = context.getControllerWorld().getTileEntity(pos);
            if (XNet.rftools && RFToolsSupport.isStorageScanner(te)) {
                int toinsert = total;
                Integer count = settings.getCount();
                if (count != null) {
                    int amount = RFToolsSupport.countItems(te, settings.getMatcher(), count);
                    int caninsert = count - amount;
                    if (caninsert <= 0) {
                        continue;
                    }
                    toinsert = Math.min(toinsert, caninsert);
                    stack = stack.copy();
                    if (toinsert <= 0) {
                        stack.setCount(0);
                    } else {
                        stack.setCount(toinsert);
                    }
                }
                ItemStack remaining = RFToolsSupport.insertItem(te, stack, false);
                int actuallyinserted = toinsert - remaining.getCount();
                if (count == null) {
                    // If we are not using a count then we restore 'stack' here as that is what
                    // we actually have to keep inserting until it is empty. If we are using a count
                    // then we don't do this as we don't want to risk stack getting null (on 1.10.2)
                    // from the insertItem() and then not being able to set stacksize a few lines
                    // above this
                    stack = remaining;
                }

                if (actuallyinserted > 0) {
                    roundRobinOffset = (roundRobinOffset + 1) % itemConsumers.size();
                    total -= actuallyinserted;
                    if (total <= 0) {
                        return;
                    }
                }

            } else {
                IItemHandler handler = getItemHandlerAt(te, settings.getFacing());

                int toinsert = total;
                Integer count = settings.getCount();
                if (count != null) {
                    int amount = countItems(handler, settings.getMatcher());
                    int caninsert = count - amount;
                    if (caninsert <= 0) {
                        continue;
                    }
                    toinsert = Math.min(toinsert, caninsert);
                    stack = stack.copy();
                    if (toinsert <= 0) {
                        stack.setCount(0);
                    } else {
                        stack.setCount(toinsert);
                    }
                }
                ItemStack remaining = ItemHandlerHelper.insertItem(handler, stack, false);
                int actuallyinserted = toinsert - remaining.getCount();
                if (count == null) {
                    // If we are not using a count then we restore 'stack' here as that is what
                    // we actually have to keep inserting until it is empty. If we are using a count
                    // then we don't do this as we don't want to risk stack getting null (on 1.10.2)
                    // from the insertItem() and then not being able to set stacksize a few lines
                    // above this
                    stack = remaining;
                }

                if (actuallyinserted > 0) {
                    roundRobinOffset = (roundRobinOffset + 1) % itemConsumers.size();
                    total -= actuallyinserted;
                    if (total <= 0) {
                        return;
                    }
                }
            }
        }
    }

    private int countItems(IItemHandler handler, Predicate<ItemStack> matcher) {
        int cnt = 0;
        for (int i = 0 ; i < handler.getSlots() ; i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (!s.isEmpty()) {
                if (matcher.test(s)) {
                    cnt += s.getCount();
                }
            }
        }
        return cnt;
    }


    private ItemStack fetchItem(IItemHandler handler, boolean simulate, Predicate<ItemStack> matcher, ItemConnectorSettings.StackMode stackMode, int maxamount, MInteger index, int startIdx) {
        if (handler.getSlots() <= 0) {
            return ItemStack.EMPTY;
        }
        for (int i = index.get(); i < handler.getSlots()+startIdx ; i++) {
            int j = i % handler.getSlots();
            ItemStack stack = handler.getStackInSlot(j);
            if (!stack.isEmpty()) {
                int s = (stackMode == ItemConnectorSettings.StackMode.SINGLE) ? 1 : stack.getMaxStackSize();
                s = Math.min(s, maxamount);
                stack = handler.extractItem(j, s, simulate);
                if (!stack.isEmpty() && matcher.test(stack)) {
                    index.set(i);
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }


    private void updateCache(int channel, IControllerContext context) {
        if (itemExtractors == null) {
            itemExtractors = new HashMap<>();
            itemConsumers = new ArrayList<>();
            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ItemConnectorSettings con = (ItemConnectorSettings) entry.getValue();
                if (con.getItemMode() == ItemConnectorSettings.ItemMode.EXT) {
                    itemExtractors.put(entry.getKey(), con);
                } else {
                    itemConsumers.add(Pair.of(entry.getKey(), con));
                }
            }
            connectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ItemConnectorSettings con = (ItemConnectorSettings) entry.getValue();
                if (con.getItemMode() == ItemConnectorSettings.ItemMode.INS) {
                    itemConsumers.add(Pair.of(entry.getKey(), con));
                }
            }

            itemConsumers.sort((o1, o2) -> o2.getRight().getPriority().compareTo(o1.getRight().getPriority()));
        }
    }

    @Override
    public void cleanCache() {
        itemExtractors = null;
        itemConsumers = null;
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(GuiController.iconGuiElements, 0, 80, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        gui.nl().choices(TAG_MODE, "Item distribution mode", channelMode, ChannelMode.values());
    }

    @Override
    public void update(Map<String, Object> data) {
        channelMode = ChannelMode.valueOf(((String)data.get(TAG_MODE)).toUpperCase());
        roundRobinOffset = 0;
    }

    @Nullable
    public static IItemHandler getItemHandlerAt(@Nullable TileEntity te, EnumFacing intSide) {
        if (te != null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, intSide)) {
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, intSide);
            if (handler != null) {
                return handler;
            }
        } else if (te instanceof ISidedInventory) {
            // Support for old inventory
            ISidedInventory sidedInventory = (ISidedInventory) te;
            return new SidedInvWrapper(sidedInventory, intSide);
        } else if (te instanceof IInventory) {
            // Support for old inventory
            IInventory inventory = (IInventory) te;
            return new InvWrapper(inventory);
        }
        return null;
    }


}

package dev.fgorder.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.common.collect.Multimap;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.packager.IdentifiedInventory;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.minecraft.world.item.ItemStack;

/**
 * 修正工厂仪表“输入配方顺序”的前两跳：
 *
 * <ol>
 *   <li><b>连接顺序</b>：原版用 {@code HashMap<FactoryPanelPosition, FactoryPanelConnection>} 保存
 *       “这个仪表由哪些仪表供货”，键是仪表坐标，迭代顺序由坐标哈希决定 —— 玩家加连接的先后顺序
 *       从未被记录。这里换成 {@link LinkedHashMap}，让顺序等于玩家点选连接的顺序，
 *       并且 NBT 存档读写（clear + 按 list 顺序 put）后依然保持。</li>
 *   <li><b>请求顺序</b>：{@code tickRequests()} 内部用 {@code HashMap} / {@code HashMultimap}
 *       汇总每种原料，最后交给 {@code PackageOrder} 的 List 是哈希顺序。这里在它调用
 *       {@code LogisticsManager.findPackagersForRequest(...)} 之前，按上面的连接顺序重排
 *       {@code PackageOrderWithCrafts.orderedStacks}。</li>
 * </ol>
 *
 * <p>重排只影响“顺序”，不改变数量、不增加/删除条目；顺序未知的条目保持相对位置排在最后。</p>
 */
@Mixin(value = FactoryPanelBehaviour.class, remap = false)
public class FactoryPanelBehaviourMixin {

    /** 构造结束后把连接表替换为有序 Map（原版在此处赋的是 HashMap）。 */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void fgorder$useOrderedConnectionMap(CallbackInfo ci) {
        FactoryPanelBehaviour self = (FactoryPanelBehaviour) (Object) this;
        self.targetedBy = new LinkedHashMap<>(self.targetedBy);
    }

    /** disable() 会把连接表重置成新的 HashMap，这里补一次。 */
    @Inject(method = "disable", at = @At("RETURN"))
    private void fgorder$resetOrderedConnectionMap(CallbackInfo ci) {
        FactoryPanelBehaviour self = (FactoryPanelBehaviour) (Object) this;
        self.targetedBy = new LinkedHashMap<>();
    }

    /**
     * 把“按连接顺序排好的原料列表”交给打包机查找逻辑。
     *
     * <p>钩在 tickRequests 里唯一一次 {@code findPackagersForRequest} 调用上，
     * 此时 {@code craftingContext}（机械动力合成配方排列）已经单独存放在
     * {@code orderedCrafts} 里，不会被动到。</p>
     */
    @Redirect(
        method = "tickRequests",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/logistics/packagerLink/LogisticsManager;findPackagersForRequest(Ljava/util/UUID;Lcom/simibubi/create/content/logistics/stockTicker/PackageOrderWithCrafts;Lcom/simibubi/create/content/logistics/packager/IdentifiedInventory;Ljava/lang/String;)Lcom/google/common/collect/Multimap;"
        )
    )
    private Multimap<PackagerBlockEntity, PackagingRequest> fgorder$requestInConnectionOrder(UUID network,
        PackageOrderWithCrafts order, IdentifiedInventory ignoredHandler, String address) {
        return LogisticsManager.findPackagersForRequest(network, fgorder$sortByConnectionOrder(order), ignoredHandler, address);
    }

    private PackageOrderWithCrafts fgorder$sortByConnectionOrder(PackageOrderWithCrafts order) {
        if (order == null)
            return null;

        List<BigItemStack> stacks = order.stacks();
        if (stacks.size() < 2)
            return order;

        FactoryPanelBehaviour self = (FactoryPanelBehaviour) (Object) this;
        List<ItemStack> wanted = new ArrayList<>();
        for (FactoryPanelConnection connection : self.targetedBy.values()) {
            // 供货仪表可能已卸载/未激活，at() 会返回 null —— 这种连接没有可用的过滤物品
            FactoryPanelBehaviour source = FactoryPanelBehaviour.at(self.getWorld(), connection);
            if (source != null)
                wanted.add(source.getFilter());
        }
        if (wanted.size() < 2)
            return order;

        List<BigItemStack> sorted = new ArrayList<>(stacks);
        sorted.sort(Comparator.comparingInt(stack -> fgorder$indexOf(wanted, stack.stack)));
        return new PackageOrderWithCrafts(new PackageOrder(sorted), order.orderedCrafts());
    }

    private static int fgorder$indexOf(List<ItemStack> wanted, ItemStack stack) {
        for (int i = 0; i < wanted.size(); i++) {
            if (ItemStack.isSameItemSameComponents(wanted.get(i), stack))
                return i;
        }
        return Integer.MAX_VALUE;
    }
}

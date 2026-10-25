package dev.fgorder.mixin;

import java.util.Comparator;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 最后一跳：拆包时按订单自带的顺序把物品交给目标容器。
 *
 * <p>包裹里除了物品，还带着 {@code PACKAGE_ORDER_DATA}（{@link PackageOrderWithCrafts}），
 * 其中 {@code orderedStacks} 就是发货方期望的原料顺序。但默认的拆包实现
 * （{@code DefaultUnpackingHandler}）只是按包裹格子顺序逐个 {@code insertItemStacked}，
 * 而包裹格子顺序在打包机一侧已经被 HashSet 打乱了（见上游 #10188）。于是
 * “请求时有顺序、落地时又变随机”。</p>
 *
 * <p>这里拦截 {@code unwrapBox} 里唯一一次 {@code UnpackingHandler#unpack} 调用，
 * 把待插入列表就地按 {@code orderedStacks} 重排：</p>
 *
 * <ul>
 *   <li><b>必须就地排序</b>：合成台路径的 {@code CrafterUnpackingHandler} 用“传入 list 是否被
 *       消耗空”判定成功，换成新 list 会让它误判失败；</li>
 *   <li><b>带配方图案的订单不碰</b>：{@code hasCraftingInformation(ctx)} 为真时，顺序来源是
 *       仪表里的“合成排列”图案，由 {@code CrafterUnpackingHandler} 负责，这里直接放行。</li>
 * </ul>
 */
@Mixin(value = PackagerBlockEntity.class, remap = false)
public class PackagerUnwrapMixin {

    @Redirect(
        method = "unwrapBox",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/api/packager/unpacking/UnpackingHandler;unpack(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Ljava/util/List;Lcom/simibubi/create/content/logistics/stockTicker/PackageOrderWithCrafts;Z)Z"
        )
    )
    private static boolean fgorder$unpackInOrder(UnpackingHandler handler, Level level, BlockPos pos, BlockState state,
        Direction side, List<ItemStack> items, PackageOrderWithCrafts orderContext, boolean simulate) {
        fgorder$sortInPlace(items, orderContext);
        return handler.unpack(level, pos, state, side, items, orderContext, simulate);
    }

    private static void fgorder$sortInPlace(List<ItemStack> items, PackageOrderWithCrafts orderContext) {
        if (items == null || items.size() < 2 || orderContext == null)
            return;

        List<BigItemStack> wanted = orderContext.stacks();
        if (wanted.size() < 2)
            return;

        // 合成台排列由 CrafterUnpackingHandler 按配方图案处理，别覆盖它
        if (PackageOrderWithCrafts.hasCraftingInformation(orderContext))
            return;

        try {
            items.sort(Comparator.comparingInt(stack -> fgorder$indexOf(wanted, stack)));
        } catch (UnsupportedOperationException ignored) {
            // 上游若传入不可变 List，保持原顺序（不会比原版更差）
        }
    }

    private static int fgorder$indexOf(List<BigItemStack> wanted, ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return Integer.MAX_VALUE;
        for (int i = 0; i < wanted.size(); i++) {
            if (ItemStack.isSameItemSameComponents(wanted.get(i).stack, stack))
                return i;
        }
        return Integer.MAX_VALUE;
    }
}

package dev.fgorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * 工厂仪表顺序修复（Factory Gauge Order Fix）。
 *
 * <p>本模组不注册任何方块/物品，只通过 Mixin 修正 Create 物流链路中丢失的物品顺序，
 * 因此对存档和服务器是“纯客户端/服务端行为修正”，可以随时加入或移除。</p>
 *
 * <p>对应上游 issue：Creators-of-Create/Create#8898（order-sensitive non-crafter recipes）。</p>
 */
@Mod(FGOrder.MODID)
public class FGOrder {
    public static final String MODID = "factorygaugeorder";
    public static final Logger LOGGER = LoggerFactory.getLogger("FactoryGaugeOrder");

    public FGOrder(IEventBus modBus) {
        LOGGER.info("Factory Gauge Order Fix loaded: gauge connection order, request order and unboxing order are now stable.");
    }
}

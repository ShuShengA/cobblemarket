package com.shusheng.cobblemarket.mixin.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 `Entity.dataTracker`（protected），供 {@link PlayerEntityMixin} 写皮肤层数据。
 *
 * ⚠ 为什么不直接在 `PlayerEntity` 的 mixin 里 `@Shadow`：这个字段声明在父类 `Entity`，
 *   Mixin AP 在 `PlayerEntity` 里找不到目标、会把整个 mixin 的 refmap 映射丢掉
 *   （生产环境里映射不到 intermediary 名 ⇒ mixin 静默不生效）。用 Entity 自己的 accessor 最稳。
 */
@Mixin(Entity.class)
public interface EntityDataTrackerAccessor {

    @Accessor("dataTracker")
    DataTracker cobblemarket$dataTracker();
}

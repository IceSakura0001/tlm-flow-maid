package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

/** Resolve real registered work modes; never wrap the flow work mode recursively. */
public final class NativeWorkTasks {
    private NativeWorkTasks() { }
    public static IMaidTask find(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || key.equals(FlowWorkTask.UID)) throw new IllegalArgumentException("请选择真实的女仆工作能力，不能递归调用流程");
        return TaskManager.findTask(key).orElseThrow(() -> new IllegalArgumentException("女仆工作能力不存在或对应模组未加载：" + id));
    }
    public static boolean enabled(EntityMaid maid, IMaidTask task) {
        return !task.isHidden(maid) && task.isEnable(maid) && !MinecraftForge.EVENT_BUS.post(new MaidTaskEnableEvent(task, maid));
    }
}

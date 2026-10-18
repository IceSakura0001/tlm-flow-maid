package dev.tlmprogrammable.flow.server;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

@LittleMaidExtension
public final class FlowExtension implements ILittleMaid {
    @Override public void addMaidTask(TaskManager manager) { manager.add(new FlowWorkTask()); }
}

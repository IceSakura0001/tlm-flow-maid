package dev.tlmprogrammable;

import dev.tlmprogrammable.flow.client.FlowClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(ProgrammableMaid.ID)
public final class ProgrammableMaid {
    public static final String ID = "tlm_programmable";
    public ProgrammableMaid() {
        dev.tlmprogrammable.flow.server.FlowNetwork.init();
        dev.tlmprogrammable.flow.server.FlowRuntime.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FlowClient.init());
    }
}

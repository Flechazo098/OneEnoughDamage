package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointDictionaryGenerator;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import cc.sighs.oed.hook.ForgeHooks;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

public class OneEnoughDamage implements ModInitializer {
    public static final String MODID = "oneenoughdamage";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        ForgeHooks.register();
        DamagePointAttributes.register();
        DamagePointDictionaryGenerator.generateIfNeeded();
        DamagePointTomlConfig.startWatcherIfNeeded();
    }
}

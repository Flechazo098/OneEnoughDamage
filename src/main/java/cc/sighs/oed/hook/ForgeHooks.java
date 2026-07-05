package cc.sighs.oed.hook;

import cc.sighs.oed.api.event.EntityAttributeModificationCallback;
import cc.sighs.oed.mixin.AttributeSupplierAccessor;
import cc.sighs.oed.mixin.DefaultAttributesAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.util.Map;

public class ForgeHooks {

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            EntityAttributeModificationCallback.AttributeMap map = new EntityAttributeModificationCallback.AttributeMap();
            EntityAttributeModificationCallback.EVENT.invoker().modifyAttributes(map);
            applyModifications(map);
        });
    }

    public static void applyModifications(EntityAttributeModificationCallback.AttributeMap map) {
        Map<EntityType<? extends LivingEntity>, AttributeSupplier> suppliers = DefaultAttributesAccessor.getSuppliers();

        for (var entry : map.getAdditions().entrySet()) {
            EntityType<? extends LivingEntity> type = entry.getKey();
            Map<Attribute, Double> newAttributes = entry.getValue();

            AttributeSupplier original = suppliers.get(type);
            AttributeSupplier.Builder builder = new AttributeSupplier.Builder();
            if (original != null) {
                for (AttributeInstance instance : ((AttributeSupplierAccessor) original).getInstances().values()) {
                    builder.add(instance.getAttribute(), instance.getBaseValue());
                }
            }
            for (var attr : newAttributes.entrySet()) {
                builder.add(attr.getKey(), attr.getValue());
            }
            suppliers.put(type, builder.build());
        }
    }
}

package cc.sighs.oed;

import cc.sighs.oed.asm.DamagePointData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = OneEnoughDamage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DamagePointAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, OneEnoughDamage.MODID);

    private static final Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> DAMAGE_POINT_ATTRIBUTES = registerDamagePointAttributes();

    private DamagePointAttributes() {
    }

    private static Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> registerDamagePointAttributes() {
        Map<DamagePointData.DamagePoint, RegistryObject<Attribute>> attributes = new LinkedHashMap<>();
        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            RegistryObject<Attribute> attribute = ATTRIBUTES.register(
                    point.attributePath(),
                    () -> new RangedAttribute(
                            point.description(),
                            point.defaultDamage(),
                            0.0D,
                            2048.0D
                    ).setSyncable(true)
            );
            attributes.put(point, attribute);
        }
        return attributes;
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<?> entityType : ForgeRegistries.ENTITY_TYPES) {
            Class<? extends Entity> baseClass = entityType.getBaseClass();
            if (!LivingEntity.class.isAssignableFrom(baseClass)) {
                continue;
            }

            for (RegistryObject<Attribute> attribute : DAMAGE_POINT_ATTRIBUTES.values()) {
                event.add(asLivingEntityType(entityType), attribute.get());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends LivingEntity> asLivingEntityType(EntityType<?> entityType) {
        return (EntityType<? extends LivingEntity>) entityType;
    }
}

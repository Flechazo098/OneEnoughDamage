package cc.sighs.oed;

import cc.sighs.oed.api.event.EntityAttributeModificationCallback;
import cc.sighs.oed.asm.DamagePointData;
import cc.sighs.oed.asm.DamagePointTomlConfig;
import com.flechazo.hkt.business.control.MaybePath;
import com.flechazo.hkt.business.core.Pathway;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

import java.util.*;

public final class DamagePointAttributes {
    public static final Attribute PROJECTILE_BASE_DAMAGE = new RangedAttribute(
            "oneenoughdamage.projectile_base_damage",
            -1.0D,
            -1.0D,
            2048.0D
    ).setSyncable(true);

    private static final Map<DamagePointData.DamagePoint, Attribute> DAMAGE_POINT_ATTRIBUTES = new LinkedHashMap<>();
    private static final Map<String, Double> CONFIGURED_DEFAULTS = configuredDefaults();

    static {
        DamagePointTomlConfig.addChangeListener(RuntimeSync::syncConfiguredAttributes);
    }

    private DamagePointAttributes() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.ATTRIBUTE,
                new ResourceLocation(OneEnoughDamage.MODID, "projectile_base_damage"),
                PROJECTILE_BASE_DAMAGE);

        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            Attribute attribute = new RangedAttribute(
                    point.description(),
                    point.defaultDamage(),
                    0.0D,
                    2048.0D
            ).setSyncable(true);
            Registry.register(BuiltInRegistries.ATTRIBUTE,
                    new ResourceLocation(OneEnoughDamage.MODID, point.attributePath()),
                    attribute);
            DAMAGE_POINT_ATTRIBUTES.put(point, attribute);
        }

        // Register to add custom attributes to all living entity types (like Forge's EntityAttributeModificationEvent)
        EntityAttributeModificationCallback.EVENT.register(attributes -> {
            for (EntityType<? extends LivingEntity> entityType : attributes.getTypes()) {
                if (!attributes.has(entityType, PROJECTILE_BASE_DAMAGE)) {
                    attributes.add(entityType, PROJECTILE_BASE_DAMAGE);
                }
                for (Attribute attribute : DAMAGE_POINT_ATTRIBUTES.values()) {
                    if (!attributes.has(entityType, attribute)) {
                        attributes.add(entityType, attribute);
                    }
                }
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!world.isClientSide() && entity instanceof LivingEntity living) {
                Set<String> attributeIds = new LinkedHashSet<>(CONFIGURED_DEFAULTS.keySet());
                attributeIds.addAll(DamagePointTomlConfig.configuredKeys());
                RuntimeSync.syncEntity(living, attributeIds);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> RuntimeSync.server = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> RuntimeSync.server = null);
    }

    public static Collection<Attribute> getDamagePointAttributes() {
        return DAMAGE_POINT_ATTRIBUTES.values();
    }

    private static Map<String, Double> configuredDefaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        for (DamagePointData.DamagePoint point : DamagePointData.points()) {
            defaults.put(OneEnoughDamage.MODID + ":" + point.attributePath(), (double) point.defaultDamage());
        }
        return Map.copyOf(defaults);
    }

    static final class RuntimeSync {
        private static volatile MinecraftServer server;

        private RuntimeSync() {
        }

        private static void syncConfiguredAttributes(Collection<String> attributeIds) {
            MinecraftServer current = server;
            if (current == null) {
                return;
            }

            for (ServerLevel level : current.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof LivingEntity living) {
                        syncEntity(living, attributeIds);
                    }
                }
            }
        }

        private static void syncEntity(LivingEntity living, Collection<String> attributeIds) {
            for (String attributeId : attributeIds) {
                ConfiguredAttributeKey.parse(attributeId)
                        .filter(key -> key.matches(living))
                        .via(key -> Pathway.nullable(ResourceLocation.tryParse(key.attributeId()))
                                .via(id -> Pathway.nullable(BuiltInRegistries.ATTRIBUTE.get(id)))
                                .via(attribute -> Pathway.nullable(living.getAttribute(attribute)))
                                .zipWith(configuredValue(attributeId, key.attributeId()), AttributeSync::new))
                        .peek(sync -> {
                            if (Double.compare(sync.instance().getBaseValue(), sync.value()) != 0) {
                                sync.instance().setBaseValue(sync.value());
                            }
                        });
            }
        }

        private static MaybePath<Double> configuredValue(String attributeId, String defaultKey) {
            Float configured = DamagePointTomlConfig.configuredValue(attributeId);
            return configured == null
                    ? Pathway.nullable(CONFIGURED_DEFAULTS.get(defaultKey))
                    : Pathway.just((double) configured);
        }

        private record AttributeSync(AttributeInstance instance, double value) {
        }

        private record ConfiguredAttributeKey(String attributeId, String entityId) {
            private static MaybePath<ConfiguredAttributeKey> parse(String value) {
                int entitySeparator = value.indexOf('@');
                if (entitySeparator < 0) {
                    return Pathway.just(new ConfiguredAttributeKey(value, null));
                }
                String attributeId = value.substring(0, entitySeparator);
                String entityId = value.substring(entitySeparator + 1);
                if (attributeId.isBlank() || entityId.isBlank()) {
                    return Pathway.nothing();
                }
                return Pathway.just(new ConfiguredAttributeKey(attributeId, entityId));
            }

            private boolean matches(LivingEntity living) {
                if (entityId == null) {
                    return true;
                }
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
                return typeId != null && entityId.equals(typeId.toString());
            }
        }
    }
}

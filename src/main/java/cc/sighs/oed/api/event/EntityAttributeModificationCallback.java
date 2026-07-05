package cc.sighs.oed.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityAttributeModificationCallback {
    private EntityAttributeModificationCallback() {
    }

    public static final Event<ModifyAttributes> EVENT = EventFactory.createArrayBacked(
            ModifyAttributes.class,
            callbacks -> attributes -> {
                for (ModifyAttributes callback : callbacks) {
                    callback.modifyAttributes(attributes);
                }
            }
    );

    @FunctionalInterface
    public interface ModifyAttributes {
        void modifyAttributes(AttributeMap attributes);
    }

    public static final class AttributeMap {
        private final Map<EntityType<? extends LivingEntity>, Map<Attribute, Double>> additions = new HashMap<>();
        private List<EntityType<? extends LivingEntity>> cachedTypes;

        public List<EntityType<? extends LivingEntity>> getTypes() {
            if (cachedTypes == null) {
                cachedTypes = BuiltInRegistries.ENTITY_TYPE.stream()
                        .filter(t -> LivingEntity.class.isAssignableFrom(t.getBaseClass()))
                        .<EntityType<? extends LivingEntity>>map(t -> (EntityType<? extends LivingEntity>) t)
                        .filter(DefaultAttributes::hasSupplier)
                        .toList();
            }
            return cachedTypes;
        }

        public void add(EntityType<? extends LivingEntity> entityType, Attribute attribute, double value) {
            additions.computeIfAbsent(entityType, k -> new HashMap<>()).put(attribute, value);
        }

        public void add(EntityType<? extends LivingEntity> entityType, Attribute attribute) {
            add(entityType, attribute, attribute.getDefaultValue());
        }

        public boolean has(EntityType<? extends LivingEntity> entityType, Attribute attribute) {
            if (DefaultAttributes.hasSupplier(entityType)
                    && DefaultAttributes.getSupplier(entityType).hasAttribute(attribute)) {
                return true;
            }
            Map<Attribute, Double> map = additions.get(entityType);
            return map != null && map.containsKey(attribute);
        }

        public Map<EntityType<? extends LivingEntity>, Map<Attribute, Double>> getAdditions() {
            return additions;
        }
    }
}

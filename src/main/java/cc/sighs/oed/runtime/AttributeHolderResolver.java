package cc.sighs.oed.runtime;

import cc.sighs.oed.asm.DamagePointConfig;
import cc.sighs.oed.asm.DamagePointData;
import com.flechazo.hkt.business.control.MaybePath;
import com.flechazo.hkt.business.core.Pathway;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AttributeHolderResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<Class<? extends LivingEntity>, List<EntityType<? extends LivingEntity>>> inferredOwnerTypes = new ConcurrentHashMap<>();

    public MaybePath<LivingEntity> resolve(Entity attacker) {
        if (attacker instanceof LivingEntity living) {
            return Pathway.just(living);
        }
        if (attacker instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity owner) {
            return Pathway.just(owner);
        }
        return Pathway.nothing();
    }

    public MaybePath<LivingEntity> infer(LivingEntity target, DamagePointData.DamagePoint point) {
        if (!DamagePointConfig.inferAttributeHolder()) {
            return Pathway.nothing();
        }

        List<EntityType<? extends LivingEntity>> ownerTypes = inferredLivingOwnerTypes(point.owner(), target.level());
        if (ownerTypes.isEmpty()) {
            return Pathway.nothing();
        }

        AABB bounds = target.getBoundingBox().inflate(DamagePointConfig.inferAttributeHolderSearchRadius());
        List<LivingEntity> candidates = target.level().getEntitiesOfClass(
                LivingEntity.class,
                bounds,
                entity -> entity != target && ownerTypes.contains(entity.getType())
        );
        if (candidates.isEmpty()) {
            return Pathway.nothing();
        }

        return Pathway.optional(candidates.stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(target)))
        );
    }

    private List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(String scanOwner, Level level) {
        int innerClassMarker = scanOwner.indexOf('$');
        String className = innerClassMarker >= 0 ? scanOwner.substring(0, innerClassMarker) : scanOwner;

        return Pathway.tryOf(() -> Class.forName(className))
                .toMaybePath()
                .filter(LivingEntity.class::isAssignableFrom)
                .map(ownerClass -> inferredLivingOwnerTypes(ownerClass.asSubclass(LivingEntity.class), level))
                .getOrElse(List.of());
    }

    private List<EntityType<? extends LivingEntity>> inferredLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        return inferredOwnerTypes.computeIfAbsent(ownerClass, key -> scanLivingOwnerTypes(key, level));
    }

    @SuppressWarnings("unchecked")
    private List<EntityType<? extends LivingEntity>> scanLivingOwnerTypes(Class<? extends LivingEntity> ownerClass, Level level) {
        List<EntityType<? extends LivingEntity>> entityTypes = new ArrayList<>();
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            createEntityForType(entityType, level)
                    .filter(ownerClass::isInstance)
                    .peek(ignored -> entityTypes.add((EntityType<? extends LivingEntity>) entityType));
        }
        if (!entityTypes.isEmpty()) {
            LOGGER.info("OED inferred {} owner types {}", ownerClass.getName(), entityTypes.stream().map(BuiltInRegistries.ENTITY_TYPE::getKey).toList());
        }
        return List.copyOf(entityTypes);
    }

    private static MaybePath<Entity> createEntityForType(EntityType<?> entityType, Level level) {
        return Pathway.<Entity>tryOf(() -> entityType.create(level)).toMaybePath();
    }
}

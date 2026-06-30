package cc.sighs.oed.asm;

import cc.sighs.oed.OneEnoughDamage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraftforge.registries.ForgeRegistries;

public final class DamagePointHooks {
    private static final Map<String, DamagePointData.DamagePoint> DAMAGE_POINTS_BY_CALLER = buildDamagePointIndex();

    private DamagePointHooks() {
    }

    public static float modifyIncomingDamage(LivingEntity target, DamageSource source, float amount) {
        DamagePointData.DamagePoint point = findDamagePoint(amount);
        if (point == null) {
            return amount;
        }

        Entity attacker = source.getEntity();
        if (attacker == null) {
            attacker = source.getDirectEntity();
        }
        return getDamage(attacker, point.attributePath(), amount);
    }

    public static float getDamage(Entity attacker, String attributePath, float fallback) {
        LivingEntity living = resolveAttributeHolder(attacker);
        if (living == null) {
            return fallback;
        }

        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(OneEnoughDamage.MODID, attributePath));
        if (attribute == null) {
            return fallback;
        }

        AttributeInstance instance = living.getAttribute(attribute);
        return instance == null ? fallback : (float) instance.getValue();
    }

    private static DamagePointData.DamagePoint findDamagePoint(float amount) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stackTrace) {
            DamagePointData.DamagePoint point = DAMAGE_POINTS_BY_CALLER.get(frame.getClassName() + "#" + frame.getMethodName());
            if (point != null && Float.compare(point.defaultDamage(), amount) == 0) {
                return point;
            }
        }
        return null;
    }

    private static Map<String, DamagePointData.DamagePoint> buildDamagePointIndex() {
        List<DamagePointData.DamagePoint> points = DamagePointData.points();
        Map<String, DamagePointData.DamagePoint> unique = new HashMap<>();
        Map<String, Boolean> ambiguous = new HashMap<>();
        for (DamagePointData.DamagePoint point : points) {
            String key = point.owner() + "#" + point.method();
            DamagePointData.DamagePoint existing = unique.putIfAbsent(key, point);
            if (existing != null && Float.compare(existing.defaultDamage(), point.defaultDamage()) != 0) {
                ambiguous.put(key, true);
            }
        }
        ambiguous.keySet().forEach(unique::remove);
        return Map.copyOf(unique);
    }

    private static LivingEntity resolveAttributeHolder(Entity attacker) {
        if (attacker instanceof LivingEntity living) {
            return living;
        }
        if (attacker instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity owner) {
            return owner;
        }
        return null;
    }
}

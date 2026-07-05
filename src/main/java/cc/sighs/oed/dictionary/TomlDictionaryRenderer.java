package cc.sighs.oed.dictionary;

import cc.sighs.oed.asm.DamagePointTomlConfig;
import cc.sighs.oed.scan.DamagePointScanResult;
import com.flechazo.hkt.business.control.MaybePath;
import com.flechazo.hkt.business.core.Pathway;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TomlDictionaryRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TomlDictionaryRenderer() {
    }

    public static void render(Map<String, Map<MobKey, List<DamagePointScanResult>>> groups, Path outputFile) {
        Map<String, Float> configuredValues = DamagePointTomlConfig.readValues(outputFile);
        Set<String> writtenKeys = new LinkedHashSet<>();
        StringBuilder lines = new StringBuilder();
        lines.append("# OneEnoughDamage 硬编码伤害点配置字典\n");
        lines.append("# 改等号右侧的数字即可修改对应 attribute 的初始值，重启游戏后生效。\n");
        lines.append("# /r 表示替换原伤害，/m 表示作为乘数参与计算。\n\n");
        for (Map.Entry<String, Map<MobKey, List<DamagePointScanResult>>> namespaceEntry : groups.entrySet()) {
            Map<MobKey, List<DamagePointScanResult>> mobs = namespaceEntry.getValue();
            if (mobs == null || mobs.isEmpty()) {
                continue;
            }

            for (Map.Entry<MobKey, List<DamagePointScanResult>> entry : mobs.entrySet()) {
                MobKey key = entry.getKey();
                lines.append("# ").append(key.fallback());
                if (!key.enName().isBlank() || !key.zhName().isBlank()) {
                    lines.append(" - ").append(key.enName());
                    if (!key.zhName().equals(key.enName())) {
                        lines.append("（").append(key.zhName()).append("）");
                    }
                }
                String typeLabel = typeLabel(key.type());
                if (!typeLabel.isEmpty()) {
                    lines.append("（类型：").append(typeLabel).append("）");
                }
                lines.append("\n");
                appendAttackDamageConfig(lines, configuredValues, writtenKeys, key);

                List<DamagePointScanResult> points = entry.getValue();
                points.sort(Comparator.comparing((DamagePointScanResult p) -> p.owner())
                        .thenComparing(DamagePointScanResult::method)
                        .thenComparingInt(DamagePointScanResult::ordinal));
                for (DamagePointScanResult point : points) {
                    String attribute = point.attribute();
                    float value = configuredValues.getOrDefault(attribute, point.defaultDamage());
                    writtenKeys.add(attribute);
                    lines.append("# 模式：").append(point.constant() ? "替换（r）" : "乘数（m）")
                            .append("，默认 ").append(point.defaultDamage())
                            .append("，伤害源 ").append(point.damageSource())
                            .append("，").append(point.description())
                            .append("\n");
                    lines.append('"').append(escapeTomlString(attribute)).append("\" = ")
                            .append(formatFloat(value)).append("\n");
                }
                lines.append("\n");
            }
        }
        appendUnmatchedConfig(lines, configuredValues, writtenKeys);

        Pathway.tryOf(() -> {
                    Files.createDirectories(outputFile.getParent());
                    if (isUnchanged(outputFile, lines.toString())) {
                        LOGGER.info("OED dictionary: toml unchanged at {}", outputFile);
                        return outputFile;
                    }
                    Files.writeString(outputFile, lines.toString(), StandardCharsets.UTF_8);
                    LOGGER.info("OED dictionary: wrote toml to {}", outputFile);
                    return outputFile;
                })
                .peekFailure(error -> LOGGER.error("OED dictionary: failed to write toml", error));
    }

    private static boolean isUnchanged(Path outputFile, String content) throws IOException {
        return Files.isRegularFile(outputFile) && Files.readString(outputFile, StandardCharsets.UTF_8).equals(content);
    }

    private static String typeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "living" -> "生物";
            case "projectile" -> "弹射物";
            case "entity" -> "实体";
            case "item" -> "物品";
            case "block" -> "方块";
            case "effect" -> "效果";
            case "behavior" -> "AI 行为";
            default -> "其他";
        };
    }

    private static String escapeTomlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendAttackDamageConfig(StringBuilder lines, Map<String, Float> configuredValues, Set<String> writtenKeys, MobKey key) {
        if (!"living".equals(key.type()) || key.entityId() == null || key.entityId().isBlank()) {
            return;
        }

        String configKey = "minecraft:generic.attack_damage@" + key.entityId();
        attackDamageDefault(key.entityId())
                .peek(defaultValue -> {
                    float value = configuredValues.getOrDefault(configKey, defaultValue);
                    writtenKeys.add(configKey);
                    lines.append("# 原版近战基础伤害：只作用于 ").append(key.entityId()).append("\n");
                    lines.append("# Vanilla melee base damage: only applies to ").append(key.entityId()).append("\n");
                    lines.append('"').append(escapeTomlString(configKey)).append("\" = ")
                            .append(formatFloat(value)).append("\n");
                });
    }

    private static void appendUnmatchedConfig(StringBuilder lines, Map<String, Float> configuredValues, Set<String> writtenKeys) {
        List<Map.Entry<String, Float>> unmatched = configuredValues.entrySet().stream()
                .filter(entry -> !writtenKeys.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (unmatched.isEmpty()) {
            return;
        }

        lines.append("# 未匹配旧配置\n");
        lines.append("# 这些 key 来自旧 TOML，但本次扫描没有匹配到。可能是模组已删除、版本改名，或扫描结果变化。\n");
        lines.append("# OED 不会自动删除它们；确认无用后可以手动删掉。\n");
        for (Map.Entry<String, Float> entry : unmatched) {
            lines.append('"').append(escapeTomlString(entry.getKey())).append("\" = ")
                    .append(formatFloat(entry.getValue())).append("\n");
        }
    }

    private static MaybePath<Float> attackDamageDefault(String entityId) {
        return Pathway.nullable(ResourceLocation.tryParse(entityId))
                .via(id -> Pathway.nullable(BuiltInRegistries.ENTITY_TYPE.get(id)))
                .filter(DefaultAttributes::hasSupplier)
                .via(entityType -> Pathway.tryOf(() -> {
                            @SuppressWarnings("unchecked")
                            EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) entityType;
                            return livingType;
                        })
                        .toMaybePath())
                .filter(livingType -> DefaultAttributes.getSupplier(livingType).hasAttribute(Attributes.ATTACK_DAMAGE))
                .map(livingType -> (float) DefaultAttributes.getSupplier(livingType).getBaseValue(Attributes.ATTACK_DAMAGE));
    }

    private static String formatFloat(float value) {
        if (Float.isFinite(value) && value == (long) value) {
            return (long) value + ".0";
        }
        return Float.toString(value);
    }
}

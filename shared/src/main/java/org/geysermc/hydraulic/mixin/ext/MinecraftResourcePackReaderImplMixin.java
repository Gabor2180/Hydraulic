package org.geysermc.hydraulic.mixin.ext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import net.kyori.adventure.key.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import team.unnamed.creative.overlay.ResourceContainer;
import team.unnamed.creative.part.ResourcePackPart;
import team.unnamed.creative.serialize.minecraft.GsonUtil;
import team.unnamed.creative.serialize.minecraft.io.JsonResourceDeserializer;

import java.io.IOException;
import java.util.Map;

@Mixin(targets = "team.unnamed.creative.serialize.minecraft.MinecraftResourcePackReaderImpl", remap = false)
public abstract class MinecraftResourcePackReaderImplMixin {
    private static Logger LOGGER = LoggerFactory.getLogger("MinecraftResourcePackReaderImplMixin");

    /**
     * Redirect the parseJson method to catch any exceptions that may occur
     * This means a single bad json file won't cause the entire resource pack to fail loading
     */
    @Redirect(
        method = "parseJson",
        at = @At(
            value = "INVOKE",
            target = "Lteam/unnamed/creative/serialize/minecraft/GsonUtil;parseReader(Lcom/google/gson/stream/JsonReader;)Lcom/google/gson/JsonElement;"
        )
    )
    private JsonElement parseJson(JsonReader reader) {
        try {
            return GsonUtil.parseReader(reader);
        } catch (Exception e) {
            LOGGER.error("Failed to parse JSON: " + e.getMessage());
        }

        return null;
    }

    /**
     * Redirect the deserializeFromJson to ignore any null JsonElements
     * Also catch any exceptions that may occur and log them
     */
    @Redirect(
        method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
        at = @At(
            value = "INVOKE",
            target = "Lteam/unnamed/creative/serialize/minecraft/io/JsonResourceDeserializer;deserializeFromJson(Lcom/google/gson/JsonElement;Lnet/kyori/adventure/key/Key;)Ljava/lang/Object;"
        )
    )
    private Object deserializeFromJson(JsonResourceDeserializer instance, JsonElement jsonElement, Key key) throws IOException {
        if (jsonElement == null) {
            return null;
        }

        // Strip any tint source entries whose type is not in the minecraft: namespace.
        // The creative library throws on unknown tint types (e.g. biomeswevegone:foliage),
        // which prevents the whole model from loading. Bedrock has no Java-style tint
        // sources, so removing them is completely safe.
        stripUnknownTintSources(jsonElement);

        try {
            return instance.deserializeFromJson(jsonElement, key);
        } catch (Exception e) {
            LOGGER.error("Failed to deserialize JSON (" + key + "): " + e.getMessage());
        }

        return null;
    }

    /**
     * Recursively walks a {@link JsonElement} and, for every object that contains
     * a {@code "tints"} array, removes entries whose {@code "type"} field is not
     * in the {@code minecraft:} namespace.
     *
     * <p>This is necessary because the creative library only registers vanilla tint
     * source types. Mod-defined types (e.g. {@code biomeswevegone:foliage}) cause
     * the deserializer to throw, aborting the entire model load.</p>
     */
    private static void stripUnknownTintSources(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                stripUnknownTintSources(entry);
            }
            return;
        }

        JsonObject obj = element.getAsJsonObject();

        if (obj.has("tints") && obj.get("tints").isJsonArray()) {
            JsonArray tints = obj.getAsJsonArray("tints");
            JsonArray filtered = new JsonArray();
            for (JsonElement tint : tints) {
                if (tint.isJsonObject()) {
                    JsonObject tintObj = tint.getAsJsonObject();
                    String type = tintObj.has("type") ? tintObj.get("type").getAsString() : "minecraft:constant";
                    if (type.startsWith("minecraft:")) {
                        filtered.add(tint);
                    }
                    // Non-minecraft tint types are intentionally dropped.
                } else {
                    filtered.add(tint);
                }
            }
            obj.add("tints", filtered);
        }

        // Recurse into all child elements (covers nested model structures such as
        // composite, select, condition, range_dispatch, etc.).
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!entry.getKey().equals("tints")) {
                stripUnknownTintSources(entry.getValue());
            }
        }
    }

    @Redirect(
            method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
            at = @At(
                    value = "INVOKE",
                    target = "Lteam/unnamed/creative/part/ResourcePackPart;addTo(Lteam/unnamed/creative/overlay/ResourceContainer;)V"
            )
    )
    private void addTo(ResourcePackPart instance, ResourceContainer resourceContainer) {
        if (instance != null) {
            instance.addTo(resourceContainer);
        }
    }

    //Key key = Key.key(namespace, keyValue);
    @ModifyArgs(
            method = "read(Lteam/unnamed/creative/serialize/minecraft/fs/FileTreeReader;)Lteam/unnamed/creative/ResourcePack;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/kyori/adventure/key/Key;key(Ljava/lang/String;Ljava/lang/String;)Lnet/kyori/adventure/key/Key;",
                    ordinal = 2
            )
    )
    private void injectKeyCreation(Args args) {
        args.set(1, ((String) args.get(1)).toLowerCase());
    }
}

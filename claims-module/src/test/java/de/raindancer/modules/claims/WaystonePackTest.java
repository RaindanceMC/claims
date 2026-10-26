package de.raindancer.modules.claims;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.modules.claims.selection.ClaimsPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The waystone's looks: every item the code sets resolves to a model, and every texture of ours ships. */
class WaystonePackTest {

    private static JsonObject json(String file) throws IOException {
        try (InputStream in = ClaimsPack.class.getResourceAsStream(ClaimsPack.ROOT + file)) {
            assertThat(in).as(file).isNotNull();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("every file the pack lists is in the jar")
    void everyFileShips() throws IOException {
        for (String file : ClaimsPack.FILES) {
            try (InputStream in = ClaimsPack.class.getResourceAsStream(ClaimsPack.ROOT + file)) {
                assertThat(in).as(file).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("each waystone item points at a model, and our models at textures the pack ships")
    void chain() throws IOException {
        for (var key : List.of(ClaimsPack.WAYSTONE, ClaimsPack.WAYSTONE_BASE, ClaimsPack.WAYSTONE_FIRE,
                ClaimsPack.WAYSTONE_SOUL_FIRE)) {
            String itemFile = "assets/" + key.namespace() + "/items/" + key.value() + ".json";
            assertThat(ClaimsPack.FILES).contains(itemFile);
            String model = json(itemFile).getAsJsonObject("model").get("model").getAsString();
            if (model.startsWith("minecraft:")) {
                continue;   // vanilla's own fire, which every client has
            }
            String modelFile = "assets/rainsclaims/models/" + model.split(":")[1] + ".json";
            assertThat(ClaimsPack.FILES).as(key.asString()).contains(modelFile);
            JsonObject textures = json(modelFile).getAsJsonObject("textures");
            for (var texture : textures.entrySet()) {
                String ref = texture.getValue().getAsString();
                if (ref.startsWith("rainsclaims:")) {
                    assertThat(ClaimsPack.FILES).as(texture.getKey())
                            .contains("assets/rainsclaims/textures/" + ref.split(":")[1] + ".png");
                }
            }
            assertThat(json(modelFile).getAsJsonArray("elements")).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the runes pulse: their texture ships with its animation")
    void runesAnimate() throws IOException {
        assertThat(ClaimsPack.FILES).contains("assets/rainsclaims/textures/block/claim_waystone_rune.png.mcmeta");
        assertThat(json("assets/rainsclaims/textures/block/claim_waystone_rune.png.mcmeta").has("animation"))
                .isTrue();
    }

    @Test
    @DisplayName("the held waystone has its inventory angles and stays inside what a model may span")
    void heldItem() throws IOException {
        JsonObject model = json("assets/rainsclaims/models/block/claim_waystone.json");
        assertThat(model.getAsJsonObject("display").has("gui")).isTrue();
        for (String file : List.of("assets/rainsclaims/models/block/claim_waystone.json",
                "assets/rainsclaims/models/block/claim_waystone_base.json")) {
            for (var element : json(file).getAsJsonArray("elements")) {
                for (String corner : List.of("from", "to")) {
                    for (var value : element.getAsJsonObject().getAsJsonArray(corner)) {
                        assertThat(value.getAsDouble()).as(file).isBetween(-16.0, 32.0);
                    }
                }
            }
        }
    }
}

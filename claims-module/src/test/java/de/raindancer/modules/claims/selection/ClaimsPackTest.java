package de.raindancer.modules.claims.selection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimsPackTest {

    private static JsonObject json(String file) throws IOException {
        try (InputStream in = ClaimsPack.class.getResourceAsStream(ClaimsPack.ROOT + file)) {
            assertThat(in).as(file).isNotNull();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    @DisplayName("every file the pack ships is in the jar")
    void everyFileShips() throws IOException {
        for (String file : ClaimsPack.FILES) {
            try (InputStream in = ClaimsPack.class.getResourceAsStream(ClaimsPack.ROOT + file)) {
                assertThat(in).as(file).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("a claim stick wears the claim model, a no-claim stick the red one")
    void modelPerPurpose() {
        assertThat(SelectionStick.modelFor(Selection.Purpose.NEW_CLAIM)).isEqualTo(ClaimsPack.CLAIM_STICK);
        assertThat(SelectionStick.modelFor(Selection.Purpose.RESIZE_CLAIM)).isEqualTo(ClaimsPack.CLAIM_STICK);
        assertThat(SelectionStick.modelFor(Selection.Purpose.ADMIN_RESHAPE)).isEqualTo(ClaimsPack.CLAIM_STICK);
        assertThat(SelectionStick.modelFor(Selection.Purpose.NO_CLAIM_ZONE)).isEqualTo(ClaimsPack.NO_CLAIM_STICK);
    }

    @Test
    @DisplayName("each item definition points at a model the pack ships, and every texture a model names is shipped")
    void itemsModelsTextures() throws IOException {
        for (var key : java.util.List.of(ClaimsPack.CLAIM_STICK, ClaimsPack.NO_CLAIM_STICK)) {
            assertThat(key.namespace()).isEqualTo("rainsclaims");
            JsonObject item = json("assets/rainsclaims/items/" + key.value() + ".json").getAsJsonObject("model");
            String model = item.get("model").getAsString();
            assertThat(model).isEqualTo("rainsclaims:item/" + key.value());
            JsonObject definition = json("assets/rainsclaims/models/" + model.split(":")[1] + ".json");
            for (var texture : definition.getAsJsonObject("textures").entrySet()) {
                String path = "assets/rainsclaims/textures/" + texture.getValue().getAsString().split(":")[1] + ".png";
                assertThat(ClaimsPack.FILES).as(key + " " + texture.getKey()).contains(path);
            }
        }
        JsonObject stick = json("assets/rainsclaims/models/item/claim_stick.json");
        assertThat(stick.getAsJsonArray("elements")).isNotEmpty();
        assertThat(stick.getAsJsonObject("display").has("gui")).isTrue();
        assertThat(json("assets/rainsclaims/models/item/no_claim_stick.json").get("parent").getAsString())
                .isEqualTo("rainsclaims:item/claim_stick");
    }

    @Test
    @DisplayName("the pack declares a format range that takes in 26.2 and 26.3 clients")
    void format() throws IOException {
        JsonObject pack = json("pack.mcmeta").getAsJsonObject("pack");
        assertThat(pack.get("min_format").getAsInt()).isLessThanOrEqualTo(88);
        assertThat(pack.get("max_format").getAsInt()).isGreaterThanOrEqualTo(97);
    }
}

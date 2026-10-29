package fi.alavesa.backpacks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.alavesa.backpacks.model.BbModel;
import fi.alavesa.backpacks.model.BbModelParser;
import fi.alavesa.backpacks.model.Issue;
import fi.alavesa.backpacks.model.ModelConverter;
import fi.alavesa.backpacks.model.PackBuilder;
import fi.alavesa.backpacks.model.ParseOptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelPipelineTest {

    private static final String[] MODELS = {"leather_backpack", "copper_backpack", "iron_backpack", "gold_backpack", "diamond_backpack", "netherite_backpack"};

    private static String res(String name) throws Exception {
        try (InputStream in = ModelPipelineTest.class.getResourceAsStream("/backpack_models/" + name + ".bbmodel")) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundledModelsBuildCleanly() throws Exception {
        List<PackBuilder.Source> src = new ArrayList<>();
        for (String m : MODELS) src.add(new PackBuilder.Source(m, res(m), r -> null));
        PackBuilder.Output out = PackBuilder.build(src, new PackBuilder.Options("backpacks", "test", 46, 999, ParseOptions.STRICT));
        assertEquals(0, out.errors().size(), out.errors().toString());
        assertEquals(List.of(MODELS), out.exportedModels());
        for (String m : MODELS) {
            assertTrue(out.files().containsKey("assets/backpacks/items/" + m + ".json"), m + " items file");
            assertTrue(out.files().containsKey("assets/backpacks/textures/item/" + m + "/" + m + ".png"), m + " texture");
            JsonObject items = JsonParser.parseString(new String(out.files().get("assets/backpacks/items/" + m + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("minecraft:model", items.getAsJsonObject("model").get("type").getAsString(), "one group -> single model");
        }
        assertTrue(out.files().containsKey("pack.mcmeta"));
        JsonObject mcmeta = JsonParser.parseString(new String(out.files().get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(46, mcmeta.getAsJsonObject("pack").get("pack_format").getAsInt());
    }

    @Test
    void fixedTransformAnchorsBottomCentre() throws Exception {
        BbModelParser.Result r = BbModelParser.parse("leather_backpack", res("leather_backpack"), null, ParseOptions.STRICT);
        assertTrue(r.ok(), r.issues().toString());
        BbModel m = r.model();
        assertEquals("java_block", m.format);
        assertNotNull(m.display);
        double[] b = m.bounds();
        var cm = new ModelConverter("backpacks").convert(m);
        assertEquals(1, cm.parts());
        String wrapper = new String(cm.files().get("assets/backpacks/models/item/leather_backpack__p0.json"), StandardCharsets.UTF_8);
        JsonObject fixed = JsonParser.parseString(wrapper).getAsJsonObject().getAsJsonObject("display").getAsJsonObject("fixed");
        var tr = fixed.getAsJsonArray("translation");
        // group pivot is (8,0,8): translation = pivot - (cx, minY, cz)
        assertEquals(8 - (b[0] + b[3]) / 2, tr.get(0).getAsDouble(), 0.01);
        assertEquals(0 - b[1], tr.get(1).getAsDouble(), 0.01);
        assertEquals(8 - (b[2] + b[5]) / 2, tr.get(2).getAsDouble(), 0.01);
        assertEquals(1.0, fixed.getAsJsonArray("scale").get(0).getAsDouble(), 1e-9);
        // hand transform from the file: java format anchor (8,8,8) -> part at pivot (8,0,8) gives translation (0, 3 + 0.4*(-8), 1)
        JsonObject tp = JsonParser.parseString(wrapper).getAsJsonObject().getAsJsonObject("display").getAsJsonObject("thirdperson_righthand");
        assertEquals(0.4, tp.getAsJsonArray("scale").get(0).getAsDouble(), 1e-6);
        assertEquals(3 + 0.4 * -8, tp.getAsJsonArray("translation").get(1).getAsDouble(), 0.01);
        // geometry stays inside the client's element range
        String geom = new String(cm.files().get("assets/backpacks/models/item/leather_backpack__g0.json"), StandardCharsets.UTF_8);
        for (var el : JsonParser.parseString(geom).getAsJsonObject().getAsJsonArray("elements")) {
            for (var v : el.getAsJsonObject().getAsJsonArray("from")) assertTrue(v.getAsDouble() >= -16 && v.getAsDouble() <= 32);
            for (var v : el.getAsJsonObject().getAsJsonArray("to")) assertTrue(v.getAsDouble() >= -16 && v.getAsDouble() <= 32);
        }
    }

    @Test
    void rotatedGroupBecomesExactPartAndFreeModelGetsDefaults() {
        String json = """
            {"meta":{"format_version":"4.5","model_format":"free","box_uv":false},"resolution":{"width":16,"height":16},
             "elements":[{"name":"a","from":[0,0,0],"to":[4,4,4],"origin":[0,0,0],"rotation":[0,0,0],"uuid":"e1",
                          "faces":{"north":{"uv":[0,0,4,4],"texture":0}}},
                         {"name":"b","from":[6,0,0],"to":[10,4,4],"origin":[8,0,0],"rotation":[0,37,0],"uuid":"e2",
                          "faces":{"north":{"uv":[0,0,4,4],"texture":0}}}],
             "outliner":[{"name":"g","origin":[2,2,2],"rotation":[0,30,0],"uuid":"g1","children":["e1"]},"e2"],
             "textures":[]}
            """;
        BbModelParser.Result r = BbModelParser.parse("Odd Model", json, null, new ParseOptions(ParseOptions.MeshPolicy.FAIL, ParseOptions.MissingTexturePolicy.WARN));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals("odd_model", r.model().path);
        var cm = new ModelConverter("backpacks").convert(r.model());
        assertEquals(2, cm.parts(), "group part + solo rotated cube");
        boolean warnedDisplay = false;
        for (Issue i : cm.issues()) if (i.message().contains("no 'display' section")) warnedDisplay = true;
        assertTrue(warnedDisplay);
        String wrap = new String(cm.files().get("assets/backpacks/models/item/odd_model__p0.json"), StandardCharsets.UTF_8);
        JsonObject fixed = JsonParser.parseString(wrap).getAsJsonObject().getAsJsonObject("display").getAsJsonObject("fixed");
        assertEquals(30, fixed.getAsJsonArray("rotation").get(1).getAsDouble(), 0.01, "exact group yaw kept");
        String solo = new String(cm.files().get("assets/backpacks/models/item/odd_model__pc0.json"), StandardCharsets.UTF_8);
        JsonObject soloFixed = JsonParser.parseString(solo).getAsJsonObject().getAsJsonObject("display").getAsJsonObject("fixed");
        assertEquals(37, soloFixed.getAsJsonArray("rotation").get(1).getAsDouble(), 0.01, "free cube rotation kept exactly");
    }

    @Test
    void brokenFilesAreReported() {
        var r1 = BbModelParser.parse("x", "not json", null, ParseOptions.STRICT);
        assertFalse(r1.ok());
        var r2 = BbModelParser.parse("y", "{\"elements\":[]}", null, ParseOptions.STRICT);
        assertFalse(r2.ok());
        assertTrue(r2.issues().get(r2.issues().size() - 1).message().contains("no cube elements"));
    }
}

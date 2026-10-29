package fi.alavesa.backpacks.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * .bbmodel JSON -> {@link BbModel} with validation. Supported: Generic / Java block-item / Bedrock /
 * GeckoLib formats, cube elements with per-face or box UV, groups with pivot and rotation, embedded
 * (base64) or sibling-file textures. Unsupported and reported: meshes (policy), animations (ignored with
 * a warning - rest pose only), locators/null objects (ignored), per-face tint, texture-less models.
 */
public final class BbModelParser {

    public record Result(BbModel model, List<Issue> issues) {
        public boolean ok() { return model != null; }
    }

    private BbModelParser() { }

    /**
     * @param name            logical model name (file name without extension)
     * @param json            file content
     * @param textureResolver returns the PNG bytes for a texture that is not embedded, by its recorded
     *                        name / relative path, or null
     */
    public static Result parse(String name, String json, Function<String, byte[]> textureResolver, ParseOptions opts) {
        List<Issue> issues = new ArrayList<>();
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || !el.isJsonObject()) { issues.add(Issue.error(name, "malformed file: not a JSON object")); return new Result(null, issues); }
            root = el.getAsJsonObject();
        } catch (Exception e) {
            issues.add(Issue.error(name, "malformed file: " + e.getMessage()));
            return new Result(null, issues);
        }
        if (!root.has("elements") || !root.get("elements").isJsonArray()) {
            issues.add(Issue.error(name, "no 'elements' array - is this a Blockbench model?"));
            return new Result(null, issues);
        }
        double resW = 16, resH = 16;
        if (root.has("resolution") && root.get("resolution").isJsonObject()) {
            JsonObject r = root.getAsJsonObject("resolution");
            resW = num(r.get("width"), 16); resH = num(r.get("height"), 16);
        }
        BbModel m = new BbModel(name, resW, resH);
        if (root.has("meta") && root.get("meta").isJsonObject()) m.format = str(root.getAsJsonObject("meta").get("model_format"), "");
        if (root.has("display") && root.get("display").isJsonObject()) m.display = root.getAsJsonObject("display");
        if (!m.path.equals(name)) issues.add(Issue.warning(name, "name has upper-case letters or symbols - files are written as '" + m.path + "' (keep using '" + name + "' in configs)"));
        boolean metaBoxUv = root.has("meta") && root.get("meta").isJsonObject() && root.getAsJsonObject("meta").has("box_uv")
            && root.getAsJsonObject("meta").get("box_uv").isJsonPrimitive() && root.getAsJsonObject("meta").get("box_uv").getAsBoolean();

        // textures
        int ti = 0;
        if (root.has("textures") && root.get("textures").isJsonArray()) for (JsonElement te : root.getAsJsonArray("textures")) {
            if (!te.isJsonObject()) continue;
            JsonObject t = te.getAsJsonObject();
            String tname = str(t.get("name"), "tex" + ti).replaceAll("\\.png$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (tname.isEmpty()) tname = "tex" + ti;
            for (BbModel.Texture other : m.textures) if (other.key().equals(tname)) { tname = tname + "_" + ti; break; }
            byte[] png = null;
            String src = str(t.get("source"), "");
            if (src.contains("base64,")) {
                try { png = Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7)); } catch (Exception e) { issues.add(Issue.warning(name, "texture '" + tname + "': embedded data is not valid base64")); }
            }
            if (png == null && textureResolver != null) {
                for (String cand : new String[] {str(t.get("relative_path"), ""), str(t.get("name"), ""), str(t.get("path"), "")}) {
                    if (cand.isEmpty()) continue;
                    png = textureResolver.apply(cand);
                    if (png != null) break;
                }
            }
            if (png != null && !isPng(png)) { issues.add(Issue.error(name, "texture '" + tname + "' is not a PNG file")); png = null; }
            if (png == null) {
                String msg = "texture '" + tname + "' is not embedded in the file and was not found next to it (put the .png beside the .bbmodel or re-save with the texture embedded)";
                if (opts.missingTexturePolicy() == ParseOptions.MissingTexturePolicy.FAIL) { issues.add(Issue.error(name, msg)); return new Result(null, issues); }
                issues.add(Issue.warning(name, msg + " - it will render purple/black"));
            }
            List<String> ids = new ArrayList<>();
            ids.add(String.valueOf(ti));
            if (t.has("id")) ids.add(str(t.get("id"), ""));
            if (t.has("uuid")) ids.add(str(t.get("uuid"), ""));
            m.textures.add(new BbModel.Texture(tname, png, num(t.get("uv_width"), resW), num(t.get("uv_height"), resH), ids));
            ti++;
        }
        if (m.textures.isEmpty()) issues.add(Issue.warning(name, "no textures in the file - faces will use the fallback texture"));

        // elements
        List<String> meshNames = new ArrayList<>();
        for (JsonElement ee : root.getAsJsonArray("elements")) {
            if (!ee.isJsonObject()) continue;
            JsonObject e = ee.getAsJsonObject();
            if (e.has("visibility") && e.get("visibility").isJsonPrimitive() && !e.get("visibility").getAsBoolean()) continue;
            if (e.has("export") && e.get("export").isJsonPrimitive() && !e.get("export").getAsBoolean()) continue;
            String type = str(e.get("type"), "cube");
            if (type.equals("mesh")) {
                m.meshCount++;
                meshNames.add(str(e.get("name"), "mesh"));
                if (opts.meshPolicy() == ParseOptions.MeshPolicy.BOUNDING_BOX) {
                    BbModel.Cube box = meshBox(e, m.cubes.size());
                    if (box != null) m.cubes.put(box.uuid, box);
                }
                continue;
            }
            if (!type.equals("cube")) { issues.add(Issue.warning(name, "element '" + str(e.get("name"), "?") + "' of type '" + type + "' is not supported and was skipped")); continue; }
            if (!e.has("from") || !e.has("to")) { issues.add(Issue.warning(name, "cube '" + str(e.get("name"), "?") + "' has no from/to and was skipped")); continue; }
            BbModel.Cube c = new BbModel.Cube();
            c.uuid = str(e.get("uuid"), "e" + m.cubes.size());
            c.name = str(e.get("name"), "");
            c.from = vec(e.get("from")); c.to = vec(e.get("to"));
            if (e.has("origin")) c.origin = vec(e.get("origin"));
            if (e.has("inflate")) c.inflate = num(e.get("inflate"), 0);
            if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
            c.mirror = e.has("mirror_uv") && e.get("mirror_uv").isJsonPrimitive() && e.get("mirror_uv").getAsBoolean();
            c.boxUv = e.has("box_uv") && e.get("box_uv").isJsonPrimitive() ? e.get("box_uv").getAsBoolean() : metaBoxUv;
            if (e.has("uv_offset") && e.get("uv_offset").isJsonArray()) { JsonArray u = e.getAsJsonArray("uv_offset"); if (u.size() >= 2) c.uvOffset = new double[] {num(u.get(0), 0), num(u.get(1), 0)}; }
            c.faces = e.has("faces") && e.get("faces").isJsonObject() ? e.getAsJsonObject("faces") : new JsonObject();
            m.cubes.put(c.uuid, c);
        }
        if (m.meshCount > 0) {
            if (opts.meshPolicy() == ParseOptions.MeshPolicy.FAIL) {
                issues.add(Issue.error(name, m.meshCount + " MESH element(s) " + meshNames + ": Java item models only hold cubes. Rebuild them from cubes, or set pack.mesh-policy: bounding-box to export each mesh as its bounding box"));
                return new Result(null, issues);
            }
            issues.add(Issue.warning(name, m.meshCount + " MESH element(s) " + meshNames + " exported as bounding boxes (Java item models only hold cubes)"));
        }
        if (m.cubes.isEmpty()) { issues.add(Issue.error(name, "no cube elements")); return new Result(null, issues); }

        // outliner
        if (root.has("outliner") && root.get("outliner").isJsonArray()) for (JsonElement oe : root.getAsJsonArray("outliner")) parseOutliner(oe, null, m);

        if (root.has("animations") && root.get("animations").isJsonArray()) {
            for (JsonElement ae : root.getAsJsonArray("animations")) if (ae.isJsonObject()) m.animationNames.add(str(ae.getAsJsonObject().get("name"), "?"));
            if (!m.animationNames.isEmpty()) issues.add(Issue.warning(name, "animations " + m.animationNames + " are not supported - the rest pose is exported"));
        }
        int far = 0;
        double[] b = m.bounds();
        for (int i = 0; i < 6; i++) if (Math.abs(b[i]) > 80 * 3) far++;
        if (far > 0) issues.add(Issue.warning(name, "geometry reaches more than 240 px (15 blocks) from the origin; parts beyond the client's 80 px pivot limit are clamped - keep the model near (0,0,0)"));
        return new Result(m, issues);
    }

    private static void parseOutliner(JsonElement oe, BbModel.Bone parent, BbModel m) {
        if (oe.isJsonPrimitive()) {
            BbModel.Cube c = m.cubes.get(oe.getAsString());
            if (c != null && parent != null) c.bone = parent;
            return;
        }
        if (!oe.isJsonObject()) return;
        JsonObject o = oe.getAsJsonObject();
        BbModel.Bone b = new BbModel.Bone();
        b.uuid = str(o.get("uuid"), "b" + m.bones.size());
        b.name = str(o.get("name"), b.uuid);
        if (o.has("origin")) b.origin = vec(o.get("origin"));
        if (o.has("rotation")) b.rotation = vec(o.get("rotation"));
        b.parent = parent;
        m.bones.put(b.uuid, b);
        if (parent != null) parent.children.add(b);
        if (o.has("children") && o.get("children").isJsonArray()) for (JsonElement ce : o.getAsJsonArray("children")) parseOutliner(ce, b, m);
    }

    private static BbModel.Cube meshBox(JsonObject e, int index) {
        if (!e.has("vertices") || !e.get("vertices").isJsonObject()) return null;
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (var v : e.getAsJsonObject("vertices").entrySet()) {
            if (!v.getValue().isJsonArray()) continue;
            double[] p = vec(v.getValue());
            for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
        }
        if (mn[0] > mx[0]) return null;
        BbModel.Cube c = new BbModel.Cube();
        c.uuid = str(e.get("uuid"), "m" + index);
        c.name = str(e.get("name"), "mesh");
        c.meshBox = true;
        c.origin = e.has("origin") ? vec(e.get("origin")) : new double[] {0, 0, 0};
        if (e.has("rotation")) c.rotation = vec(e.get("rotation"));
        c.from = new double[] {c.origin[0] + mn[0], c.origin[1] + mn[1], c.origin[2] + mn[2]};
        c.to = new double[] {c.origin[0] + mx[0], c.origin[1] + mx[1], c.origin[2] + mx[2]};
        for (int i = 0; i < 3; i++) if (c.to[i] - c.from[i] < 0.05) c.to[i] = c.from[i] + 0.05;
        JsonElement tex = null;
        if (e.has("faces") && e.get("faces").isJsonObject())
            for (var f : e.getAsJsonObject("faces").entrySet()) if (f.getValue().isJsonObject() && f.getValue().getAsJsonObject().has("texture")) { tex = f.getValue().getAsJsonObject().get("texture"); break; }
        c.faces = new JsonObject();
        for (String f : ModelConverter.FACE) { JsonObject face = new JsonObject(); face.add("texture", tex == null ? new com.google.gson.JsonPrimitive(0) : tex); c.faces.add(f, face); }
        return c;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    static double num(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) return def;
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return Double.parseDouble(e.getAsString().trim());
            return e.getAsDouble();
        } catch (Exception ex) { return def; }
    }

    static String str(JsonElement e, String def) { return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? def : e.getAsString(); }

    static double[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new double[] {0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return new double[] {0, 0, 0};
        return new double[] {num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0)};
    }

    static double[] vec4(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 4) return new double[] {0, 0, 16, 16};
        return new double[] {num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0), num(a.get(3), 0)};
    }
}

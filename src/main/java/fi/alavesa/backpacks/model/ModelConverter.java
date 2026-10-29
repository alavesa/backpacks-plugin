package fi.alavesa.backpacks.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BbModel} -> Java item models ("composite" layout): every Blockbench group, and every cube whose
 * rotation a Java element cannot hold exactly, becomes its own sub-model. The sub-model's geometry is
 * written around the group's pivot and a thin wrapper model carries the EXACT rest rotation and the pivot
 * position in its {@code display} transforms, so there is no 22.5-degree rounding and no shrinking of big
 * models.
 * <p>
 * Display contexts: {@code fixed} (what an ItemDisplay with the FIXED transform shows) keeps 16 px = 1 block
 * with the model's bottom-centre on the entity position - that is what the worn and placed backpacks use.
 * The hand / gui / ground / head contexts come from the Blockbench file's own {@code display} section when
 * it has one (Java Block/Item format), otherwise from vanilla block-like defaults scaled to the model size;
 * either way they are composed exactly with each part's own placement.
 */
public final class ModelConverter {

    static final String[] FACE = {"north", "south", "east", "west", "up", "down"};
    private static final int[][] DIR    = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    private static final int[][] FUP    = {{0,1,0},{0,1,0},{0,1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static final int[][] FRIGHT = {{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{1,0,0},{1,0,0}};
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String[] CONTEXTS = {"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "gui", "ground", "head"};

    private final String ns;

    public ModelConverter(String namespace) { this.ns = namespace; }

    private static final class Part {
        BbModel.Bone bone; double[] pivot; double[][] extra = Mat.identity(); BbModel.Cube soloCube;
        final List<BbModel.Cube> cubes = new ArrayList<>(); double k = 1; String suffix;
    }

    /** A display transform: rotation (deg, XYZ), translation (px), uniform scale. */
    private record Trs(double[] rot, double[] tr, double sc) { }

    public ConvertedModel convert(BbModel m) {
        List<Issue> issues = new ArrayList<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        String mpath = m.path;

        // textures
        JsonObject textures = new JsonObject();
        Map<String, Integer> texIndex = new HashMap<>();
        List<double[]> texRes = new ArrayList<>();
        int ti = 0;
        for (BbModel.Texture t : m.textures) {
            if (t.png() != null) files.put("assets/" + ns + "/textures/item/" + mpath + "/" + t.key() + ".png", t.png());
            textures.addProperty(String.valueOf(ti), ns + ":item/" + mpath + "/" + t.key());
            if (ti == 0) textures.addProperty("particle", ns + ":item/" + mpath + "/" + t.key());
            for (String id : t.ids()) if (!id.isEmpty()) texIndex.put(id, ti);
            texRes.add(new double[] {t.uvWidth(), t.uvHeight()});
            ti++;
        }
        if (ti == 0) {
            textures.addProperty("0", "minecraft:block/brown_wool");
            textures.addProperty("particle", "minecraft:block/brown_wool");
            texRes.add(new double[] {m.resW, m.resH});
        }

        // anchors
        double[] b = m.bounds();
        double[] anchorFixed = {(b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2};
        boolean javaFormat = m.format != null && m.format.toLowerCase(java.util.Locale.ROOT).startsWith("java");
        double[] anchorHand = javaFormat ? new double[] {8, 8, 8} : new double[] {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        double extent = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        double f = extent > 16 ? 16 / extent : 1;
        Map<String, Trs> ctx = new LinkedHashMap<>();
        boolean fromFile = false;
        for (String c : CONTEXTS) {
            Trs d = m.display != null ? readTrs(m.display.get(c), issues, m.name, c) : null;
            if (d != null) fromFile = true;
            ctx.put(c, d != null ? d : defaultTrs(c, f));
        }
        if (!fromFile && m.display == null) issues.add(Issue.warning(m.name, "no 'display' section in the file - hand/gui transforms use block-like defaults (open the model in Blockbench as Java Block/Item and set Display to tune them)"));

        // parts
        BbModel.Bone root = new BbModel.Bone(); root.name = "root"; root.uuid = "__root"; root.origin = new double[] {8, 8, 8};
        Map<BbModel.Bone, Part> byBone = new LinkedHashMap<>();
        List<Part> parts = new ArrayList<>();
        int solo = 0;
        for (BbModel.Cube c : m.cubes.values()) {
            BbModel.Bone bone = c.bone == null ? root : c.bone;
            if (!exactRotation(c)) {
                Part sp = new Part(); sp.bone = bone; sp.soloCube = c; sp.pivot = c.origin.clone();
                sp.extra = Mat.mul(Mat.mul(Mat.translate(c.origin[0], c.origin[1], c.origin[2]), Mat.rotZYX(c.rotation)), Mat.translate(-c.origin[0], -c.origin[1], -c.origin[2]));
                sp.cubes.add(c); sp.suffix = "c" + (solo++); parts.add(sp);
                continue;
            }
            Part part = byBone.get(bone);
            if (part == null) { part = new Part(); part.bone = bone; part.pivot = bone.origin.clone(); part.suffix = String.valueOf(byBone.size()); byBone.put(bone, part); parts.add(part); }
            part.cubes.add(c);
        }
        Map<BbModel.Bone, double[][]> cache = new HashMap<>();
        List<String> wrappers = new ArrayList<>();
        int clamped = 0, shrunk = 0;
        for (Part part : parts) {
            String geom = mpath + "__g" + part.suffix, wrap = mpath + "__p" + part.suffix;
            files.put("assets/" + ns + "/models/item/" + geom + ".json", partGeometry(part, textures, texIndex, texRes));
            if (part.k < 1) shrunk++;
            double[][] M = Mat.mul(world(part.bone, cache), part.extra);
            double[] Pw = Mat.apply(M, part.pivot);
            double[][] Rw = {{M[0][0], M[0][1], M[0][2]}, {M[1][0], M[1][1], M[1][2]}, {M[2][0], M[2][1], M[2][2]}};
            double[] eul = Mat.eulerXYZ(Rw);
            double sc = Math.min(4.0, 1.0 / part.k);
            JsonObject disp = new JsonObject();
            double[] trFixed = new double[3];
            for (int i = 0; i < 3; i++) { trFixed[i] = Pw[i] - anchorFixed[i]; if (Math.abs(trFixed[i]) > 80) { trFixed[i] = Math.max(-80, Math.min(80, trFixed[i])); clamped++; } }
            disp.add("fixed", transform(eul, trFixed, new double[] {sc, sc, sc}));
            double[] trHand = {Pw[0] - anchorHand[0], Pw[1] - anchorHand[1], Pw[2] - anchorHand[2]};
            for (var e : ctx.entrySet()) disp.add(e.getKey(), compose(e.getValue(), trHand, sc, Rw));
            JsonObject w = new JsonObject();
            w.addProperty("parent", ns + ":item/" + geom);
            w.add("display", disp);
            files.put("assets/" + ns + "/models/item/" + wrap + ".json", GSON.toJson(w).getBytes(StandardCharsets.UTF_8));
            wrappers.add("item/" + wrap);
        }
        if (clamped > 0) issues.add(Issue.warning(m.name, clamped + " part(s) have their pivot more than 80 px (5 blocks) from the model's bottom-centre - the client cannot place them that far; move the model closer to (0,0,0)"));
        if (shrunk > 0) issues.add(Issue.warning(m.name, shrunk + " part(s) are bigger than 48 px around their pivot and were scaled in the geometry (compensated in the wrapper); split very large groups"));
        if (solo > 0) issues.add(Issue.warning(m.name, solo + " cube(s) with a free rotation angle became their own exact part"));
        return new ConvertedModel(m.name, mpath, files, wrappers, m.cubes.size(), parts.size(), b, issues);
    }

    /** D o P : apply the part placement (rotation Rw, translation tr, scale sc) then the context transform D. */
    private static JsonObject compose(Trs d, double[] trPart, double scPart, double[][] Rp) {
        double[][] Rd = Mat.rotXYZ(d.rot());
        boolean partIdentity = true;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) if (Math.abs(Rp[i][j] - (i == j ? 1 : 0)) > 1e-6) partIdentity = false;
        double[] eul = partIdentity ? d.rot().clone() : Mat.eulerXYZ(Mat.mul3(Rd, Rp));
        double[] rt = {Rd[0][0]*trPart[0]+Rd[0][1]*trPart[1]+Rd[0][2]*trPart[2], Rd[1][0]*trPart[0]+Rd[1][1]*trPart[1]+Rd[1][2]*trPart[2], Rd[2][0]*trPart[0]+Rd[2][1]*trPart[1]+Rd[2][2]*trPart[2]};
        double[] tr = new double[3];
        for (int i = 0; i < 3; i++) tr[i] = Math.max(-80, Math.min(80, d.tr()[i] + d.sc() * rt[i]));
        double s = Math.max(-4, Math.min(4, d.sc() * scPart));
        return transform(eul, tr, new double[] {s, s, s});
    }

    private static Trs readTrs(JsonElement e, List<Issue> issues, String model, String context) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        double[] rot = o.has("rotation") ? BbModelParser.vec(o.get("rotation")) : new double[] {0, 0, 0};
        double[] tr = o.has("translation") ? BbModelParser.vec(o.get("translation")) : new double[] {0, 0, 0};
        double[] sc = o.has("scale") ? BbModelParser.vec(o.get("scale")) : new double[] {1, 1, 1};
        double s = (sc[0] + sc[1] + sc[2]) / 3;
        if (Math.abs(sc[0] - sc[1]) > 0.01 || Math.abs(sc[1] - sc[2]) > 0.01)
            issues.add(Issue.warning(model, "display." + context + " has a non-uniform scale " + java.util.Arrays.toString(sc) + " - averaged to " + String.format(java.util.Locale.ROOT, "%.3f", s)));
        return new Trs(rot, tr, s);
    }

    private static Trs defaultTrs(String c, double f) {
        return switch (c) {
            case "thirdperson_righthand", "thirdperson_lefthand" -> new Trs(new double[] {75, 45, 0}, new double[] {0, 2.5, 0}, 0.375 * f);
            case "firstperson_righthand" -> new Trs(new double[] {0, 45, 0}, new double[] {0, 0, 0}, 0.4 * f);
            case "firstperson_lefthand" -> new Trs(new double[] {0, 225, 0}, new double[] {0, 0, 0}, 0.4 * f);
            case "gui" -> new Trs(new double[] {30, 225, 0}, new double[] {0, 0, 0}, 0.625 * f);
            case "ground" -> new Trs(new double[] {0, 0, 0}, new double[] {0, 3, 0}, 0.25 * f);
            default -> new Trs(new double[] {0, 0, 0}, new double[] {0, 0, 0}, f);
        };
    }

    /** Can a Java element hold this cube's rotation exactly (one axis, multiple of 22.5, |a| <= 45)? */
    static boolean exactRotation(BbModel.Cube c) {
        if (c.rotation[0] == 0 && c.rotation[1] == 0 && c.rotation[2] == 0) return true;
        double[][] R = Mat.rot3(c.rotation);
        int[][] P = Mat.nearestPerm(R);
        double[] e = Mat.euler3(Mat.mul3(Mat.transpose3(P), R));
        int a = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(e[i]) > Math.abs(e[a])) a = i;
        for (int i = 0; i < 3; i++) if (i != a && Math.abs(e[i]) > 0.5) return false;
        double snapped = Math.round(e[a] / 22.5) * 22.5;
        return Math.abs(e[a] - snapped) < 0.5 && Math.abs(snapped) <= 45;
    }

    /** Exact rest-pose world matrix of a group: T(origin) * R(rotation) * T(-origin), chained through parents. */
    private static double[][] world(BbModel.Bone b, Map<BbModel.Bone, double[][]> cache) {
        if (b == null || "__root".equals(b.uuid)) return Mat.identity();
        double[][] w = cache.get(b);
        if (w != null) return w;
        double[][] local = Mat.mul(Mat.mul(Mat.translate(b.origin[0], b.origin[1], b.origin[2]), Mat.rotZYX(b.rotation)), Mat.translate(-b.origin[0], -b.origin[1], -b.origin[2]));
        w = b.parent == null ? local : Mat.mul(world(b.parent, cache), local);
        cache.put(b, w);
        return w;
    }

    private static double[] corner(BbModel.Cube c, int corner) {
        return new double[] {((corner & 1) == 0 ? c.from[0] - c.inflate : c.to[0] + c.inflate) - c.origin[0],
                             ((corner & 2) == 0 ? c.from[1] - c.inflate : c.to[1] + c.inflate) - c.origin[1],
                             ((corner & 4) == 0 ? c.from[2] - c.inflate : c.to[2] + c.inflate) - c.origin[2]};
    }

    private byte[] partGeometry(Part part, JsonObject textures, Map<String, Integer> texIndex, List<double[]> texRes) {
        double[] shift = {8 - part.pivot[0], 8 - part.pivot[1], 8 - part.pivot[2]};
        double far = 0;
        for (BbModel.Cube c : part.cubes) {
            int[][] Pe = part.soloCube == c ? Mat.identityPerm() : Mat.nearestPerm(Mat.rot3(c.rotation));
            for (int corner = 0; corner < 8; corner++) {
                double[] v = corner(c, corner);
                for (int i = 0; i < 3; i++) far = Math.max(far, Math.abs(Pe[i][0]*v[0]+Pe[i][1]*v[1]+Pe[i][2]*v[2] + c.origin[i] + shift[i] - 8));
            }
        }
        part.k = far > 23.9 ? 23.9 / far : 1.0;
        JsonArray elements = new JsonArray();
        for (BbModel.Cube c : part.cubes) {
            boolean solo = part.soloCube == c;
            double[][] Re = Mat.rot3(solo ? new double[] {0, 0, 0} : c.rotation);
            int[][] Pe = Mat.nearestPerm(Re);
            double[] eulE = Mat.euler3(Mat.mul3(Mat.transpose3(Pe), Re));
            int ca = 0;
            for (int i = 1; i < 3; i++) if (Math.abs(eulE[i]) > Math.abs(eulE[ca])) ca = i;
            double cs = Math.max(-45, Math.min(45, Math.round(eulE[ca] / 22.5) * 22.5));
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int corner = 0; corner < 8; corner++) {
                double[] v = corner(c, corner);
                double[] w = {Pe[0][0]*v[0]+Pe[0][1]*v[1]+Pe[0][2]*v[2] + c.origin[0], Pe[1][0]*v[0]+Pe[1][1]*v[1]+Pe[1][2]*v[2] + c.origin[1], Pe[2][0]*v[0]+Pe[2][1]*v[1]+Pe[2][2]*v[2] + c.origin[2]};
                for (int i = 0; i < 3; i++) { double q = 8 + (w[i] + shift[i] - 8) * part.k; lo[i] = Math.min(lo[i], q); hi[i] = Math.max(hi[i], q); }
            }
            JsonObject srcFaces = c.faces;
            boolean needBox = c.boxUv;
            if (!needBox) for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && !srcFaces.getAsJsonObject(f).has("uv")) needBox = true;
            if (needBox) {
                boolean anyUv = false;
                for (String f : FACE) if (srcFaces.has(f) && srcFaces.get(f).isJsonObject() && srcFaces.getAsJsonObject(f).has("uv")) anyUv = true;
                if (!anyUv) srcFaces = boxUvFaces(c, srcFaces);
            }
            JsonObject cfaces = remapFaces(srcFaces, Pe);
            for (int i = 0; i < 3; i++) { lo[i] = Math.max(-16, Math.min(32, lo[i])); hi[i] = Math.max(-16, Math.min(32, hi[i])); }
            JsonObject e = new JsonObject();
            e.add("from", arr(lo));
            e.add("to", arr(hi));
            if (cs != 0) {
                JsonObject rotation = new JsonObject();
                rotation.add("origin", arr(8 + (c.origin[0] + shift[0] - 8) * part.k, 8 + (c.origin[1] + shift[1] - 8) * part.k, 8 + (c.origin[2] + shift[2] - 8) * part.k));
                rotation.addProperty("axis", ca == 0 ? "x" : ca == 1 ? "y" : "z");
                rotation.addProperty("angle", cs);
                e.add("rotation", rotation);
            }
            JsonObject faces = new JsonObject();
            for (String f : FACE) {
                if (!cfaces.has(f) || !cfaces.get(f).isJsonObject()) continue;
                JsonObject bf = cfaces.getAsJsonObject(f);
                JsonElement tex = bf.get("texture");
                if (tex == null || tex.isJsonNull()) continue;
                Integer idx = tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isNumber() ? Integer.valueOf(tex.getAsInt()) : texIndex.get(tex.getAsString());
                if (idx == null || idx >= texRes.size()) idx = 0;
                double[] res = texRes.get(idx);
                double[] uv = bf.has("uv") && bf.get("uv").isJsonArray() ? BbModelParser.vec4(bf.get("uv")) : new double[] {0, 0, res[0], res[1]};
                JsonObject face = new JsonObject();
                face.add("uv", arr(uv[0] * 16 / res[0], uv[1] * 16 / res[1], uv[2] * 16 / res[0], uv[3] * 16 / res[1]));
                face.addProperty("texture", "#" + idx);
                if (bf.has("rotation")) { int r = ((int) BbModelParser.num(bf.get("rotation"), 0) % 360 + 360) % 360; if (r != 0) face.addProperty("rotation", r); }
                faces.add(f, face);
            }
            e.add("faces", faces);
            elements.add(e);
        }
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return GSON.toJson(model).getBytes(StandardCharsets.UTF_8);
    }

    private static int faceIndex(int[] dir) {
        for (int i = 0; i < 6; i++) if (DIR[i][0] == dir[0] && DIR[i][1] == dir[1] && DIR[i][2] == dir[2]) return i;
        return 0;
    }
    private static int[] applyPerm(int[][] P, int[] v) {
        return new int[] {P[0][0]*v[0]+P[0][1]*v[1]+P[0][2]*v[2], P[1][0]*v[0]+P[1][1]*v[1]+P[1][2]*v[2], P[2][0]*v[0]+P[2][1]*v[1]+P[2][2]*v[2]};
    }
    private static int dot(int[] a, int[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static int[] neg(int[] v) { return new int[] {-v[0], -v[1], -v[2]}; }

    /** Faces of a cube folded by a 90-degree permutation: renamed, with texture rotation/mirror remapped. */
    private static JsonObject remapFaces(JsonObject faces, int[][] P) {
        boolean identity = true;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) if (P[i][j] != (i == j ? 1 : 0)) identity = false;
        if (identity) return faces;
        JsonObject out = new JsonObject();
        for (int i = 0; i < 6; i++) {
            if (!faces.has(FACE[i]) || !faces.get(FACE[i]).isJsonObject()) continue;
            JsonObject f = faces.getAsJsonObject(FACE[i]).deepCopy();
            int ni = faceIndex(applyPerm(P, DIR[i]));
            int[] up = applyPerm(P, FUP[i]), right = applyPerm(P, FRIGHT[i]);
            int k;
            if (dot(up, FUP[ni]) > 0) k = 0; else if (dot(up, FRIGHT[ni]) > 0) k = 90; else if (dot(up, FUP[ni]) < 0) k = 180; else k = 270;
            int[] expectRight = switch (k) { case 0 -> FRIGHT[ni]; case 90 -> neg(FUP[ni]); case 180 -> neg(FRIGHT[ni]); default -> FUP[ni]; };
            boolean mirror = dot(right, expectRight) < 0;
            int rotation = (int) ((f.has("rotation") ? BbModelParser.num(f.get("rotation"), 0) : 0) + k) % 360;
            if (rotation != 0) f.addProperty("rotation", rotation); else f.remove("rotation");
            if (mirror && f.has("uv") && f.get("uv").isJsonArray()) {
                JsonArray uv = f.getAsJsonArray("uv");
                double u1 = BbModelParser.num(uv.get(0), 0), u2 = BbModelParser.num(uv.get(2), 0);
                JsonArray n = new JsonArray(); n.add(u2); n.add(BbModelParser.num(uv.get(1), 0)); n.add(u1); n.add(BbModelParser.num(uv.get(3), 0));
                f.add("uv", n);
            }
            out.add(FACE[ni], f);
        }
        return out;
    }

    /** Box-UV fallback (Bedrock/GeckoLib style models whose faces carry no uv): the classic Minecraft layout. */
    private static JsonObject boxUvFaces(BbModel.Cube c, JsonObject faces) {
        double dx = Math.abs(c.to[0] - c.from[0]), dy = Math.abs(c.to[1] - c.from[1]), dz = Math.abs(c.to[2] - c.from[2]);
        double u = c.uvOffset[0], v = c.uvOffset[1];
        Map<String, double[]> uv = new LinkedHashMap<>();
        uv.put("up",    new double[] {u + dz, v, u + dz + dx, v + dz});
        uv.put("down",  new double[] {u + dz + dx, v + dz, u + dz + 2 * dx, v});
        uv.put("west",  new double[] {u, v + dz, u + dz, v + dz + dy});
        uv.put("north", new double[] {u + dz, v + dz, u + dz + dx, v + dz + dy});
        uv.put("east",  new double[] {u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy});
        uv.put("south", new double[] {u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy});
        if (c.mirror) { double[] e = uv.get("east"), w = uv.get("west"); uv.put("east", w); uv.put("west", e); }
        JsonObject out = new JsonObject();
        for (String f : FACE) {
            JsonObject face = faces.has(f) && faces.get(f).isJsonObject() ? faces.getAsJsonObject(f).deepCopy() : new JsonObject();
            if (!face.has("uv") || !face.get("uv").isJsonArray()) {
                JsonArray a = new JsonArray(); for (double d : uv.get(f)) a.add(d);
                face.add("uv", a);
            }
            if (!face.has("texture")) face.addProperty("texture", 0);
            out.add(f, face);
        }
        return out;
    }

    static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(Math.round(d * 1000.0) / 1000.0);
        return a;
    }

    static JsonObject transform(double[] rot, double[] tr, double[] sc) {
        JsonObject o = new JsonObject();
        o.add("rotation", arr(rot));
        o.add("translation", arr(tr));
        o.add("scale", arr(sc));
        return o;
    }
}

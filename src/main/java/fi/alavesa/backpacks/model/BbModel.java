package fi.alavesa.backpacks.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The parts of a Blockbench file this plugin understands: cubes, groups (bones) with pivot + rotation,
 * and textures. Everything is in Blockbench pixels (16 px = 1 block, origin = the model's (0,0,0)).
 */
public final class BbModel {

    public static final class Bone {
        public String uuid, name;
        public double[] origin = {0, 0, 0}, rotation = {0, 0, 0};
        public Bone parent;
        public final List<Bone> children = new ArrayList<>();
    }

    public static final class Cube {
        public String uuid, name = "";
        public double[] from, to, origin = {0, 0, 0}, rotation = {0, 0, 0}, uvOffset = {0, 0};
        public double inflate = 0;
        public JsonObject faces;
        public Bone bone;
        public boolean mirror, boxUv;
        /** True when this "cube" is the bounding box of a MESH element (ParseOptions.MeshPolicy.BOUNDING_BOX). */
        public boolean meshBox;
    }

    public record Texture(String key, byte[] png, double uvWidth, double uvHeight, List<String> ids) { }

    public final String name;      // logical model name (the file name without .bbmodel), used in configs
    public final String path;      // resource-location safe path used in the pack
    public final double resW, resH;
    public final List<Texture> textures = new ArrayList<>();
    public final Map<String, Cube> cubes = new LinkedHashMap<>();
    public final Map<String, Bone> bones = new LinkedHashMap<>();
    public final List<String> animationNames = new ArrayList<>();
    public int meshCount;
    /** Blockbench model_format (java_block, free, bedrock, ...). */
    public String format = "";
    /** The file's own 'display' object (hand/gui/ground transforms), or null. */
    public JsonObject display;

    public BbModel(String name, double resW, double resH) {
        this.name = name;
        this.path = safePath(name);
        this.resW = resW;
        this.resH = resH;
    }

    /** Resource-location safe: ONE illegal character would make the client reject the whole items file. */
    public static String safePath(String name) {
        String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return p.isEmpty() ? "model" : p;
    }

    /** Axis-aligned extent of all cubes in px: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public double[] bounds() {
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (Cube c : cubes.values()) for (int i = 0; i < 3; i++) {
            mn[i] = Math.min(mn[i], Math.min(c.from[i], c.to[i]) - c.inflate);
            mx[i] = Math.max(mx[i], Math.max(c.from[i], c.to[i]) + c.inflate);
        }
        return new double[] {mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]};
    }
}

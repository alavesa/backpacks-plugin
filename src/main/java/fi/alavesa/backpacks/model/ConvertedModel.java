package fi.alavesa.backpacks.model;

import java.util.List;
import java.util.Map;

/**
 * One model turned into pack files. {@code files} maps pack-relative paths to bytes; {@code wrappers} are
 * the item-model paths (namespace-relative, e.g. "item/leather_backpack__p0") the items file composes.
 */
public record ConvertedModel(String name, String path, Map<String, byte[]> files, List<String> wrappers, int cubes,
                             int parts, double[] boundsPx, List<Issue> issues) { }

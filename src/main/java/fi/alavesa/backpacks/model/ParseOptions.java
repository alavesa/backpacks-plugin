package fi.alavesa.backpacks.model;

/** What to do with .bbmodel content the Java item-model format cannot represent. */
public record ParseOptions(MeshPolicy meshPolicy, MissingTexturePolicy missingTexturePolicy) {

    /** MESH elements (cylinders, bevels...): fail the model, or approximate each mesh by its bounding box. */
    public enum MeshPolicy { FAIL, BOUNDING_BOX }

    /** A texture that is neither embedded nor found next to the model. */
    public enum MissingTexturePolicy { FAIL, WARN }

    public static final ParseOptions STRICT = new ParseOptions(MeshPolicy.FAIL, MissingTexturePolicy.FAIL);

    public static ParseOptions of(String mesh, String missingTexture) {
        MeshPolicy m = "bounding-box".equalsIgnoreCase(mesh) || "box".equalsIgnoreCase(mesh) ? MeshPolicy.BOUNDING_BOX : MeshPolicy.FAIL;
        MissingTexturePolicy t = "warn".equalsIgnoreCase(missingTexture) ? MissingTexturePolicy.WARN : MissingTexturePolicy.FAIL;
        return new ParseOptions(m, t);
    }
}

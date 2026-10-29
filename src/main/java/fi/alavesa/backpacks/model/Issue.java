package fi.alavesa.backpacks.model;

/** One validation / conversion finding. ERROR means the model was NOT exported. */
public record Issue(Level level, String model, String message) {
    public enum Level { ERROR, WARNING }

    public static Issue error(String model, String message) { return new Issue(Level.ERROR, model, message); }
    public static Issue warning(String model, String message) { return new Issue(Level.WARNING, model, message); }

    public boolean isError() { return level == Level.ERROR; }

    @Override public String toString() { return (isError() ? "ERROR " : "warn  ") + model + ": " + message; }
}

package fi.alavesa.backpacks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ItemStack needs a live server, so only the key arithmetic is unit tested here. */
class InvUtilTest {
    @Test
    void placedKeysAreUniqueForNearbyBlocks() {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        for (int x = -3; x <= 3; x++) for (int y = -64; y <= 320; y += 32) for (int z = -3; z <= 3; z++)
            assertTrue(keys.add(fi.alavesa.backpacks.placed.PlacedBackpack.key(x, y, z)));
    }

    @Test
    void prettify() {
        assertEquals("Advanced Pickup", Settings.prettify("advanced_pickup"));
    }
}

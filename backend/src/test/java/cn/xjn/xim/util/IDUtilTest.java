package cn.xjn.xim.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class IDUtilTest {
    @Test
    public void generatesEightLowercaseHexCharacters() {
        for (int i = 0; i < 100; i++) {
            String id = IDUtil.genRandomId();
            assertNotNull(id);
            assertTrue(id, id.matches("[0-9a-f]{8}"));
        }
    }
}

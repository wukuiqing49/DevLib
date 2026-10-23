package com.beautysdk;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class JavaApiTest {
    @Test public void javaHostCanUseFrameAndLifecycleWithoutKotlinHelpers() {
        FrameInfo info = new FrameInfo(1, 1, 0L);
        RgbaFrame frame = new RgbaFrame(info, ByteBuffer.allocate(4), 4);
        try (BeautyProcessor processor = new BeautyProcessor()) {
            processor.start();
            VideoFrame output = processor.process(frame);
            assertSame(frame, output);
            assertEquals(1, output.getInfo().getWidth());
            processor.stop();
        }
    }
}

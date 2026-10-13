package cn.xjn.xim.server.limits;

import cn.xjn.xim.codec.Spliter;
import cn.xjn.xim.codec.PacketCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ResourceLimitsTest {
    @Test public void capacityIsReleasedWhenAConnectionCloses() {
        var guard = new ConnectionGuard(1);
        var first = new EmbeddedChannel(guard); var denied = new EmbeddedChannel(guard);
        assertTrue(first.isActive()); assertFalse(denied.isActive()); first.close();
        var next = new EmbeddedChannel(guard); assertTrue(next.isActive());
        first.finishAndReleaseAll(); denied.finishAndReleaseAll(); next.finishAndReleaseAll();
    }
    @Test public void burstRefillsAndExcessFramesAreReleased() {
        var clock = new AtomicLong(); var channel = new EmbeddedChannel(new RequestRateGuard(1, 2, clock::get));
        assertTrue(channel.writeInbound("one")); assertTrue(channel.writeInbound("two"));
        clock.set(1_000_000_000L); assertTrue(channel.writeInbound("three"));
        channel.readInbound(); channel.readInbound(); channel.readInbound();
        var buffer = Unpooled.buffer().writeByte(1); assertFalse(channel.writeInbound(buffer));
        assertFalse(channel.isActive()); assertEquals(0, buffer.refCnt()); channel.finishAndReleaseAll();
    }
    @Test public void rejectsHugeTcpLengthImmediatelyWithoutAllocatingItsBody() {
        var channel = new EmbeddedChannel(new Spliter());
        var header = Unpooled.buffer().writeInt(PacketCodec.MAGIC_NUMBER).writeByte(1).writeByte(1).writeByte(1).writeInt(Integer.MAX_VALUE);
        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(header));
        channel.finishAndReleaseAll();
    }
}

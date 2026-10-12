package cn.xjn.xim.handler;

import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.Test;

import static org.junit.Assert.*;

public class IMIdleStateHandlerTest extends ChannelTestSupport {
    @Test
    public void closesConnectionOnReadIdleTimeout() throws Exception {
        IMIdleStateHandler handler = new IMIdleStateHandler();
        EmbeddedChannel channel = channel(handler);
        assertEquals(15000, handler.getReaderIdleTimeInMillis());
        assertEquals(0, handler.getWriterIdleTimeInMillis());
        assertEquals(0, handler.getAllIdleTimeInMillis());
        handler.channelIdle(channel.pipeline().context(handler), IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        assertFalse(channel.isActive());
    }
}

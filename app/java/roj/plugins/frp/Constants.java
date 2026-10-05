package roj.plugins.frp;

import roj.concurrent.MPSCQueue;
import roj.io.IOUtil;
import roj.net.ChannelCtx;
import roj.net.ChannelHandler;
import roj.net.MyChannel;
import roj.net.handler.Compress;
import roj.text.logging.Level;
import roj.text.logging.Logger;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardSocketOptions;

/**
 * @author Roj233
 * @since 2021/12/21 13:18
 */
public abstract class Constants implements ChannelHandler {
	public static final String PROTOCOL_VERSION = "3.0.0-rc1 (2024-11-20)";
	public static final Level LOG_LEVEL = Level.ALL;

	public static final int PIPE_IDLE_MAX = 600000;

	public static final int SERVER_TIMEOUT = 90000;
	public static final int CLIENT_TIMEOUT = 30000;

	public static final int MAX_PORTS = 16;
	public static final int MAX_MOTD = 32767;
	public static final int MAX_PACKET = 8192;

	// logon reply
	public static final String PACKET_SET_MOTD = "frp:motd";
	public static final String PACKET_PORT_MAP = "frp:port";
	public static final String PACKET_SET_ROOM = "frp:room";
	/**
	 * vi_gbk new_motd;
	 */
	public static final int PHS_UPDATE_MOTD = 6;
	// endregion

	public static void registerShutdownHook(Closeable s) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> IOUtil.closeSilently(s)));
	}

	public static void initSocketPref(MyChannel client) throws IOException {
		client.setOption(StandardSocketOptions.TCP_NODELAY, true);
		client.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		client.setOption(StandardSocketOptions.IP_TOS, 0b10010000);
	}

	public static void compress(MyChannel ch) {
		ChannelCtx timeout = ch.handler("timeout");
		if (timeout != null)
			ch.addBefore(timeout, "compress", new Compress(99999, 200, 1024, -1));
	}

	protected final Logger LOGGER;
	public Constants() {
		LOGGER = Logger.getLogger(getClass().getSimpleName());
		LOGGER.setLevel(LOG_LEVEL);
	}

	private final MPSCQueue packets = new MPSCQueue();
	public final void writeAsync(DynByteBuf rb) {
		if (rb.readableBytes() > MAX_PACKET) throw new IllegalArgumentException("Packet too big ("+rb.readableBytes()+" > "+MAX_PACKET+")");
		packets.offer(rb);
	}
	@Override
	public void channelTick(ChannelCtx ctx) throws IOException {
		if (!packets.isEmpty()) {
			ByteList buf = IOUtil.getSharedByteBuf();
			while (true) {
				DynByteBuf take = packets.take(buf);
				if (take == null) break;
				ctx.channelWrite(take);
				buf.clear();
			}
		}
	}

	protected final void kickWithMessage(ChannelCtx ctx, int code) {
		ByteList b = IOUtil.getSharedByteBuf().put(code);
		try {
			ctx.channelWrite(b);
			ctx.channel().closeGracefully();
		} catch (IOException e) {
			LOGGER.warn("[{}]: ", e, this);
		}
	}
}
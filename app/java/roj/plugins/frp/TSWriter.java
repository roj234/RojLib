package roj.plugins.frp;

import roj.concurrent.MPSCQueue;
import roj.io.IOUtil;
import roj.net.ChannelCtx;
import roj.net.ChannelHandler;
import roj.net.MyChannel;
import roj.util.DynByteBuf;

import java.io.IOException;

/**
 * @author Roj234
 * @since 2024/11/21 4:02
 */
final class TSWriter implements ChannelHandler {
	private final MPSCQueue packetBuffer = new MPSCQueue(2);
	private volatile byte asyncClose;
	private MyChannel ctx;

	@Override public void handlerAdded(ChannelCtx ctx) {this.ctx = ctx.channel();}
	@Override public void handlerRemoved(ChannelCtx ctx) {packetBuffer.clear();}

	@Override
	public void channelTick(ChannelCtx ctx) throws Exception {
		var buf = IOUtil.getSharedByteBuf();
		while (packetBuffer.mayTake(buf)) {
			ctx.channelWrite(buf);
			buf.clear();
		}
		if (asyncClose != 0) {
			if (asyncClose == 3) {
				ctx.close();
			} else {
				ctx.channel().closeGracefully();
			}
		}
	}

	public void fireChannelWrite(DynByteBuf data) throws IOException {
		if (ctx.lock().tryLock()) {
			try {
				ctx.fireChannelWrite(data);
			} finally {
				ctx.lock().unlock();
			}
		} else {
			packetBuffer.offer(data);
		}
	}

	public void closeGracefully() throws IOException {
		if (ctx.lock().tryLock()) {
			try {
				ctx.closeGracefully();
			} finally {
				ctx.lock().unlock();
			}
		} else {
			asyncClose |= 1;
		}

	}

	public void close() throws IOException {
		if (ctx.lock().tryLock()) {
			try {
				ctx.close();
			} finally {
				ctx.lock().unlock();
			}
		} else {
			asyncClose = 3;
		}
	}
}

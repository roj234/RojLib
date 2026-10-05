package roj.net.handler;

import roj.io.IOUtil;
import roj.net.ChannelCtx;
import roj.net.ChannelHandler;
import roj.net.Net;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * @author Roj234
 * @since 2024/6/27 20:56
 */
public class Socks5Client implements ChannelHandler {
	private final byte[] username, password;
	final InetSocketAddress remote;
	int state;

	public Socks5Client(InetSocketAddress remote) {
		this.remote = remote;
		username = password = null;
	}
	public Socks5Client(InetSocketAddress remote, String username, String password) {
		this.username = username == null ? null : username.getBytes(StandardCharsets.UTF_8);
		this.password = password == null ? ArrayCache.BYTES : password.getBytes(StandardCharsets.UTF_8);
		this.remote = remote;
	}

	@Override
	public void channelOpened(ChannelCtx ctx) throws IOException {
		ByteList buf = IOUtil.getSharedByteBuf().put(0x05);
		if (username != null) buf.put(0x02).put(0x00).put(0x02);
		else buf.put(0x01).put(0x00);
		writeUp(ctx, buf);
	}

	@Override
	public void channelRead(ChannelCtx ctx, Object msg) throws IOException {
		var ib = (DynByteBuf) msg;
		int b = ib.readUnsignedByte();
		if (b != 5 || !ib.isReadable()) throw new IllegalStateException("Expect Socks5("+b+")");
		b = ib.readUnsignedByte();

		switch (state) {
			case 0 -> {
				if (b == 0xFF) {
					ctx.close();
				} else {
					switch (b) {
						default -> throw new IOException("不支持的登录方法 "+b);
						case 0 -> sendConnect(ctx);
						case 2 -> {
							ByteList buf = IOUtil.getSharedByteBuf().put(0x05);
							buf.put(username.length).put(username).put(password.length).put(password);
							writeUp(ctx, buf);
							state = 1;
						}
					}
				}
			}
			case 1 -> {
				if (b != 0) throw new IOException("登录失败");
				sendConnect(ctx);
			}
			case 2 -> {
				if (b != 0)
					throw new IOException(switch (b) {
						case 1 -> "general SOCKS server failure";
						case 2 -> "connection not allowed by ruleset";
						case 3 -> "Network unreachable";
						case 4 -> "Host unreachable";
						case 5 -> "Connection refused";
						case 6 -> "TTL expired";
						case 7 -> "Command not supported";
						case 8 -> "Address type not supported";
						default -> "reserved "+b;
					});
				ib.readUnsignedByte();//RSV
				b = ib.readUnsignedByte();//ATYPE
				var addr = switch (b) {
					case 0 -> null;
					case 1 -> InetAddress.getByAddress(ib.readBytes(4));
					case 3 -> InetAddress.getByName(ib.readAscii(ib.readUnsignedByte()));
					case 4 -> InetAddress.getByAddress(ib.readBytes(16));
					default -> throw new IllegalStateException("未知的地址类型:"+b);
				};
				var soca = new InetSocketAddress(addr, ib.readChar());
				//貌似不对？
				//此处所提供的BND.ADDR通常情况下不同于客户端连接到socks5代理服务器的IP地址，因为有可能代理服务器是一个集群，当然我这里只是一个服务器，所以返回的和代理的IP一样，
				//BND.PORT表示服务器分配的连接到目标主机的端口号，即代理服务器接下来会使用BND.PORT这个端口与目标主机进行TCP通信。
				if (handlerBinderAddr(soca)) {
					//TCP连接已建立
					ctx.removeSelf();
				}

				ctx.channelOpened();
				state = 3;
			}
		}
	}

	int requestType() {return 1;}
	void writeUp(ChannelCtx ctx, DynByteBuf buf) throws IOException {ctx.channelWrite(buf);}
	boolean handlerBinderAddr(InetSocketAddress soca) {return true;}

	private void sendConnect(ChannelCtx ctx) throws IOException {
		ByteList buf = IOUtil.getSharedByteBuf().put(0x05);
		//0x01表示CONNECT请求
		//0x02表示BIND请求 (反向连接？)
		//0x03表示UDP转发
		buf.put(requestType());
		buf.put(0x00);//RESERVED
		String hostName = Net.getOriginalHostName(remote.getAddress());
		if (hostName != null) {
			buf.put(0x03).put(hostName.length()).putAscii(hostName);
		} else {
			byte[] data = remote.getAddress().getAddress();
			if (data.length == 4) {
				// ipv4
				buf.put(0x01).put(data);
			} else {
				// ipv6
				buf.put(0x04).put(data);
			}
		}
		buf.putShort(remote.getPort());
		ctx.channelWrite(buf);
		state = 2;
	}
}
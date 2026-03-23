package roj.net;

import org.jetbrains.annotations.Nullable;
import roj.RojLib;
import roj.ci.annotation.Public;
import roj.collect.ArrayList;
import roj.collect.HashSet;
import roj.net.handler.Socks5Client;
import roj.reflect.Bypass;
import roj.text.CharList;
import roj.text.FastNumberParser;
import roj.text.TextUtil;
import roj.text.URICoder;
import roj.text.logging.Logger;
import roj.util.NativeException;
import roj.util.OS;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.*;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Enumeration;

/**
 * @author Roj234
 * @since 2020/10/30 23:05
 */
public final class Net {
	public static final Logger LOGGER = Logger.getLogger("Net");

	private static ArrayList<NetworkInterface> networkInterfaces;
	private static HashSet<InetAddress> endpoints;
	private static boolean isIPv6;

	public static void refreshEndpoints() {
		ArrayList<NetworkInterface> interfaces = new ArrayList<>();
		HashSet<InetAddress> addresses = new HashSet<>();
		boolean val = false;

		try {
			Enumeration<NetworkInterface> itr = NetworkInterface.getNetworkInterfaces();
			while (itr.hasMoreElements()) {
				NetworkInterface itf = itr.nextElement();
				if (/*itf.getParent() == null && */itf.isUp() && !itf.isLoopback()) {
					interfaces.add(itf);
					Enumeration<InetAddress> itr1 = itf.getInetAddresses();
					while (itr1.hasMoreElements()) {
						InetAddress endpoint = itr1.nextElement();
						if (endpoint instanceof Inet6Address) {
							val = true;
						}
						addresses.add(endpoint);
					}
				}
			}
		} catch (IOException ignored) {}

		networkInterfaces = interfaces;
		endpoints = addresses;
		isIPv6 = val;
	}

	public static boolean isIPv6() { if (networkInterfaces == null) refreshEndpoints(); return isIPv6; }
	public static HashSet<InetAddress> getNetworkEndpoints() { if (networkInterfaces == null) refreshEndpoints(); return endpoints; }
	public static ArrayList<NetworkInterface> getNetworkInterfaces() { if (networkInterfaces == null) refreshEndpoints(); return networkInterfaces; }

	// region IP String-Bytes convert
	public static byte[] ip2bytes(CharSequence ip) { return TextUtil.lastIndexOf(ip, '.') != -1 ? v4ipBytes(ip) : v6ipBytes(ip); }
	public static byte[] v4ipBytes(CharSequence ip) {
		byte[] addr = new byte[4];
		int pos = 0;

		CharList num = new CharList(4);
		for (int i = 0; i < ip.length(); i++) {
			char c = ip.charAt(i);
			if (c == '.') {
				addr[pos++] = (byte) FastNumberParser.parseInt(num);
				if (pos == 4) throw new IllegalArgumentException("invalid ipv4: "+ip);
				num.clear();
			} else {
				num.append(c);
			}
		}

		if (num.length() == 0 || pos != 3) throw new IllegalArgumentException("invalid ipv4: "+ip);
		addr[3] = (byte) FastNumberParser.parseInt(num);
		return addr;
	}
	public static byte[] v6ipBytes(CharSequence ip) {
		// subnet mask
		int len = TextUtil.indexOf(ip, '%');
		if (len < 0) len = ip.length();

		byte[] addr = new byte[16];

		int j = 0, colon = -1;
		CharList num = new CharList(5);
		for (int i = 0; i < len; i++) {
			char c = ip.charAt(i);
			if (c == ':') {
				if (num.length() == 0) {
					if (i == 0) throw new IllegalArgumentException("Not support :: at first");
					if (ip.charAt(i - 1) != ':') throw new IllegalArgumentException("Single ':': "+ip);
					if (colon >= 0) throw new IllegalArgumentException("More than one ::");
					colon = j;
					continue;
				}
				int st = FastNumberParser.parseInt(num, FastNumberParser.NUM_HEX);
				addr[j++] = (byte) (st >> 8);
				addr[j++] = (byte) st;

				if (j == 16) throw new IllegalArgumentException("Address overflow: "+ip);
				num.clear();
			} else if (TextUtil.HEX.contains(c)) {
				num.append(c);
			} else {
				throw new IllegalArgumentException("Invalid character at "+i+": "+ip);
			}
		}

		if ((colon == -1 && (num.length() == 0 || j != 14)) || j > 14) throw new IllegalArgumentException("Address overflow: " + ip);
		int st;
		st = num.length() == 0 ? 0 : FastNumberParser.parseInt(num, FastNumberParser.NUM_HEX);
		addr[j++] = (byte) (st >> 8);
		addr[j] = (byte) st;

		if (colon >= 0) {
			len = j - colon + 1;
			for (int i = 1; i <= len; i++) {
				addr[16 - i] = addr[j - i + 1];
				addr[j - i + 1] = 0;
			}
		}

		return addr;
	}

	public static String bytes2ip(byte[] addr) {
		try {
			return InetAddress.getByAddress(addr).getHostAddress();
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("address length neither v4 nor v6");
		}
	}
	// endregion
	// region utils for reading string a:b port
	public static InetSocketAddress parseListeningAddress(String s) { return parseAddress(s, null); }
	@Deprecated
	public static InetSocketAddress parseConnectAddress(String s) { return parseAddress(s, InetAddress.getLoopbackAddress()); }
	public static InetSocketAddress parseAddress(String s, InetAddress defaultAddr) {
		if (s == null || s.isEmpty()) throw new IllegalArgumentException("null port");

		int pos = s.lastIndexOf(':');

		InetAddress host;
		try {
			host = pos < 0 ? defaultAddr : InetAddress.getByName(s.substring(0, pos));
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("unknown host");
		}

		InetSocketAddress addr;
		try {
			addr = new InetSocketAddress(host, Integer.parseInt(s.substring(pos+1)));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("illegal port");
		}
		return addr;
	}
	public static InetSocketAddress parseAddress(String hostStr, int defaultPort) {
		if (hostStr == null || hostStr.isEmpty()) throw new IllegalArgumentException("null port");
		int pos = hostStr.lastIndexOf(':');
		return new InetSocketAddress(pos < 0 ? hostStr : hostStr.substring(0, pos), pos < 0 ? defaultPort : Integer.parseInt(hostStr.substring(pos+1)));
	}
	public static InetSocketAddress parseUnresolvedAddress(String hostStr, int defaultPort) {
		if (hostStr == null || hostStr.isEmpty()) throw new IllegalArgumentException("null port");
		int pos = hostStr.lastIndexOf(':');
		return InetSocketAddress.createUnresolved(pos < 0 ? hostStr : hostStr.substring(0, pos), pos < 0 ? defaultPort : Integer.parseInt(hostStr.substring(pos+1)));
	}
	// endregion

	public static URI socks5(InetSocketAddress server) {return URI.create("socks5://"+server);}
	public static URI socks5(InetSocketAddress server, String username, String password) {return URI.create("socks5://"+URICoder.encodeURIComponent(username)+":"+URICoder.encodeURIComponent(password)+"@"+server);}

	public static InetSocketAddress applyProxy(@Nullable URI proxy, InetSocketAddress connectAddress, MyChannel ch) throws IOException {
		if (proxy == null) return connectAddress;
		switch (proxy.getScheme()) {
			default -> throw new IllegalArgumentException("Not know "+proxy+" proxy");
			case "socks5" -> {
				String info = proxy.getRawUserInfo();
				String user = null, pass = null;
				if (info != null) {
					int i = info.indexOf(':');
					if (i < 0) {
						user = URICoder.decodeURI(info);
					} else {
						user = URICoder.decodeURI(info.substring(0, i));
						pass = URICoder.decodeURI(info.substring(i+1));
					}
				}
				ch.addFirst("@proxy", new Socks5Client(connectAddress, user, pass));
				return new InetSocketAddress(InetAddress.getByName(proxy.getHost()), proxy.getPort());
			}
		}
	}

	@Nullable
	public static String getOriginalHostName(InetAddress address) {
		return H.impl.getOriginalHostName(H.impl.getHolder(address));
	}

	public static String toString(SocketAddress address) {
		if (!(address instanceof InetSocketAddress n)) return String.valueOf(address);
		return n.getAddress().getHostAddress()+":"+n.getPort();
	}

	public static void setHostCachePolicy(boolean negative, int seconds) {
		if (seconds < 0) seconds = -1;
		if (negative) {
			H.impl.setNegCachePolicy(seconds);
			H.impl.setNegCacheSet(true);
		} else {
			H.impl.setPosCachePolicy(seconds);
			H.impl.setPosCacheSet(true);
		}
	}

	@Public
	interface H {
		H impl = getInstance();
		private static H getInstance() {
			Bypass<H> b = Bypass.builder(H.class).unchecked();

			try {
				b.access(FileDescriptor.class, "fd", "fdVal", "fdFd")
						.delegate(FileDescriptor.class, "closeAll", "fdClose");
			} catch (Throwable e) {
				LOGGER.error("无法加载模块 {}", e, "GetIntFD");
			}

			try {
				b.access(Class.forName("sun.nio.ch.SocketChannelImpl"), "fd", "tcpFD", null)
						.access(Class.forName("sun.nio.ch.ServerSocketChannelImpl"), "fd", "tcpsFD", null)
						.access(Class.forName("sun.nio.ch.DatagramChannelImpl"), "fd", "udpFD", null);
			} catch (Throwable e) {
				LOGGER.error("无法加载模块 {}", e, "GetChannelFD");
			}

			try {
				Class<?> policy = Class.forName("sun.net.InetAddressCachePolicy");
				String[] fieldName = new String[] {"cachePolicy", "negativeCachePolicy", "propertySet", "propertyNegativeSet"};
				try {
					policy.getDeclaredField("propertySet");
				} catch (NoSuchFieldException e) {
					fieldName[2] = "set";
					fieldName[3] = "negativeSet";
				}
				b.access(policy, fieldName, null, new String[] {"setPosCachePolicy", "setNegCachePolicy", "setPosCacheSet", "setNegCacheSet"});
			} catch (Throwable e) {
				LOGGER.error("无法加载模块 {}", e, "AddressCache");
			}

			try {
				Class<?> pl = Class.forName("java.net.InetAddress$InetAddressHolder");
				b.access(pl, "originalHostName", "getOriginalHostName", null);
				b.access(InetAddress.class, "holder", "getHolder", null);
			} catch (Throwable e) {
				LOGGER.error("无法加载模块 {}", e, "getOriginalHostName");
			}

			try {
				b.delegate(AbstractSelectableChannel.class, "removeKey");
			} catch (Throwable e) {
				LOGGER.error("无法加载模块 {}", e, "UDP Util");
			}

			return b.build();
		}

		//region FileDescriptor
		void fdFd(FileDescriptor fd, int fdVal);
		int fdVal(FileDescriptor fd);
		void fdClose(FileDescriptor fd, Closeable releaser) throws IOException;

		FileDescriptor udpFD(DatagramChannel ch);
		FileDescriptor tcpFD(SocketChannel ch);
		FileDescriptor tcpsFD(ServerSocketChannel ch);
		//endregion
		Object getHolder(InetAddress address);
		String getOriginalHostName(Object o);
		//region HostResolverCache
		default void setPosCachePolicy(int seconds) {
			throw new UnsupportedOperationException();
		}
		default void setNegCachePolicy(int seconds) {
			throw new UnsupportedOperationException();
		}

		void setPosCacheSet(boolean set);
		void setNegCacheSet(boolean set);
		//endregion

		void removeKey(AbstractSelectableChannel ch, SelectionKey key);
	}

	// java为了所谓的安全性禁止在windows上重用端口，这是解决它的办法
	public static void setReusePort(DatagramChannel so, boolean on) throws IOException {
		if (OS.CURRENT != OS.WINDOWS) so.setOption(StandardSocketOptions.SO_REUSEPORT, on);
		else SetSocketOpt(H.impl.udpFD(so), on);
	}
	public static void setReusePort(SocketChannel so, boolean on) throws IOException {
		if (OS.CURRENT != OS.WINDOWS) so.setOption(StandardSocketOptions.SO_REUSEPORT, on);
		else SetSocketOpt(H.impl.tcpFD(so), on);
	}
	public static void setReusePort(ServerSocketChannel so, boolean on) throws IOException {
		if (OS.CURRENT != OS.WINDOWS) so.setOption(StandardSocketOptions.SO_REUSEPORT, on);
		else SetSocketOpt(H.impl.tcpsFD(so), on);
	}

	private static void SetSocketOpt(FileDescriptor fd, boolean enabled) throws IOException {
		if (!RojLib.hasNative(RojLib.WIN32)) throw new NativeException("not available");
		int error = SetSocketOpt(H.impl.fdVal(fd), enabled);
		if (error != 0) {
			switch (error) {
				case 10036: throw new IOException("WSAEINPROGRESS");
				case 10038: throw new IOException("WSAENOTSOCK");
				case 10042: throw new IOException("WSAENOPROTOOPT");
				case 10050: throw new IOException("WSAENETDOWN");
				default: throw new IOException("native setsockopt returns "+error);
			}
		}
	}
	private static native int SetSocketOpt(int fd, boolean enable);
}
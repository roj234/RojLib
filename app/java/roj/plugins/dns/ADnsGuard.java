package roj.plugins.dns;

import roj.collect.ArrayList;
import roj.collect.TrieTreeSet;
import roj.config.node.ListValue;
import roj.config.node.MapValue;
import roj.http.server.Content;
import roj.http.server.Request;
import roj.http.server.Response;
import roj.http.server.auto.GET;
import roj.http.server.auto.OKRouter;
import roj.http.server.auto.POST;
import roj.io.IOUtil;
import roj.net.Net;
import roj.plugin.Plugin;
import roj.text.*;
import roj.util.DynByteBuf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;

import static roj.plugins.dns.DnsQuestion.*;

/**
 * @author solo6975
 * @since 2022/1/1 19:20
 */
public class ADnsGuard extends Plugin {
	private DnsServer server;

	@Override
	protected void onEnable() throws Exception {
		MapValue cfg = getConfig();

		var local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 53);
		System.out.println("Dns listening on "+local);

		var dns = new DnsServer(cfg, local);
		this.server = dns;

		ListValue list = cfg.getOrCreateList("hosts");
		for (int i = 0; i < list.size(); i++) {
			dns.loadHosts(new File(list.get(i).asString()));
		}

		TrieTreeSet tree = new TrieTreeSet();
		list = cfg.getOrCreateList("adblock");
		for (int i = 0; i < list.size(); i++) {
			MapValue map = list.get(i).asMap();
			File file = new File(map.getString("file"));
			if (System.currentTimeMillis() - file.lastModified() > map.getLong("update")) {
				String url = map.getString("url");
				if (!url.isEmpty()) {
					System.out.println("Update " + file + " via " + url);
					HttpURLConnection hc = (HttpURLConnection) new URL(url).openConnection();
					InputStream in = hc.getInputStream();
					try (FileOutputStream out = new FileOutputStream(file)) {
						int read;
						do {
							byte[] buf = new byte[4096];
							read = in.read(buf);
							out.write(buf, 0, read);
						} while (read > 0);
					} finally {
						hc.disconnect();
					}
				}
			}

			CharList tmp = new CharList();
			try (TextReader tr = TextReader.auto(file)) {
				for (String ln : tr) {
					if (ln.isEmpty() || ln.startsWith("!")) continue;

					tmp.clear();
					tmp.append(ln).replace("@@", "").replace("|", "").replace("^", "");
					tree.add(tmp.toString());
				}
			}
		}
		if (!tree.isEmpty()) {
			dns.blocked = s -> {
				int i = 0;
				do {
					// e.qq.com does not matches image.qq.com
					// but matches abc.e.qq.com
					if (tree.contains(s, i, s.length())) {
						if (i != 0) System.out.println("Matched partial at " + i + " of " + s);
						return true;
					}
					i = s.indexOf('.', i) + 1;
				} while (i > 0);
				return false;
			};
		}

		if (cfg.getBool("manage")) registerRoute("dns/", new OKRouter().register(dns));
		//Record.ttlUpdateMultiplier = cfg.getFloat("TTLFactor", 1);

		System.out.println("Welcome, to a cleaner world, "+System.getProperty("user.name", "user")+"!");
		dns.launch();
	}

	@GET("/")
	public Content index(String msg) throws Exception {
		var sb = new StringBuilder().append("<head><meta charset='UTF-8' /><title>ADnsGuard 2.0</title></head><h1>Welcome! <br> ADnsGuard - 基于DNS的广告屏蔽器</h1>");

		if (msg != null && !msg.isEmpty()) {
			sb.append("<div style='background: 0xAA8888; margin: 16px; padding: 16px; border: #000 1px dashed; font-size: 24px; text-align: center;'>")
			  .append(URICoder.decodeURI(msg))
			  .append("</div>");
		}

		sb.append("欢迎您,")
		  .append(System.getProperty("user.name", "用户"))
		  .append("! <br/><a href='stat' style='color:red;'>查看缓存的解析</a><br/><h2> 设置或者删除DNS解析: </h2>")
		  .append("<form action='set' method='post' >Url: <input type='text' name='url' /><br/>" +
			  "Type: <input type='number' name='type' /><br/>" +
			  "Content: <input type='text' name='cnt' />" +
			  "<input type='submit' value='提交' /></form>")
		  .append("<pre>Type: 1 删除, \nA(IPV4): " + Q_A + ", \nAAAA(IPV6): " + Q_AAAA + " \nCNAME: " + Q_CNAME + ", \n其他看rfc" + "</pre>")
		  .append("<h2 style='color:#eecc44;margin: 10px auto;'>Powered by ImpLib/DPS</h2>Memory: ")
		  .append(TextUtil.scaledNumber(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));

		return Content.html(sb);
	}

	@GET
	public String stat() {return /*DebugTool.inspect(server.answerCache.entrySet())*/"";}

	@POST
	public void set(Request req, Response rh, String url, String type, String cnt) {
		String msg = null;
		if (url == null || type == null || cnt == null) {
			msg = "缺field";
		} else {
			DnsResourceKey key = new DnsResourceKey(url);

			if (type.equals("-1")) {
				msg = (server.answerCache.remove(key) == null) ? "不存在" : "已清除";
			} else {
				short qType = (short) FastNumberParser.parseInt(type);
				byte[] data = null;
				if (qType == Q_A || qType == Q_AAAA) {
					data = Net.ip2bytes(cnt);
				} else {
					switch (qType) {
						case Q_CNAME, Q_MB, Q_MD, Q_MF, Q_MG, Q_MR, Q_NS, Q_PTR:
							DynByteBuf w = IOUtil.getSharedByteBuf();
							encodeDomain(w, cnt);
							data = w.toByteArray();
							break;
						default:
							msg = "暂不支持" + DnsAnswer.getTypeName(qType);
					}
				}
				var e = new DnsAnswer(qType, C_INTERNET, data);

				if (msg == null) {
					ArrayList<DnsAnswer> value = new ArrayList<>();
					value.add(e);
					server.answerCache.put(key, value);
					msg = "操作完成";
				}
			}
		}

		rh.code(302).setHeader("Location", "./?msg="+ URICoder.encodeURI(msg));
	}
}
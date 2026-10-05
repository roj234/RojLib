package roj.privileged.service;

import roj.collect.ArrayList;
import roj.concurrent.Timer;
import roj.concurrent.TimerTask;
import roj.io.IOUtil;
import roj.net.Net;
import roj.text.logging.Logger;
import roj.util.FastFailException;
import roj.util.OS;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Roj234
 * @since 2025/10/11 22:13
 */
final class FirewallManagerImpl {
	private static final Logger LOGGER = Logger.getLogger(FirewallManagerImpl.class.getName());

	private final ConcurrentHashMap<String, TimerTask> rules = new ConcurrentHashMap<>();

	public boolean addFirewallRule(String source, int port, int timeoutSeconds) {
		String ruleId = makeRuleId(source, port);
		if (rules.containsKey(ruleId)) return false;

		try {
			if (switch (OS.CURRENT) {
				case WINDOWS -> addWindowsRule(source, port);
				case UNIX, OSX -> manageLinuxRule(source, port, true);
				default -> throw new FastFailException("不支持的操作系统: "+OS.CURRENT);
			}) {
				rules.put(ruleId, Timer.getDefault().delay(() -> removeFirewallRule(source, port), timeoutSeconds*1000L));
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("添加防火墙规则失败: "+e.getMessage());
		}
		return false;
	}

	public boolean removeFirewallRule(String source, int port) {
		String ruleId = makeRuleId(source, port);

		var task = rules.get(ruleId);
		if (task != null) task.cancel();
		else return false;

		try {
			return switch (OS.CURRENT) {
				case WINDOWS -> removeWindowsRule(source, port);
				case UNIX, OSX -> manageLinuxRule(source, port, false);
				default -> throw new FastFailException("不支持的操作系统: "+OS.CURRENT);
			};
		} catch (Exception e) {
			LOGGER.error("移除防火墙规则失败: "+e.getMessage());
			return false;
		} finally {
			rules.remove(ruleId);
		}
	}

	/**
	 * 清理所有定时器和规则
	 */
	public void shutdown() {
		rules.values().forEach(task -> {
			task.cancel();
			if (!task.isExpired()) {
				task.task().run();
			}
		});
		rules.clear();
	}

	private static String generateRuleName(String source, int port) {return "IL_Block_"+IOUtil.escapeFileName(source)+"_"+(port > 0 ? port : "ALL");}

	private boolean addWindowsRule(String source, int port) throws IOException {
		String ruleName = generateRuleName(source, port);
		String direction = "in";
		String action = "block";
		String protocol = port > 0 ? "tcp" : "any";

		var command = ArrayList.asModifiableList(
				"netsh", "advfirewall", "firewall", "add", "rule",
				"name=" + ruleName,
				"dir=" + direction,
				"action=" + action,
				"protocol=" + protocol,
				"remoteip=" + source
		);

		if (port > 0) command.add("localport="+port);

		return executeProcess(command);
	}
	private static boolean removeWindowsRule(String source, int port) throws IOException {
		String ruleName = generateRuleName(source, port);
		var command = ArrayList.asModifiableList(
				"netsh", "advfirewall", "firewall", "delete", "rule",
				"name=" + ruleName
		);
		return executeProcess(command);
	}

	private static boolean manageLinuxRule(String source, int port, boolean add) throws IOException {
		var command = ArrayList.asModifiableList(
				isIPv6(source) ? "ip6tables" : "iptables",
				add ? "-A" : "-D", "INPUT",
				"-s", source,
				"-j", "DROP"
		);

		if (port > 0) {
			command.addAll(
					"-p", "tcp",
					"--dport", String.valueOf(port)
			);
		}
		return executeProcess(command);
	}

	private static boolean executeProcess(List<String> command) throws IOException {
		var pb = new ProcessBuilder(command);
		Process process = pb.start();
		try {
			return process.waitFor() == 0;
		} catch (InterruptedException e) {
			throw IOUtil.rethrowAsIOException(e);
		}
	}

	private static boolean isIPv6(String source) {return source.indexOf('.') < 0;}

	private static String makeRuleId(String source, int port) {
		int cidrIndex = source.lastIndexOf('/');
		if (cidrIndex < 0) Net.ip2bytes(source);
		else {
			int ip = Net.ip2bytes(source.substring(0, cidrIndex)).length;
			int r = Integer.parseInt(source.substring(cidrIndex+1));
			if (r >= ip*8) throw new IllegalArgumentException("Illegal ip CIDR: "+source);
		}

		if ((char)port != port) throw new IllegalArgumentException("Illegal port: "+port);
		return source+":"+port;
	}
}
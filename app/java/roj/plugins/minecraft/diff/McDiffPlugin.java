package roj.plugins.minecraft.diff;

import roj.plugin.Plugin;
import roj.plugin.SimplePlugin;
import roj.ui.Argument;
import roj.ui.TUI;
import roj.ui.Tty;

import java.io.File;

import static roj.ui.CommandNode.argument;
import static roj.ui.CommandNode.literal;

/**
 * @author Roj234
 * @since 2024/7/16 4:53
 */
@SimplePlugin(id = "mcdiff", desc = """
	[存档压缩&备份]
	压缩regions,entities和poi: chunktrim 世界文件夹
	创建压缩的备份: chunktrim backup 世界文件夹 备份文件夹
	""",
	version = "3.0")
public class McDiffPlugin extends Plugin {
	@Override
	protected void onEnable() throws Exception {
		File file1 = new File(getDataFolder(), "ignore.json");
		ChunkTrim.init(file1);

		registerCommand(literal("chunktrim").then(argument("chunk", Argument.folder()).executes(ctx -> {
			char c = TUI.key("yn", "您正原位替换世界，确认吗？");
			if (c != 'y') return;
			System.out.println("您确认了操作.");
			ChunkTrim.createInline(ctx.argument("chunk", File.class));
		}).then(argument("backup", Argument.fileOptional(false)).executes(ctx -> {
			var file = ctx.argument("chunk", File.class);
			var backup = ctx.argument("backup", File.class);
			if (!backup.isDirectory() && !backup.mkdirs()) {
				Tty.error("无法创建保存目录");
				return;
			}
			ChunkTrim.createBackup(file, backup);
		}))));
	}
}
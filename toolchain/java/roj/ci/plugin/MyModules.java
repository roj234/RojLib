package roj.ci.plugin;

import roj.ci.BuildContext;
import roj.collect.ArrayList;

import java.io.File;
import java.util.List;

/**
 * @author Roj234
 * @since 2026/02/07 09:03
 */
public class MyModules implements Plugin {
	@Override
	public String name() {
		return "'自研'模块标准, 毕竟javac是个傻逼";
	}

	@Override
	public boolean preProcess(ArrayList<String> options, List<File> sources, BuildContext ctx) {
		for (int i = 0; i < sources.size(); i++) {
			File source = sources.get(i);
			if (source.getName().equals("module-info.java")) {

				/*try {
					var cu = new LavaCompileUnit("module-info.java", IOUtil.readString(source));
					cu.S1parseStruct();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}*/

				sources.remove(i);
				break;
			}
		}
		return false;
	}

	@Override
	public void postProcess(BuildContext ctx) {

	}
}

package roj.ci.plugin;

import roj.archive.zip.ZipEditor;
import roj.archive.zip.ZipEntry;
import roj.asm.ClassNode;
import roj.asm.Opcodes;
import roj.asm.insn.CodeWriter;
import roj.asmx.Context;
import roj.ci.BuildContext;
import roj.collect.HashSet;

/**
 * @author Roj234
 * @since 2025/10/14 23:01
 */
public class MyPackageList implements Plugin {
	@Override
	public void process(BuildContext ctx) {
		if (ctx.classesHaveChanged() && "true".equals(ctx.project.getVariables().get("myPackageList"))) {
			var annotatedClass = ctx.getAnnotatedClasses("roj/annotation/SpecialMethod1");
			if (annotatedClass.isEmpty()) return;

			var packages = new HashSet<String>();
			for (Context context : ctx.getChangedClasses()) {
				var name = context.getFileName();
				int endIndex = name.lastIndexOf('/');
				packages.add(name.substring(0, endIndex < 0 ? name.length() : endIndex).replace('/', '.'));
			}

			try {
				ZipEditor archive = ctx.artifact().getArchiveIfPresent();
				if (archive != null) {
					for (ZipEntry entry : archive.entries()) {
						String name = entry.getName();
						if (name.startsWith("META-INF/")) continue;
						if (!name.endsWith(".class")) continue;
						packages.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'));
					}
				} else {
					System.out.println("No ZipArchive present");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

			for (Context klass : annotatedClass) {
				ClassNode node = klass.getData();

				CodeWriter cw = node.methods.get(node.getMethod("getPackages")).overwrite(node.cp);
				cw.visitSize(2, 1);

				cw.newObject("java/util/HashSet");
				cw.insn(Opcodes.ASTORE_0);

				for (String aPackage : packages) {
					cw.insn(Opcodes.ALOAD_0);
					cw.ldc(aPackage);
					cw.invokeV("java/util/HashSet", "add", "(Ljava/lang/Object;)Z");
					cw.insn(Opcodes.POP);
				}

				cw.insn(Opcodes.ALOAD_0);
				cw.insn(Opcodes.ARETURN);

				cw.finish();
			}
		}
	}
}

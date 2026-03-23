package roj.compiler;

import roj.text.ParseException;

/**
 * @author Roj234
 * @since 2026/05/09 22:16
 */
public class VirtualCompileUnit extends CompileUnit {
	public VirtualCompileUnit(String name, String code) {super(name, code);}

	@Override protected CompileUnit parseAnonymousClass() throws ParseException {return LavaCompileUnit.parseAnonymousClass_static(this);}
	@Override public boolean S1parseStruct() {return false;}
}

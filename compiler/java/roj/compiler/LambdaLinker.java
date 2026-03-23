package roj.compiler;

import roj.asm.AsmCache;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.attr.Attribute;
import roj.asm.attr.StringAttribute;
import roj.asm.type.IType;
import roj.asm.type.ParameterizedType;
import roj.asm.type.Signature;
import roj.asm.type.Type;
import roj.asmx.ParamNameMapper;
import roj.compiler.api.Compiler;
import roj.compiler.ast.ParseTask;
import roj.compiler.ast.expr.Expr;
import roj.compiler.diagnostic.TextDiagnosticReporter;
import roj.compiler.library.ClassLoaderLibrary;
import roj.compiler.plugins.TypeDeclPlugin;
import roj.compiler.plugins.UintPlugin;
import roj.compiler.plugins.annotations.AnnotationsPlugin;
import roj.compiler.plugins.asm.AsmPlugin;
import roj.compiler.plugins.eval.Evaluator;
import roj.compiler.plugins.moreop.MoreOpPlugin;
import roj.compiler.plugins.stc.StreamChainPlugin;
import roj.compiler.resolve.Inferrer;
import roj.compiler.resolve.Resolver;
import roj.compiler.runtime.LambdaCompiler;
import roj.compiler.test.ComparisonChainPlugin;
import roj.compiler.test.TestPlugin;
import roj.compiler.test.TimeUnitPlugin;
import roj.compiler.types.SignatureBuilder;
import roj.reflect.Reflection;
import roj.reflect.Sandbox;
import roj.text.ParseException;

import java.util.*;

/**
 * @author Roj234
 * @since 2024/5/20 2:52
 */
public class LambdaLinker implements LambdaCompiler {
	public final LavaCompiler compiler = new LavaCompiler();
	public final CompileContext ctx;
	public final Sandbox classLoader;

	public final Map<String, Expr> injectedExpressions;

	public LambdaLinker() {
		ClassLoader theLoader = Reflection.getCallerClass(2).getClassLoader();
		classLoader = new Sandbox("LambdaLinker", theLoader);

		CompileContext.set(compiler.createContext());
		initDefaultPlugins(compiler);
		CompileContext.set(null);

		compiler.addLibrary(new ClassLoaderLibrary(theLoader));

		injectedExpressions = compiler.attachment(AsmPlugin.INJECT_PROPERTY);
		compiler.reporter = new TextDiagnosticReporter(1,1,1);
		compiler.features.add(Compiler.EMIT_SOURCE_FILE);
		compiler.features.add(Compiler.EMIT_LINE_NUMBERS);
		compiler.features.add(Compiler.EMIT_INNER_CLASS);
		compiler.features.add(Compiler.OPTIONAL_SEMICOLON);
		compiler.features.add(Compiler.OMISSION_NEW);
		compiler.features.add(Compiler.SHARED_STRING_CONCAT);
		compiler.features.add(Compiler.OMIT_CHECKED_EXCEPTION);

		ctx = compiler.createContext();
	}

	@Deprecated
	static void initDefaultPlugins(LavaCompiler api) {
		api.addLibrary(Resolver.Libs.SELF);

		Evaluator.pluginInit(api);
		new AsmPlugin(api);
		new AnnotationsPlugin(api);
		new MoreOpPlugin(api);
		new TypeDeclPlugin(api);
		new UintPlugin(api);
		new TestPlugin(api);
		TimeUnitPlugin.pluginInit(api);
		new StreamChainPlugin(api);
		new ComparisonChainPlugin(api);
	}

	@Override
	public Object compile(String source, String fileName, IType genericType, String... argumentNames) throws ParseException, LinkageError {
		ClassNode node = compiler.resolve(genericType.owner());
		MethodNode lambdaMethod = compiler.link(node).getLambdaMethod();
		if (lambdaMethod == null)
			throw new IllegalArgumentException(genericType+" is not a FunctionalInterface");

		var unit = new VirtualCompileUnit(fileName, source+"}");

		unit.version = CompileUnit.JavaVersion(8);
		unit.name("roj/lambda/Lambda$"+Reflection.uniqueId());
		unit.addInterface(genericType.owner());

		if (genericType.kind() != IType.SIMPLE_TYPE) {
			var sign = new SignatureBuilder(Signature.CLASS);
			sign.set(1, genericType);
			sign.applyTypeParam(unit);
			unit.addAttribute(sign);
		}

		unit.addAttribute(new StringAttribute(Attribute.SourceFile, fileName));
		unit.getImportList().setImportAny(true);

		MethodNode impl = new MethodNode(Opcodes.ACC_PUBLIC, unit.name(), lambdaMethod.name(), "()V");
		unit.methods.add(impl);

		if (genericType.kind() != IType.SIMPLE_TYPE) {
			ParameterizedType parameterizedType = (ParameterizedType) genericType;

			var typeVariables = node.getSignature().typeVariables;
			var typeParameterMap = Inferrer.createSubstitutionMap(typeVariables, parameterizedType.typeParameters);

			Signature lambdaMethodSign = lambdaMethod.getAttribute(node, Attribute.SIGNATURE);
			if (lambdaMethodSign != null) {
				List<Type> parameters = impl.parameters();
				List<IType> values = lambdaMethodSign.values;
				for (int j = 0; ;) {
					IType value = values.get(j++);
					Type e = Inferrer.substituteTypeVariables(value, typeParameterMap).rawType();
					if (j == values.size()) {
						impl.setReturnType(e);
						break;
					}
					parameters.add(e);
				}
			}
		} else {
			impl.rawDesc(lambdaMethod.rawDesc());
		}

		List<String> list;
		if (argumentNames == null) {
			list = Objects.requireNonNull(ParamNameMapper.getParameterNames(node.cp, lambdaMethod), "Could not get parameter name");
		} else {
			list = Arrays.asList(argumentNames);
		}

		try {
			unit.addParseTask(ParseTask.singleMethod(impl, list));
			compiler.addCompileUnit(unit);

			var compiled = compiler.compile(Collections.emptyList());
			if (compiled == null) return null;
			for (ClassNode data : compiled) {
				if (data != unit)
					classLoader.classBytes.put(data.name(), AsmCache.toByteArray(data));
			}

			return Reflection.createInstance(classLoader, unit);
		} catch (Throwable e) {
			throw new IllegalArgumentException("Exception compiling lambda for "+genericType, e);
		} finally {
			compiler.parsables.clear();
		}
	}
}
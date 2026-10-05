package roj.http.server.auto;

import org.jetbrains.annotations.Nullable;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.annotation.AnnVal;
import roj.asm.annotation.Annotation;
import roj.asm.annotation.ArrayVal;
import roj.asm.attr.Annotations;
import roj.asm.attr.Attribute;
import roj.asm.attr.ParameterAnnotations;
import roj.asm.cp.CstString;
import roj.asm.frame.FrameVisitor;
import roj.asm.insn.CodeWriter;
import roj.asm.insn.Label;
import roj.asm.insn.SwitchBlock;
import roj.asm.insn.TryCatchBlock;
import roj.asm.type.IType;
import roj.asm.type.Signature;
import roj.asm.type.Type;
import roj.asm.type.TypeHelper;
import roj.asmx.ParamNameMapper;
import roj.ci.annotation.IndirectReference;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.IntMap;
import roj.collect.ToIntMap;
import roj.concurrent.Task;
import roj.config.ConfigMaster;
import roj.config.mapper.ObjectMapper;
import roj.config.node.ConfigValue;
import roj.http.HttpUtil;
import roj.http.server.*;
import roj.io.IOUtil;
import roj.reflect.Reflection;
import roj.reflect.VirtualReference;
import roj.text.HtmlEntities;
import roj.util.ByteList;
import roj.util.FastFailException;
import roj.util.Helpers;
import roj.util.TypedKey;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static roj.asm.Opcodes.*;

/**
 * 注解定义的Http路由器.
 * <p>函数声明:</p>
 * <ul>
 *   <li>在函数上使用{@link roj.http.server.auto.Route}、{@link GET}或{@link POST}注解，以声明一个<i>请求处理器</i></li>
 *   <li>若使用Route注解，默认同时允许GET和POST，可用{@link Accepts}控制允许的请求方法</li>
 *   <li>上述注解使用<i>函数名称</i>.replace("__", "/")作为无value时的缺省路径</li>
 *   <li>{@link roj.http.server.auto.Route 路径匹配语法}</li>
 * </ul>
 * <p>参数注入:</p>
 * <ul>
 *   <li>{@link QueryParam}, {@link RequestParam}, {@link Header}: 将<i>来源</i>的数据注入到对应函数参数, 支持基本类型或String.</li>
 *   <li>{@link roj.http.server.auto.Route#deserializeFrom()}: 设置无注解的参数的默认<i>来源</i>.</li>
 *   <li>{@link Body} 将请求payload作为对象反序列化并注入参数, 支持JSON、MsgPack或表单(表单更建议用{@link Request#formData()}).</li>
 * </ul>
 * <p>拦截器/过滤器链 {@link Interceptor}:</p>
 * <ul>
 *   <li>1. 用于非<i>请求处理器</i>函数: 将其注册为过滤器，过滤器可以通过返回非null的响应中止请求处理.</li>
 *   <li>注册过滤器时，只能声明一个名称.</li>
 *   <li>注册过滤器时，可以声明是否为<i>全局过滤器</i>，其在整个OKRouter实例有效，否则仅在注册的对象有效.</li>
 *   <li>2. 用于<i>请求处理器</i>函数时: 为它增加过滤器，它们以声明的顺序（先类后函数）在接收完该请求头时调用，而<i>请求处理器</i>会在请求体接收完后调用.</li>
 *   <li>该模式也可对类使用，设置全局基础值(与方法上的注解合并).</li>
 * </ul>
 * <p>其它:</p>
 * <ul>
 *   <li>{@link Mime}: 设置返回String函数的Content-Type请求头，不标记时无默认值，这不影响返回{@link Content}的函数</li>
 *   <li>除了<i>请求处理器</i>，也可在类上使用，设置全局默认值(可被方法上的注解覆盖).</li>
 *   <li>支持JetBrains的注解，例如 {@link org.jetbrains.annotations.Nullable} 和 {@link org.jetbrains.annotations.Range 未实现}.
 * </ul>
 *
 * <p>注册:</p>
 * 在实例上调用 {@link #register(Object)} 以注册一个路由器, 为避免冲突，可以通过 {@link #register(Object, String)} 添加路径前缀.
 * 冲突的路径将会在注册时抛出IllegalArgumentException
 * 你也可以使用 {@link #addPrefixDelegation(String, Router)} 以Route注解的前缀通配模式代理其它Router实例.
 * 使用ASM读取注解，需要class文件能从类加载器访问 {@link ClassLoader#getResourceAsStream(String)}
 *
 * @see roj.http.server.auto.Route
 * @see GET
 * @see POST
 * @see Accepts
 * @see Body
 * @see QueryParam
 * @see RequestParam
 * @see Interceptor
 * @see Mime
 */
public final class OKRouter implements Router {
	private static final String REQ = "roj/http/server/Request";
	private static final int ACCEPTS_ALL = 511;

	private final TypedKey<RouteInfo> RouterImplKey = new TypedKey<>("or:router");
	private final HashMap<String, Dispatcher> interceptors = new HashMap<>();

	private final RouteNode route = new LiteralNode("");

	private final boolean debug;
	private List<Task> onFinishes = Collections.emptyList();

	public OKRouter() {this(true);}
	public OKRouter(boolean debug) {this.debug = debug;}

	/**
	 * 警告：如果使用addPrefixDelegation添加OKRouter，那么onFinish可能不会被触发
	 */
	public void onFinish(Task callback) {
		if (onFinishes.isEmpty()) onFinishes = new ArrayList<>();
		onFinishes.add(callback);
	}

	//region 代码生成
	/**
	 * 从注解生成用户路由器的调用实例(Dispatcher)以及方法ID映射
	 */
	private static final class DispatcherBuilder {
		private final boolean debug;

		private ClassNode generatedClass;
		private CodeWriter cw;
		private final List<TryCatchBlock> exceptionHandlers = new ArrayList<>();
		private final Annotation defaultSource = new Annotation("");
		// slot0 this, slot1 request, slot2 handler
		private int localIndices, nextLocalIndex = 3;
		// instance, req, rh
		private int bodyUsedFlags;

		DispatcherBuilder(boolean debug) {this.debug = debug;}
		RouteRegistration build(Class<?> type) {
			var userRoute = ClassNode.fromType(type);
			if (userRoute == null) throw new IllegalStateException("找不到"+type.getName()+"的类文件");

			var caller = generatedClass = new ClassNode();
			caller.name(type.getName().replace('.', '/')+"$Router");
			caller.interfaces().add("roj/http/server/auto/OKRouter$Dispatcher");
			caller.defaultConstructor();

			caller.newField(ACC_PRIVATE, "$m", "I");
			caller.newField(ACC_PRIVATE, "$i", TypeHelper.class2asm(type));

			var cw = caller.newMethod(ACC_PUBLIC, "setMethodId", "(ILjava/lang/Object;)Lroj/http/server/auto/OKRouter$Dispatcher;");
			cw.visitSize(2, 3);

			cw.newObject(caller.name());
			cw.insn(ASTORE_0);

			cw.insn(ALOAD_0);
			cw.insn(ILOAD_1);
			cw.field(PUTFIELD, caller, 0);

			cw.insn(ALOAD_0);
			cw.insn(ALOAD_2);
			cw.clazz(CHECKCAST, type.getName().replace('.', '/'));
			cw.field(PUTFIELD, caller, 1);

			cw.insn(ALOAD_0);
			cw.insn(ARETURN);
			cw.finish();

			cw = this.cw = caller.newMethod(ACC_PUBLIC, "invoke", "(L"+REQ+";Lroj/http/server/Response;Ljava/lang/Object;)Ljava/lang/Object;");
			cw.visitSize(5, 4);

			cw.insn(ALOAD_0);
			cw.field(GETFIELD, caller, 1);

			cw.insn(ALOAD_0);
			cw.field(GETFIELD, caller, 0);

			var seg = cw.tableSwitch();

			SwitchBlock seg2;
			CodeWriter cw2;
			if (debug) {
				cw2 = caller.newMethod(ACC_PUBLIC | ACC_FINAL, "toString", "()Ljava/lang/String;");
				cw2.visitSize(1, 1);
				cw2.insn(ALOAD_0);
				cw2.field(GETFIELD, caller, 0);
				seg2 = cw2.tableSwitch();
				cw2.addSegment(seg2);
			} else {
				seg2 = null;
				cw2 = null;
			}

			int methodId = 0;
			IntMap<Annotation> handlers = new IntMap<>();
			ToIntMap<String> interceptors = new ToIntMap<>();

			var prependInterceptors = getPrependInterceptors(userRoute);

			var methods = userRoute.methods;
			for (int i = 0; i < methods.size(); i++) {
				var mn = methods.get(i);

				var map = parseAnnotations(Annotations.getAnnotations(userRoute, mn, false));
				if (map == null) continue;

				if (map.type().equals("roj/http/server/auto/Interceptor")) {
					var value = map.getList("value");
					if (value.size() > 1) throw new IllegalArgumentException("作为预处理器函数的@Interceptor的values长度只能为0或1");

					var name = value.isEmpty() ? mn.name() : value.getString(0);
					Integer prev = interceptors.putInt(name, methodId++ | (map.getBool("global") ? Integer.MIN_VALUE : 0));
					if (prev != null) throw new IllegalArgumentException("预处理器名称重复: "+prev);
				} else {
					if (prependInterceptors != null) {
						var self = map.getList("interceptor");
						if (self.isEmpty()) map.put("interceptor", new ArrayVal(prependInterceptors));
						else self.raw().addAll(0, prependInterceptors);
					}

					handlers.put(methodId++, map);
					if (!map.containsKey("value"))
						map.put("value", AnnVal.valueOf(mn.name().replace("__", "/")));
				}

				Label self = cw.label();
				seg.branch(seg.cases.size(), self);
				seg.def = self;
				List<Type> par = mn.parameters();

				noBody:{
					int begin = 2;

					hasBody: {
						if (par.isEmpty()) break noBody;

						if (!REQ.equals(par.get(0).owner)) {
							begin = 0;
							break hasBody;
						}

						if (par.size() == 1) {
							cw.insn(ALOAD_1);
							break noBody;
						}

						if(!"roj/http/server/Response".equals(par.get(1).owner)) {
							cw.insn(ALOAD_1);
							begin = 1;
							break hasBody;
						}

						cw.insn(ALOAD_1);
						cw.insn(ALOAD_2);
						if (par.size() <= 2) break noBody;
					}

					if (map.type().equals("roj/http/server/auto/Interceptor")) {
						cw.insn(ALOAD_3);
						cw.clazz(CHECKCAST, PayloadInfo.class.getName().replace('.', '/'));
					} else {
						defaultSource.put("source", map.getString("deserializeFrom", "UNDEFINED"));
						convertParams(userRoute, mn, begin);
					}
				}

				cw.invoke(INVOKEVIRTUAL, mn.owner(), mn.name(), mn.rawDesc());
				if (mn.returnType().type != Type.OBJECT) {
					if (mn.returnType().type != Type.VOID)
						throw new IllegalArgumentException("方法返回值必须是空值或对象:"+mn);
					else cw.insn(ACONST_NULL);
				}
				cw.insn(ARETURN);

				if (seg2 != null) {
					Label label = cw2.label();
					seg2.branch(seg2.cases.size(), label);
					seg2.def = label;
					cw2.ldc(mn.owner()+"."+mn.name()+mn.rawDesc());
					cw2.insn(ARETURN);
				}
			}
			if (seg.def == null) throw new IllegalArgumentException(userRoute.name()+"没有任何处理函数");

			List<TryCatchBlock> exceptionHandlers = this.exceptionHandlers;
			if (debug) {
				var map = new HashMap<String, Label>();
				for (var tce : exceptionHandlers) {
					var javacsb = cw;
					tce.handler.set(map.computeIfAbsent(tce.type, s -> {
						Label label = javacsb.label();
						javacsb.insn(ALOAD_1);
						javacsb.ldc(tce.type);
						javacsb.invoke(INVOKESTATIC, "roj/http/server/auto/OKRouter", "requestDebug", "(Ljava/lang/Throwable;Lroj/http/server/Request;Ljava/lang/String;)Lroj/http/server/IllegalRequestException;");
						javacsb.insn(ATHROW);
						return label;
					}));
					cw._addLabel(tce.handler);
				}
			} else {
				for (var tce : exceptionHandlers) {
					cw.label(tce.handler);
				}
				cw.field(GETSTATIC, "roj/http/server/IllegalRequestException", "BAD_REQUEST", "Lroj/http/server/IllegalRequestException;");
				cw.insn(ATHROW);
			}
			cw.visitExceptions();
			for (var tce : exceptionHandlers) cw.visitException(tce.start,tce.end,tce.handler,null);
			cw.finish();

			if (clinit != null) {
				clinit.insn(RETURN);
				clinit.finish();
			}

			var inst = (Dispatcher) Reflection.createInstance(type, caller);
			var defaultMime = Annotation.findInvisible(userRoute, userRoute, "roj/http/server/auto/Mime");
			return new RouteRegistration(handlers, interceptors, inst, defaultMime != null ? defaultMime.getString("value") : null);
		}

		private final ToIntMap<String> fieldIds = new ToIntMap<>();
		private CodeWriter clinit;
		private void loadType(String type) {
			int fid = fieldIds.getOrDefault(type, -1);
			if (fid < 0) {
				fid = generatedClass.newField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "$"+generatedClass.fields.size(), "Lroj/asm/type/IType;");
				fieldIds.putInt(type, fid);

				if (clinit == null) {
					clinit = generatedClass.newMethod(ACC_PUBLIC|ACC_STATIC, "<clinit>", "()V");
					clinit.visitSize(1, 0);
				}

				clinit.ldc(new CstString(type));
				clinit.invokeS("roj/asm/type/Signature", "parseGeneric", "(Ljava/lang/CharSequence;)Lroj/asm/type/IType;");
				clinit.field(PUTSTATIC, generatedClass, fid);
			}

			cw.field(GETSTATIC, generatedClass, fid);
		}

		private void convertParams(ClassNode node, MethodNode method, int begin) {
			List<List<Annotation>> annos = ParameterAnnotations.get(node, method, false);

			Signature signature = method.getAttribute(node, Attribute.SIGNATURE);
			List<IType> genTypes = signature == null ? Collections.emptyList() : signature.values;

			List<String> parNames = ParamNameMapper.getParameterNames(node.cp, method);
			if (parNames == null) parNames = Collections.emptyList();

			localIndices = 0;
			nextLocalIndex = 3;
			bodyUsedFlags = 0;

			List<Type> parTypes = method.parameters();
			for (; begin < parTypes.size(); begin++) {
				processParameter(begin>=annos.size()?null:parseParameterAnnotations(annos.get(begin)),
						begin>=parNames.size()?null:parNames.get(begin),
						parTypes.get(begin),
						begin>=genTypes.size() ? null : genTypes.get(begin));
			}

			cw.visitSizeMax(TypeHelper.paramSize(method.rawDesc())+3, nextLocalIndex);
		}
		private void processParameter(@Nullable Annotation field, String name, Type rawType, IType type) {
			if (field == null) field = defaultSource;
			name = field.getString("value", name);
			if (type == null) type = rawType;

			String source = field.getString("source");
			int fromSlot = 0;

			if (name == null && !source.equals("BODY")) throw new IllegalArgumentException("编译时是否保存了方法参数名称？");

			CodeWriter c = cw;
			switch (source) {
				case "HEAD" -> fromSlot = 1;
				case "POST" -> {
					fromSlot = (localIndices >>> 8) & 0xFF;
					if (fromSlot == 0) {
						bodyUsedFlags |= 2;

						c.insn(ALOAD_1);
						c.invoke(INVOKEVIRTUAL, REQ, "formData", "()Ljava/util/Map;");
						c.vars(ASTORE, fromSlot = nextLocalIndex);
						localIndices |= nextLocalIndex++ << 8;
					}
				}
				case "GET" -> {
					fromSlot = (localIndices) & 0xFF;
					if (fromSlot == 0) {
						bodyUsedFlags |= 1;

						c.insn(ALOAD_1);
						c.invoke(INVOKEVIRTUAL, REQ, "queryParam", "()Ljava/util/Map;");
						c.vars(ASTORE, fromSlot = nextLocalIndex);
						localIndices |= nextLocalIndex++;
					}
				}
				case "COOKIE" -> {
					fromSlot = (localIndices >>> 16) & 0xFF;
					if (fromSlot == 0) {
						c.insn(ALOAD_1);
						c.invoke(INVOKEVIRTUAL, REQ, "cookie", "()Ljava/util/Map;");
						c.vars(ASTORE, fromSlot = nextLocalIndex);
						localIndices |= nextLocalIndex++ << 16;
					}
				}
				case "BODY" -> {
					if ((bodyUsedFlags & 4) == 0) {
						bodyUsedFlags |= 4;

						if (rawType.getActualType() != Type.OBJECT)
							throw new IllegalArgumentException("基本类型无法使用JSON解析");

						var tce = new TryCatchBlock();
						tce.start = cw.label();
						tce.handler = new Label();
						tce.type = type+" "+name;

						c.insn(ALOAD_1);
						loadType(type.toDesc());
						c.invoke(INVOKESTATIC, "roj/http/server/auto/OKRouter", "parse", "(L"+REQ+";Lroj/asm/type/IType;)Ljava/lang/Object;");
						c.clazz(CHECKCAST, rawType);

						tce.end = cw.label();
						exceptionHandlers.add(tce);
						return;
					} else {
						throw new IllegalArgumentException("JSON类型仅能出现一次(这是反序列化请求体啊)");
					}
				}
			}
			if ((bodyUsedFlags & 6) == 6) throw new IllegalArgumentException("不能同时使用POST和BODY类型");

			var tce = new TryCatchBlock();
			tce.start = cw.label();
			tce.handler = new Label();
			tce.type = type+" "+name;

			if (source.equals("PARAM")) {
				c.insn(ALOAD_1);
				// 这个不需要解析，所以也不需要缓存到本地变量
				c.invoke(INVOKEVIRTUAL, REQ, "arguments", "()Lroj/http/Headers;");
			} else {
				if (fromSlot == 0) throw new IllegalStateException("不支持的类型:"+source);
				c.vars(ALOAD, fromSlot);
			}

			c.ldc(name);
			String orDefault = field.getString("orDefault", null);
			if (orDefault != null) {
				c.ldc(orDefault);
				c.invokeItf("java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
			} else {
				c.invokeItf("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
				if (!field.getBool("optional", false)) {
					cw.insn(DUP);
					var label = new Label();
					cw.jump(IFNONNULL, label);
					cw.insn(POP);
					cw.clazz(NEW, "roj/util/FastFailException");
					cw.insn(DUP);
					cw.ldc("参数缺失");
					cw.invokeD("roj/util/FastFailException", "<init>", "(Ljava/lang/String;)V");
					cw.insn(ATHROW);
					cw.label(label);
				}
			}

			addExHandler: {
				int type1 = rawType.getActualType();
				if (type1 == Type.OBJECT) {
					if (rawType.owner != null) {
						if (rawType.owner.equals("java/lang/String") || rawType.owner.equals("java/lang/CharSequence")) {
							cw.invoke(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;");
							break addExHandler;
						}
					}
					throw new IllegalArgumentException("无法将String转换为"+rawType);
				}
				if (type1 == Type.CHAR) {
					// stack=4;
					cw.invoke(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I");
					cw.insn(DUP);
					cw.insn(DUP);
					cw.insn(I2C);
					cw.jump(IF_icmpne, createOutOfBounds());
				} else {
					name = rawType.capitalized();
					cw.clazz(CHECKCAST, "java/lang/String");
					cw.invoke(INVOKESTATIC, "java/lang/"+(name.equals("Int")?"Integer":name), "parse"+name, "(Ljava/lang/String;)"+(char)rawType.type);

					long min = field.getLong("min", Long.MIN_VALUE);
					if (min != Long.MIN_VALUE) {
						if (rawType.type == Type.LONG) {
							cw.insn(DUP2);
							cw.ldc(min);
							cw.insn(LCMP);
							cw.jump(IFLT, createOutOfBounds());
						} else {
							cw.insn(DUP);
							cw.ldc((int) min);
							cw.jump(IF_icmplt, createOutOfBounds());
						}
						cw.computeFrames(FrameVisitor.COMPUTE_SIZES);
					}
					long max = field.getLong("max", Long.MAX_VALUE);
					if (max != Long.MAX_VALUE) {
						if (rawType.type == Type.LONG) {
							cw.insn(DUP2);
							cw.ldc(max);
							cw.insn(LCMP);
							cw.jump(IFGT, createOutOfBounds());
						} else {
							cw.insn(DUP);
							cw.ldc((int)max);
							cw.jump(IF_icmpgt, createOutOfBounds());
						}
						cw.computeFrames(FrameVisitor.COMPUTE_SIZES);
					}

				}
			}
			tce.end = cw.label();
			exceptionHandlers.add(tce);
		}

		private Label createOutOfBounds() {
			cw.clazz(NEW, "roj/util/FastFailException");
			cw.insn(DUP);
			cw.ldc("参数超出范围");
			cw.invokeD("roj/util/FastFailException", "<init>", "(Ljava/lang/String;)V");
			cw.insn(ATHROW);
			return cw.label();
		}

		@Nullable
		private static List<ConfigValue> getPrependInterceptors(ClassNode data) {
			var preDef = Annotation.findInvisible(data, data, "roj/http/server/auto/Interceptor");
			return preDef != null ? preDef.getList("value").raw() : null;
		}
		private static Annotation parseAnnotations(List<Annotation> list) {
			ConfigValue accepts = null, mime = null;
			Annotation interceptor = null, route = null, easyMapping = null;

			for (int j = 0; j < list.size(); j++) {
				var a = list.get(j);
				switch (a.type()) {
					case "roj/http/server/auto/Interceptor" -> interceptor = a;
					case "roj/http/server/auto/Route" -> route = a;
					case "roj/http/server/auto/Accepts" -> accepts = a.get("value");
					case "roj/http/server/auto/Mime" -> mime = a.get("value");
					case "roj/http/server/auto/GET", "roj/http/server/auto/POST" -> {
						if (easyMapping != null) throw new IllegalArgumentException(easyMapping+"不能和"+a+"共用");
						easyMapping = a;
					}
				}
			}

			if (easyMapping != null) {
				if (route != null) throw new IllegalArgumentException(easyMapping+"不能和@Route共用");
				if (accepts != null) throw new IllegalArgumentException(easyMapping+"不能和@Accepts共用");

				boolean isGet = easyMapping.type().endsWith("GET");
				easyMapping.putIfAbsent("deserializeFrom", isGet ? "GET" : "POST");
				easyMapping.put("accepts", AnnVal.valueOf(isGet ? Accepts.GET : Accepts.POST));

				route = easyMapping;
				route.setType("roj/http/server/auto/Route");
			} else if (route == null) return interceptor;

			if (interceptor != null) route.put("interceptor", interceptor.get("value"));
			if (accepts != null) route.put("accepts", accepts);
			if (mime != null) route.put("mime", mime);

			return route;
		}
		private static Annotation parseParameterAnnotations(List<Annotation> list) {
			Annotation container = null;
			String source = null;
			boolean optional = false;
			long min = Long.MIN_VALUE;
			long max = Long.MAX_VALUE;

			for (int j = 0; j < list.size(); j++) {
				var a = list.get(j);
				switch (a.type()) {
					default -> {continue;}
					case "roj/http/server/auto/RequestParam" -> {
						if (container != null) throw new IllegalStateException("注解"+a+"与已有的"+container+"重复");
						source = a.getString("source", "PARAM");
						optional = true; //实际上是不可空？
					}
					case "roj/http/server/auto/Header" -> {
						if (container != null) throw new IllegalStateException("注解"+a+"与已有的"+container+"重复");
						source = "HEAD";
					}
					case "roj/http/server/auto/QueryParam" -> {
						if (container != null) throw new IllegalStateException("注解"+a+"与已有的"+container+"重复");
						source = "GET";
					}
					case "roj/http/server/auto/Body" -> {
						if (container != null) throw new IllegalStateException("注解"+a+"与已有的"+container+"重复");
						source = "BODY";
					}
					case "org/jetbrains/annotations/Nullable" -> {
						optional = true;
						continue;
					}
					case "org/jetbrains/annotations/Range" -> {
						min = a.getLong("min", Long.MIN_VALUE);
						max = a.getLong("max", Long.MAX_VALUE);
						continue;
					}
				}

				container = a;
			}

			if (container == null) return null;

			if (min != Long.MIN_VALUE) container.putIfAbsent("min", min);
			if (max != Long.MAX_VALUE) container.putIfAbsent("max", max);

			container.put("source", source);
			if (optional) container.put("optional", true);

			return container;
		}
	}
	// 生成请求调试信息
	@IndirectReference
	public static IllegalRequestException requestDebug(Throwable exc, Request req, String msg) {
		var isBrowserRequest = req.containsKey("accept", "text/html");
		return new IllegalRequestException(400, /*isBrowserRequest ? */Content.internalError("参数'"+HtmlEntities.encode(msg)+"'解析失败", exc));
	}
	// 解析JSON请求体
	@IndirectReference
	public static Object parse(Request req, IType type) throws Exception {
		ByteList body = req.body();
		if (body == null) throw new FastFailException("没有请求体");

		var serializer = ObjectMapper.SAFE.reader(type);
		ConfigMaster configType;

		switch (req.header("content-type")) {
			default -> configType = ConfigMaster.JSON;
			case "application/x-msgpack"/* Unofficial */, "application/vnd.msgpack" -> configType = ConfigMaster.MSGPACK;
			case "application/x-www-form-urlencoded", "multipart/form-data" -> {
				var data = req.formData();
				serializer.emitMap(data.size());
				for (Map.Entry<String, String> entry : data.entrySet()) {
					serializer.emitKey(entry.getKey());
					serializer.emit(entry.getValue());
				}
				serializer.pop();
				return serializer.get();
			}
		}

		body.retain();
		return serializer.read(body, configType);
	}
	//endregion
	private static final VirtualReference<HashMap<String, RouteRegistration>> REGISTRATIONS = new VirtualReference<>();
	private static final class RouteRegistration {
		final IntMap<Annotation> handlers;
		final ToIntMap<String> interceptors;
		final Dispatcher inst;
		final String defaultMime;

		RouteRegistration(IntMap<Annotation> handlers, ToIntMap<String> interceptors, Dispatcher inst, String defaultMime) {
			this.handlers = handlers;
			this.interceptors = interceptors;
			this.inst = inst;
			this.defaultMime = defaultMime;
		}
	}

	public final OKRouter register(Object o) {return register(o, "");}
	public final OKRouter register(Object o, String pathRel) {
		var type = o.getClass();
		var map = REGISTRATIONS.computeIfAbsent(type.getClassLoader(), Helpers.cast(Helpers.fnHashMap()));
		var registration = map.get(type.getName());
		if (registration == null) {
			synchronized (map) {
				if ((registration = map.get(type.getName())) == null) {
					registration = new DispatcherBuilder(debug).build(type);
					map.put(type.getName(), registration);
				}
			}
		}

		// 存放已经实例化的拦截器
		HashMap<String, Dispatcher> interceptorInstance = new HashMap<>();
		for (var entry : registration.handlers.selfEntrySet()) {
			int i = entry.getIntKey();
			var annotation = entry.getValue();
			var info = new RouteInfo();

			info.contentType = annotation.getString("mime", registration.defaultMime);
			info.handler = registration.inst.setMethodId(i, o);

			List<Dispatcher> precs = Collections.emptyList();
			var interceptorNames = annotation.getList("interceptor");
			if (!interceptorNames.isEmpty()) {
				for (int j = 0; j < interceptorNames.size(); j++) {
					String name = interceptorNames.getString(j);

					var interceptor = interceptorInstance.get(name);
					if (interceptor == null) {
						int methodId = registration.interceptors.getOrDefault(name, -1);
						if (methodId == -1) {
							if ((interceptor = interceptors.get(name)) == null) {
								// 忽略这个拦截器
								if (interceptors.containsKey(name)) continue;
								throw new IllegalArgumentException("未找到"+annotation+"引用的拦截器"+interceptorNames.get(j));
							}
						} else {
							interceptor = registration.inst.setMethodId(methodId & Integer.MAX_VALUE, o);
						}
						interceptorInstance.put(name, interceptor);
					}

					if (precs.size() == 0) {
						precs = Collections.singletonList(interceptor);
					} else {
						if (precs.size() == 1) precs = new ArrayList<>(precs);
						precs.add(interceptor);
					}
				}
			}
			info.filters = precs.toArray(new Dispatcher[precs.size()]);

			int flag = 0;
			String subpath = annotation.getString("value");
			if (subpath.endsWith("/**")) {
				flag |= RouteNode.PREFIX;
				subpath = subpath.substring(0, subpath.length()-2);
			}
			String url = pathRel.concat(subpath);
			if (url.endsWith("/")) flag |= RouteNode.DIRECTORY;
			else if (!url.isEmpty() && annotation.getBool("strict", true)) flag |= RouteNode.FILE;

			RouteNode node = route.add(url, 0, url.length());

			int supportedMethods = annotation.getInt("accepts", Accepts.GET | Accepts.POST);
			var list = (RouteList)node.value;
			if (list == null) {
				list = new RouteList();
				list.supportedMethods = supportedMethods;
				list.defaultRoute = info;

				node.flag |= (byte) flag;
				node.value = list;
			} else {
				if (node.flag != flag) throw new IllegalArgumentException("prefix定义不同/"+o+" in "+list+"|"+info);

				RouteInfo[] routes = list.routes;
				int currentMethods = list.supportedMethods;

				if (routes == null) {
					list.routes = routes = new RouteInfo[8];
					for (int j = 0; j < 8; j++) {
						if ((currentMethods & (1<<j)) != 0) {
							routes[j] = list.defaultRoute;
						}
					}
				}

				if ((currentMethods & supportedMethods) != 0)
					throw new IllegalArgumentException("冲突的请求类型处理器/"+o+" in "+list.defaultRoute+" and "+info+" accepts="+currentMethods+" & "+supportedMethods);
				list.supportedMethods |= supportedMethods;

				for (int j = 0; j < 8; j++) {
					if ((supportedMethods & (1<<j)) != 0) {
						routes[j] = info;
					}
				}
			}
		}

		for (var entry : registration.interceptors.selfEntrySet()) {
			if ((entry.value & Integer.MIN_VALUE) != 0) {
				String name = entry.getKey();
				if (interceptors.containsKey(name)) throw new IllegalStateException(o+"的"+name+"拦截器在当前上下文重复");
				interceptors.put(name, registration.inst.setMethodId(entry.value & Integer.MAX_VALUE, o));
			}
		}
		return this;
	}

	public final Dispatcher getInterceptor(String name) {return interceptors.get(name);}
	public final void setInterceptor(String name, Dispatcher interceptor) {interceptors.put(name, interceptor);}
	public final void removeInterceptor(String name) {interceptors.remove(name);}

	/**
	 * @see #addPrefixDelegation(String, Router, String...)
	 */
	public final OKRouter addPrefixDelegation(String path, Router router) {return addPrefixDelegation(path, router, (String[])null);}

	/**
	 * 注册path前缀通配路由器.
	 * @param path 路径结尾是否有斜杠会影响策略 (严格模式总是为假)
	 */
	public final OKRouter addPrefixDelegation(String path, Router router, @Nullable String... interceptors) {
		RouteNode node = route.add(path, 0, path.length());
		if (node.value != null) throw new IllegalArgumentException("子路径'"+path+"'已存在");

		var list = new RouteList();
		list.supportedMethods = ACCEPTS_ALL;
		if (router instanceof Predicate<?>) {
			list.prefixValidator = Helpers.cast(router);
		}
		var info = new RouteInfo();
		list.defaultRoute = info;

		node.flag |= RouteNode.PREFIX|(path.endsWith("/")?RouteNode.DIRECTORY:0);
		node.value = list;

		if (interceptors == null || interceptors.length == 0) {
			if (router instanceof OKRouter child) {
				RouteNode otherRoot = child.route;
				node.flag = otherRoot.flag;
				node.value = otherRoot.value;
				node.table = otherRoot.table;
				node.size = otherRoot.size;
				node.mask = otherRoot.mask;
				node.any = otherRoot.any;
				//prependInterceptorArray(interceptors);
				return this;
			}

			info.filters = new Dispatcher[] {_getChecker(router)};
		} else {
			var prec = new Dispatcher[interceptors.length + 1];
			int size = 0;
			for (String name : interceptors) {
				Dispatcher dp = this.interceptors.get(name);
				if (dp == null) {
					if (!this.interceptors.containsKey(name))
						throw new IllegalArgumentException("无法找到请求拦截器"+name);
				} else {
					prec[size++] = dp;
				}
			}
			prec[size] = _getChecker(router);
			info.filters = size == interceptors.length ? prec : Arrays.copyOf(prec, size+1);
		}
		info.handler = (req, srv, extra) -> {
			try {
				return router.response(req, srv);
			} catch (Exception e) {
				Helpers.athrow(e);
			}
			return null;
		};

		return this;
	}
	private static Dispatcher _getChecker(Router router) {
		return (req, srv, extra) -> {
			router.checkHeader(req, (PayloadInfo) extra);
			return null;
		};
	}

	public final boolean removePrefixDelegation(String path) {return route.remove(path, 0, path.length());}

	private static final class RouteList {
		int supportedMethods;
		Predicate<String> prefixValidator = Helpers.alwaysTrue();
		RouteInfo defaultRoute;
		RouteInfo[] routes;
	}
	private static final class RouteInfo {
		String contentType;
		Dispatcher handler;
		Dispatcher[] filters;
	}

	private static final class PathMatcher {
		private final ArrayList<RouteNode> env1 = new ArrayList<>(), env2 = new ArrayList<>();
		private final ArrayList<ArrayList<String>> par3 = new ArrayList<>(), par4 = new ArrayList<>();

		int prefixLen;
		RouteList matchedValue;

		private RouteNode prefixNode;
		private ArrayList<String> prefixPar;
		private int prefixParSize;
		private boolean exactPrefixMatch;

		int allowMethod;

		final boolean match(Request req, RouteNode root, String path, int i, int end) {
			var nodeS = env1;
			var nodeD = env2;
			var parS = par3;
			var parD = par4;

			nodeS.add(root);

			prefixNode = null;
			prefixPar = null;
			exactPrefixMatch = false;

			int prevI = 0;
			while (i < end) {
				int nextI = path.indexOf('/', i);
				if (nextI >= end || nextI < 0) nextI = end;

				for (int k = 0; k < nodeS.size(); k++) {
					RouteNode node = nodeS.get(k);

					if (node instanceof ParamNode pn) {
						ArrayList<String> params = parS.size() <= k || parS.get(k) == null ? new ArrayList<>() : new ArrayList<>(parS.get(k));
						params.add(pn.name);
						params.add(path.substring(prevI, i-1));

						parD.ensureCapacity(k+1);
						parD._setSize(k+1);
						parD.getInternalArray()[k] = params;
					}

					if ((node.flag & RouteNode.PREFIX) != 0 && checkPrefixMatch(node, path, i)) {
						prefixLen = i;
						prefixNode = node;
						prefixPar = parD.size() <= k ? null : parD.get(k);
						if (prefixPar != null) prefixParSize = prefixPar.size();
					}

					node.get(path, i, nextI, nodeD);
				}

				if (nodeD.isEmpty()) {
					parS.clear();
					nodeS.clear();
					return buildPrefix(req);
				}

				var tmp = nodeS;
				nodeS = nodeD;
				nodeD = tmp;
				nodeD.clear();

				var tmp2 = parS;
				parS = parD;
				parD = tmp2;
				parD.clear();

				prevI = i;
				i = nextI+1;
			}

			RouteNode node = null;
			int priority = exactPrefixMatch ? 5 : -1;
			ArrayList<String> par = null;

			boolean isFile = path.length() > 0 && path.charAt(end - 1) != '/';
			for (int j = 0; j < nodeS.size(); j++) {
				RouteNode n = nodeS.get(j);
				if (n.value != null) {
					// 目录和文件匹配
					if ((n.flag & RouteNode.DIRECTORY) != 0) {
						if (isFile) continue;
					} else if ((n.flag & RouteNode.FILE) != 0) {
						if (!isFile) continue;
					}

					int prio = n.priority();
					if (prio > priority) {
						checkAllow(req, n);
						if (allowMethod != 0) continue;

						node = n;
						priority = prio;
						par = parS.size() <= j ? null : parS.get(j);

						if (node instanceof ParamNode rex) {
							if (par == null) par = new ArrayList<>();

							par.add(rex.name);
							par.add(path.substring(prevI, isFile ? end : end-1));
						}
					} else if (prio == priority && node != null) { // equals
						throw new IllegalStateException("该路径被多个请求处理器命中: "+node+"|"+n);
					}
				}
			}

			parS.clear();
			nodeS.clear();

			if (node == null) return buildPrefix(req);

			// 兼容之前的代码，非严格模式
			if ((node.flag&RouteNode.DIRECTORY) == 0 && !isFile && end > 0) end--;
			prefixLen = end;
			matchedValue = getNodeValue(node);
			if (par != null) {
				var pathVariable = req.arguments();
				int j = 0;
				while (j < par.size()) {
					pathVariable.add(par.get(j++), par.get(j++));
				}
			}
			return true;
		}

		private void checkAllow(Request req, RouteNode n) {
			int accepts = getNodeValue(n).supportedMethods;
			allowMethod = ((1 << req.action()) & accepts) == 0 ? accepts : 0;
		}

		private boolean checkPrefixMatch(RouteNode node, String path, int i) {
			var v = getNodeValue(node);
			return v != null && (exactPrefixMatch = v.prefixValidator.test(path.substring(i)));
		}

		private boolean buildPrefix(Request req) {
			if (prefixNode == null) return false;
			checkAllow(req, prefixNode);
			matchedValue = getNodeValue(prefixNode);

			var par = prefixPar;
			if (par != null) {
				var pathVariable = req.arguments();
				int i = 0;
				while (i < prefixParSize) {
					pathVariable.add(par.get(i++), par.get(i++));
				}
			}

			prefixNode = null;
			prefixPar = null;
			return true;
		}
		private static RouteList getNodeValue(RouteNode node) {
			var v = node.value;
			while (v instanceof RouteNode n) {
				v = n.value;
			}
			return (RouteList) v;
		}
	}
	private static abstract sealed class RouteNode {
		String name;

		// Route or Route[]
		Object value;

		byte flag;
		static final byte PREFIX = 1, DIRECTORY = 2, FILE = 4;

		private int mask;
		private LiteralNode[] table;
		int size;

		private List<ParamNode> any = Collections.emptyList();

		public RouteNode(String name) { this.name = name; }

		public boolean remove(String path, int i, int end) {
			if (i >= end) return true;

			int j = path.indexOf('/', i);
			if (j >= end || j < 0) j = end;

			if (path.charAt(i) == ':') {
				throw new IllegalArgumentException("not supported removing regexp route, try prefix delegation");
			}

			if (table != null) {
				int hash = hash(path, i, j);

				LiteralNode prev = null;
				LiteralNode node = table[hash & mask];
				while (node != null) {
					if (node.name.length() == j - i && path.regionMatches(i, node.name, 0, node.name.length())) {
						if (!node.remove(path, j + 1, end)) return false;

						if (node.size == 0) {
							if (prev != null) prev.next = node.next;
							else table[hash & mask] = node.next;
						}

						if (--size == 0) table = null;
						return true;
					}

					prev = node;
					node = node.next;
				}
			}

			return false;
		}

		RouteNode add(String path, int i, int end) {
			if (i >= end) return this;

			int j = path.indexOf('/', i);
			if (j >= end || j < 0) j = end;
			if (i >= j) throw new IllegalArgumentException("路径存在前导斜杠: "+path);

			if (table == null) {
				table = new LiteralNode[4];
				mask = 3;
			}

			int hash = hash(path, i, j);
			int count = 0;
			LiteralNode node = table[hash & mask];
			while (node != null) {
				if (node.name.length() == j - i && path.regionMatches(i, node.name, 0, node.name.length())) {
					return node.add(path, j+1, end);
				}
				node = node.next;
				count++;
			}

			if (count >= 3) resize();

			RouteNode node1;
			if (path.charAt(i) == ':') {
				// regexNode
				var node2 = new ParamNode(path.substring(i+1, j));
				if (j == end && node2.pattern == null && (node2.flag & ParamNode.ZERO) != 0)
					throw new IllegalStateException("不允许在非末尾段中使用不带正则模式的零宽转移");
				if (any.isEmpty()) any = new ArrayList<>();

				i = any.indexOf(node2);
				if (i >= 0) node1 = any.get(i);
				else {
					if ((node2.flag&8) != 0 && j >= end) {
						if (value != null) throw new IllegalStateException("Already have "+value);
						value = node2;
					}
					any.add(node2);
					node1 = node2;
				}
			} else {
				node = new LiteralNode(path.substring(i, j));
				node.next = table[hash &= mask];
				table[hash] = node;
				size++;

				node1 = node;
			}
			return node1.add(path, j+1, end);
		}
		static int hash(String s, int off, int end) {
			int h = 1;
			while (off < end) h = 31*h + s.charAt(off++);
			return h;
		}
		private void resize() {
			int length = (mask+1) << 1;
			LiteralNode[] tab1 = new LiteralNode[length];
			int mask1 = length-1;

			int i = 0, j = table.length;
			for (; i < j; i++) {
				LiteralNode entry = table[i];

				while (entry != null) {
					LiteralNode next = entry.next;

					int newKey = hash(entry.name, 0, entry.name.length()) & mask1;

					entry.next = tab1[newKey];
					tab1[newKey] = entry;

					entry = next;
				}
			}

			this.table = tab1;
			this.mask = mask1;
		}

		String get(String path, int i, int end, List<RouteNode> nodes) {
			if (table != null) {
				int hash = hash(path, i, end);
				LiteralNode node = table[hash & mask];
				while (node != null) {
					if (node.name.length() == end - i && path.regionMatches(i, node.name, 0, node.name.length())) {
						nodes.add(node);
					}
					node = node.next;
				}
			}

			for (int j = 0; j < any.size(); j++) {
				any.get(j).match(path, i, end, nodes);
			}

			return null;
		}

		int priority() {
			// 高于正则匹配
			int prio = 5;
			// 完整匹配 > 前缀匹配
			if ((flag & PREFIX) == 0) prio++;
			return prio;
		}
	}
	private static final class LiteralNode extends RouteNode {
		LiteralNode next;
		LiteralNode(String name) { super(name); }
	}
	private static final class ParamNode extends RouteNode {
		Pattern pattern;

		@Override
		public String toString() {
			return ":"+name+"("+pattern+")"+switch (flag&(ZERO|MORE)) {
				case MORE -> "+";
				case ZERO -> "?";
				case ZERO|MORE -> "*";
				default -> "";
			};
		}

		static final Pattern REGEXP = Pattern.compile("([A-Za-z0-9-_]+)(?:\\((.+?)\\))?([+*?])?");
		static final int MORE = 8, ZERO = 16;
		ParamNode(String url) {
			super("regexp");

			Matcher m = REGEXP.matcher(url);
			if (!m.matches()) throw new IllegalArgumentException(url);

			name = m.group(1);
			String regexp = m.group(2);
			if (regexp != null) this.pattern = Pattern.compile(regexp);
			String count = m.group(3);
			if (count != null) {
				if (count.equals("+")) {
					flag |= MORE;
				} else if (count.equals("*")) {
					flag |= ZERO | MORE;
				} else {
					flag |= ZERO;
				}
			}
		}

		final void match(String path, int i, int end, List<RouteNode> nodes) {
			if (pattern != null) {
				Matcher m = pattern.matcher(path);
				if (!m.find(i) || m.start() != i || m.end() != end) {
					if ((flag&ZERO) != 0) super.get(path, i, end, nodes);
					return;
				}
			}

			nodes.add(this);
		}

		@Override
		final String get(String path, int i, int end, List<RouteNode> nodes) {
			if ((flag&MORE) != 0) {
				Matcher m;
				if (pattern == null || (m = pattern.matcher(path)).find(i) && m.start() == i && m.end() == end) {
					nodes.add(this);
				}
			}

			super.get(path, i, end, nodes);
			return name;
		}

		@Override
		int priority() {
			int prio = 0;
			// 有限匹配 > 无限匹配
			if ((flag & (ZERO|MORE)) == 0) prio++;
			// 必选匹配 > 可选匹配
			if ((flag & MORE) == 0) prio++;
			// 有限制 > 无限制
			if (pattern != null) prio++;
			return prio;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			ParamNode node = (ParamNode) o;
			if (!name.equals(node.name) || flag != node.flag) return false;
			return pattern != null ? pattern.pattern().equals(node.pattern.pattern()) : node.pattern == null;
		}

		@Override
		public int hashCode() {
			int h = (name.hashCode() << 1) ^ flag;
			return pattern != null ? h^pattern.hashCode() : h;
		}
	}

	@Override
	public void checkHeader(Request req, @Nullable PayloadInfo cfg) throws IllegalRequestException {
		String path = req.path();

		var m = (PathMatcher) req.threadLocal().get("or:pathMatcher");
		if (m == null) req.threadLocal().put("or:pathMatcher", m = new PathMatcher());

		RouteList matchedValue = m.match(req, route, path, 0, path.length()) ? m.matchedValue : null;

		RouteInfo set;

		if (matchedValue != null) {
			set = getRoute(m.matchedValue, req.action());
			req.setPath(path.substring(m.prefixLen));
		} else {
			if (m.allowMethod != 0) {
				req.responseHeader().put("allow", serializeAllow(m.allowMethod));
				throw new IllegalRequestException(HttpUtil.METHOD_NOT_ALLOWED);
			}

			set = null;
		}

		if (set == null) throw new IllegalRequestException(403);

		req.connection().attachment(RouterImplKey, set);
		for (Dispatcher prec : set.filters) {
			Object ret = prec.invoke(req, req.response(), cfg);
			if (ret != null) {
				if (ret instanceof Content r) throw new IllegalRequestException(0, r);
				throw new IllegalRequestException(0, ret.toString());
			}
		}

		if (cfg != null && !cfg.isAccepted()) Router.super.checkHeader(req, cfg);
	}

	private RouteInfo getRoute(RouteList o, int action) {
		return o.routes == null ? o.defaultRoute : o.routes[action];
	}

	private static String serializeAllow(int accepts) {
		var sb = IOUtil.getSharedByteBuf();
		for (int i = 0; i < 8; i++) {
			if (((1<<i) & accepts) != 0) {
				sb.putAscii(HttpUtil.getMethodName(i)).putAscii(", ");
			}
		}

		if(sb.wIndex() > 0) sb.wIndex(sb.wIndex()-2);
		return sb.toString();
	}

	@Override
	public Content response(Request req, Response resp) throws IOException {
		RouteInfo set = req.connection().attachment(RouterImplKey, null);

		Object ret;
		try {
			ret = set.handler.invoke(req, resp, null);
		} finally {
			for (var c : onFinishes) {
				try {
					c.run();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		if (ret instanceof Content r) return r;
		if (ret == null) return null;
		return new TextContent(ret instanceof CharSequence cs ? cs : ret.toString(), set.contentType);
	}

	public interface Dispatcher {
		Object invoke(Request req, Response server, Object argument) throws IllegalRequestException;
		default Dispatcher setMethodId(int methodId, Object instance) { return this; }
	}
}
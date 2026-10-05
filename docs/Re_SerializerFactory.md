# 通用对象的安全序列化解决方案
- 无需使用反射
- 支持任意对象
- 通过泛型推断目标类型
- “安全”指什么呢？
- 若是为了防止未知类的反序列化
    - 只需确保未开启动态模式(ALLOW_DYNAMIC)
    - 默认情况下，getInstance方法不开启动态模式
- 或者说...循环引用不会导致问题
    - 若要实现该功能，请打开OBJECT_POOL

## 注意事项
### ToMap
像这样
```java
class T { Object x = new byte[2]; }  
```
如果在Object类型的字段中存放了数组，字符串或基本类型的包装类（或任何本应不被序列化成映射的对象），它们将在序列化过程中转换为映射，以避免丢失原始类型信息。
```json
{"x":{"":"[B","v":[0,0]}}
```

### '或'类型
Either<Left, Right>
此类型可用于表示两种不同的可序列化数据类型，这两种类型必须在以下选项中各选一种且不可重复：字符串和基本数据类型、集合或列表、对象或映射。

## 标记(flag):
* `GENERATE`        对未生成的类自动生成序列化器
* `CHECK_INTERFACE` 检查类实现的接口是否有序列化器
* `CHECK_PARENT`    检查类的父类是否有序列化器
* `NO_CONSTRUCTOR`  不调用构造器（不开启，如果没有无参构造器将会报错）
* `SAFE`            扩展安全检查  检查字段访问权限是否为public，而不是使用unsafe绕过
* `OBJECT_POOL`     对象池   使用一个ID来替换序列化过的对象
* `SERIALIZE_PARENT`**序列化父类**   不能与CHECK_PARENT共用
* `NO_SCHEMA`       使用int序列化属性名称，在MsgPack格式中使用效果最佳

### 动态模式: 根据对象的类型来动态序列化，可能影响性能，并且可能造成任意对象反序列化的安全风险
* `ALLOW_DYNAMIC`   允许动态模式  仅应用到无法确定类型的字段
* `PREFER_DYNAMIC`  优先动态模式  禁用泛型推断

如果序列化的类符合以下任一条件：
- 包含Object类型（包括泛型擦除的Object，目前仅支持Collection、Map和Either的自动泛型处理）
- 是CharSequence、Number、List、Set、Collection、Map或Either之外的接口
- 是抽象类

在这种情况下，需要启用ALLOW_DYNAMIC选项，否则将会出现错误，因为无法确定具体的类。
下面代码中，我启用了ALLOW_DYNAMIC，因为测试的Pojo类中有一个Object（Map的泛型）

```java

import roj.config.ConfigMaster;
import roj.config.mapper.*;
import roj.config.serial.ToJson;
import roj.text.CharList;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;

public class Test {
	public static void main(String[] args) throws Exception {
		SerializerFactory man = SerializerFactory.getInstance(
				SerializerFactory.GENERATE | SerializerFactory.CHECK_INTERFACE | SerializerFactory.CHECK_PARENT | SerializerFactory.ALLOW_DYNAMIC);
		// 自定义序列化方式(使用@As调用)
		man.asRGB();
		// 自定义序列化器(不一定要匿名类)
		// 方法名称也不重要 参数和返回值才重要
		man.add(Charset.class, new Object() {
			public String serializeMyObject(Charset cs) {
				return cs.name();
			}

			public Charset deserMyObj(String s) {
				return Charset.forName(s);
			}
		});

		// 生成一个序列化器
		Serializer<Pojo> adapter = man.serializer(Pojo.class);

		Pojo p = new Pojo();
		p.color = 0xAABBCC;
		p.charset = StandardCharsets.UTF_8;
		p.map = Map.of("114514", Map.of("1919810", 23333L));

		// simple
		ConfigMaster.YAML.writeObject(adapter, p, new File("C:\\test.yml"));
		p = ConfigMaster.YAML.readObject(adapter, new File("C:\\test.yml"));

		// or CVisitor
		ToJson ser = new ToJson();
		adapter.write(ser, p);
		CharList json = ser.getValue();
		System.out.println(json);

		System.out.println(adapter.read(new CCJson(), json, 0));
	}

	public static class Pojo {
		// 自定义序列化方式
		@As("rgb")
		// 自定义序列化名称
		@Name("myColor")
		private int color;
		// 通过getter或setter来访问字段
		@Via(get = "getCharset", set = "setCharset")
		public Charset charset;
		// 支持任意对象和多层泛型
		// 字段类型为接口和抽象类时，会用ObjAny序列化对象，会使用==表示对象的class
		// 如果要保留这个Map的类型，那就（1）字段改成HashMap(具体)或者（2）开启DYNAMIC
		public Map<String, Map<String, Object>> map;

		//使用transient避免被序列化
		private transient Object doNotSerializeMe;

		// 若有无参构造器则调用之，否则allocateInstance
		public Pojo() {
		}

		public Charset getCharset() {
			return charset;
		}

		public void setCharset(Charset charset) {
			this.charset = charset;
		}

		@Override
		public String toString() {
			return "Pojo{" + "color=" + color + '}';
		}
	}
}
```
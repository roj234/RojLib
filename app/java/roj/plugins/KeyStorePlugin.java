package roj.plugins;

import roj.archive.zip.ZipEditor;
import roj.collect.ArrayList;
import roj.collect.CollectionX;
import roj.collect.HashMap;
import roj.config.node.ConfigValue;
import roj.config.node.MapValue;
import roj.crypt.KeyType;
import roj.crypt.jar.JarVerifier;
import roj.io.IOUtil;
import roj.net.mss.MSSKeyPair;
import roj.plugin.Plugin;
import roj.plugin.SimplePlugin;
import roj.ui.Argument;
import roj.ui.Command;
import roj.ui.Tty;
import roj.util.function.Either;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static roj.ui.CommandNode.argument;
import static roj.ui.CommandNode.literal;

/**
 * @author Roj234
 * @since 2024/7/16 5:08
 */
@SimplePlugin(id = "keyStore", desc = "统一密钥管理器，同时提供jar签名和验证功能")
public class KeyStorePlugin extends Plugin {
	private final Map<String, Either<List<Certificate>, PublicKey>> publicKeys = new HashMap<>();
	private final Map<String, PrivateKey> privateKeys = new HashMap<>();

	public Argument<KeyPair> asKeyPair() {return Argument.oneOf(CollectionX.toMap(privateKeys.keySet(), name -> new KeyPair(publicKeys.get(name).map(l -> l.get(0).getPublicKey(), Function.identity()), privateKeys.get(name))));}

	@Override
	protected void onEnable() throws Exception {
		var config = getConfig();
		for (ConfigValue entry : config.getList("keypair")) {
			MapValue map = entry.asMap();
			var alias = map.getString("alias");
			var type = map.getString("type");

			if (type.equals("standard")) {
				loadCertificateAndKeys(IOUtil.resolve(getDataFolder(), map.getString("certificate")), IOUtil.resolve(getDataFolder(), map.getString("privateKey")), alias);
			} else if (type.startsWith("rojlib.")) {
				var password = map.getString("pass");
				KeyPair keyPair = KeyType.getInstance(type.substring(7)).loadKey(password.getBytes(StandardCharsets.UTF_8), IOUtil.resolve(getDataFolder(), map.getString("file")));

				publicKeys.put(alias, Either.right(keyPair.getPublic()));
				privateKeys.put(alias, keyPair.getPrivate());
			}
		}

		if (config.containsKey("defaultMSSKeypair")) {
			String alias = config.getString("defaultMSSKeypair");
			var lr = publicKeys.get(alias);
			var pk = privateKeys.get(alias);
			MSSKeyPair defaultKeypair = (lr.isLeft() ? new MSSKeyPair((X509Certificate) lr.asLeft().get(0), pk) : new MSSKeyPair(lr.asRight(), pk));

			//MSSContext.setDefault();
			getLogger().info("MSS默认证书已加载");
		}

		Command load = ctx -> {
			var pem = ctx.argument("证书pem", File.class);
			var key = ctx.argument("私钥key", File.class);
			var name = ctx.argument("别名", String.class, IOUtil.getBaseName(key.getName()));

			loadCertificateAndKeys(pem, key, name);
		};

		registerCommand(literal("ksload")
			.then(argument("证书pem", Argument.file())
				.then(argument("私钥key", Argument.file()).executes(load)
					.then(argument("别名", Argument.string()).executes(load)))));
		registerCommand(literal("ksunload")
			.then(argument("别名", Argument.oneOf(CollectionX.toMap(publicKeys.keySet()))).executes(ctx -> {
				String name = ctx.argument("别名", String.class);
				publicKeys.remove(name);
				privateKeys.remove(name);
			})));

		List<String> hashAlgs = Arrays.asList("SHA-512", "SHA-384", "SHA-256");

		Command sign = ctx -> {
			var jar = ctx.argument("jar", File.class);
			var alias = ctx.argument("证书别名", String.class);
			var hashAlg = ctx.argument("摘要算法", String.class, "SHA-256");
			var signHashAlg = ctx.argument("签名摘要算法", String.class, "SHA-256");

			var options = new HashMap<String, String>();
			options.put("jarSigner:signatureFileName", alias.toUpperCase(Locale.ROOT));
			options.put("jarSigner:skipPerFileAttributes", "true");
			options.put("jarSigner:manifestHashAlgorithm", hashAlg);
			options.put("jarSigner:signatureHashAlgorithm", signHashAlg);

			getLogger().info("正在签名……");
			try(var zf = new ZipEditor(jar)) {
				JarVerifier.signJar(zf, publicKeys.get(alias).asLeft(), privateKeys.get(alias), options);
				zf.save();
				Tty.success("签名完成");
			}
		};

		registerCommand(literal("jar").then(literal("sign")
			.then(argument("证书别名", Argument.oneOf(CollectionX.toMap(privateKeys.keySet())))
					.then(argument("摘要算法", Argument.suggest(CollectionX.toMap(hashAlgs))).executes(sign)
						.then(argument("签名摘要算法", Argument.suggest(CollectionX.toMap(hashAlgs))).executes(sign))))));
	}

	private void loadCertificateAndKeys(File pem, File key, String name) throws CertificateException, IOException {
		var cf = CertificateFactory.getInstance("X509");
		var certs = new ArrayList<Certificate>();
		try (var in = new BufferedInputStream(new FileInputStream(pem))) {
			while (true) {
				certs.add(cf.generateCertificate(in));

				in.mark(1);
				if (in.read() < 0) break;
				in.reset();
			}
		}

		var prk = (PrivateKey) KeyType.getInstance(certs.get(0).getPublicKey().getAlgorithm()).fromPEM(IOUtil.readString(key));
		publicKeys.put(name, Either.left(certs));
		privateKeys.put(name, prk);
	}
}
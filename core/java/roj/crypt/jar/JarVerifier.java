package roj.crypt.jar;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.archive.ArchiveEntry;
import roj.archive.ArchiveFile;
import roj.archive.zip.ZipEditor;
import roj.archive.zip.ZipEntry;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.crypt.Base64;
import roj.crypt.CryptoFactory;
import roj.crypt.asn1.KnownOID;
import roj.io.IOUtil;
import roj.io.source.Source;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author Roj234
 * @since 2024/3/22 19:44
 */
public class JarVerifier {
	private static final String CREATED_BY = "ImpLib/JarSigner (v1.5)";
	private static final HashSet<String> VALID_CERTIFICATE_EXTENSION = new HashSet<>("rsa", "dsa", "ec");
	private static final List<String> SECURE_HASH_ALGORITHMS = Arrays.asList("SHA-512", "SHA-384", "SHA-256");
	private static final ThreadLocal<Map<String, MessageDigest>> DIGESTS = new ThreadLocal<>();

	public static final int TRUST_LEVEL_INVALID_SIGN = -1, TRUST_LEVEL_UNSIGNED = 0, TRUST_LEVEL_INVALID_USAGE = 1, TRUST_LEVEL_SIGNED = 2, TRUST_LEVEL_VERIFIED = 3;
	private static final String[] TRUST_LEVEL_NAMES = {
			"TRUST_LEVEL_INVALID_SIGN",
			"TRUST_LEVEL_UNSIGNED",
			"TRUST_LEVEL_INVALID_USAGE",
			"TRUST_LEVEL_SIGNED",
			"TRUST_LEVEL_VERIFIED"
	};
	public static String getTrustLevelName(int level) {return TRUST_LEVEL_NAMES[level+1];}

	private byte trustLevel;
	private final URL base;
	private Manifest manifest;

	private final SignatureBlock block;
	private transient ManifestBytes manifestBytes;
	private transient byte[] signatureBytes;

	private Map<String, byte[]> hashes;
	private String algorithm;
	private CodeSource source;

	JarVerifier(URL url, Manifest manifest, ManifestBytes manifestBytes, byte[] signatureBytes, SignatureBlock block) {
		this.base = url;
		this.manifest = manifest;
		this.manifestBytes = manifestBytes;
		this.signatureBytes = signatureBytes;
		this.block = block;
	}

	@MagicConstant(intValues = {
			TRUST_LEVEL_INVALID_SIGN,
			TRUST_LEVEL_UNSIGNED,
			TRUST_LEVEL_INVALID_USAGE,
			TRUST_LEVEL_SIGNED,
			TRUST_LEVEL_VERIFIED
	})
	public int getTrustLevel() {
		if (trustLevel == 0) {
			trustLevel = computeTrustLevel();
		}
		return trustLevel;
	}
	private byte computeTrustLevel() {
		if (block == null) {
			algorithm = "SHA-256";
			source = new CodeSource(base, (CodeSigner[]) null);
			hashes = Collections.emptyMap();

			return TRUST_LEVEL_UNSIGNED;
		}

		try {
			ensureManifestValid();
		} catch (Exception e) {
			e.printStackTrace();
			return TRUST_LEVEL_INVALID_SIGN;
		}

		List<X509Certificate> certs = block.getCertificates();
		try {
			if (!block.getSigner().getExtendedKeyUsage().contains(KnownOID.codeSigning.toString())) {
				return TRUST_LEVEL_INVALID_USAGE;
			}

			CryptoFactory.checkCertificateValidity(certs.toArray(new X509Certificate[certs.size()]));
			return TRUST_LEVEL_VERIFIED;
		} catch (Exception e) {
			return TRUST_LEVEL_SIGNED;
		}
	}
	public String getAlgorithm() {return block == null ? null : block.getSignatureAlg();}

	private void ensureManifestValid() throws GeneralSecurityException {
		Signature sign = Signature.getInstance(block.getSignatureAlg());
		sign.initVerify(block.getSigner().getPublicKey());
		sign.update(signatureBytes);
		if (!sign.verify(block.getSignature())) throw new SecurityException("签名清单校验失败");

		try {
			verifyHashAlgorithm(new ManifestBytes(signatureBytes));
		} catch (IOException e) {
			throw new SecurityException("签名清单格式错误", e);
		}
		signatureBytes = null;

		Map<String, MessageDigest> digests = DIGESTS.get();
		if (digests == null) digests = new HashMap<>();

		MessageDigest digest = digests.get(algorithm);
		if (digest == null) digests.put(algorithm, digest = MessageDigest.getInstance(algorithm));

		Attributes main = manifest.getMainAttributes();

		String digestStr = main.getValue(algorithm.concat("-Digest-Manifest"));
		if (digestStr != null) {
			byte[] computed = digest.digest(manifestBytes.data);
			byte[] except = Base64.decode(digestStr, IOUtil.getSharedByteBuf()).toByteArray();

			if (!MessageDigest.isEqual(except, computed)) throw new SecurityException("清单校验失败");
		} else {
			throw new SecurityException("找不到清单哈希");
		}

		digestStr = main.getValue(algorithm.concat("-Digest-Manifest-Main-Attributes"));
		if (digestStr != null) {
			byte[] computed = manifestBytes.digest(digest);
			byte[] except = Base64.decode(digestStr, IOUtil.getSharedByteBuf()).toByteArray();

			if (!MessageDigest.isEqual(except, computed)) throw new SecurityException("清单主属性校验失败");
		} else {
			throw new SecurityException("找不到清单主属性哈希");
		}

		for (Map.Entry<String, byte[]> entry : hashes.entrySet()) {
			byte[] computed = manifestBytes.digest(digest, entry.getKey());
			if (!MessageDigest.isEqual(entry.getValue(), computed)) throw new SecurityException("清单"+entry.getKey()+"属性校验失败");
		}

		try {
			verifyHashAlgorithm(manifestBytes);
		} catch (IOException e) {
			throw new SecurityException("清单格式错误", e);
		}
		manifestBytes = null;

		source = new CodeSource(base, new CodeSigner[]{new CodeSigner(block, block.getTimestamp())});
		DIGESTS.set(digests);
	}
	private void verifyHashAlgorithm(ManifestBytes manifestBytes) throws IOException {
		this.hashes = new HashMap<>(manifestBytes.namedAttributes.size());

		HashMap<String, String> tmpMap = new HashMap<>();
		String prevName = null, algorithm = null;
		for (var attribute : manifestBytes.namedAttributes.entrySet()) {
			tmpMap.clear();
			for (ManifestBytes.Entry section : attribute.getValue()) {
				section.getLines(manifestBytes.data, tmpMap);
			}

			String encodedHash = tmpMap.get(prevName);
			foundValidHash:
			if (encodedHash == null) {
				for (int i = 0; i < SECURE_HASH_ALGORITHMS.size(); i++) {
					algorithm = SECURE_HASH_ALGORITHMS.get(i);
					encodedHash = tmpMap.get(prevName = algorithm.concat("-Digest"));
					if (encodedHash != null) break foundValidHash;
				}
				throw new IOException("无法验证清单属性"+attribute.getKey()+"="+tmpMap+": "+(algorithm == null ? "不安全的校验码" : "仅部分文件存在"+algorithm+"校验"));
			}

			byte[] hash = Base64.decode(encodedHash, IOUtil.getSharedByteBuf()).toByteArray();
			hashes.put(attribute.getKey(), hash);
		}

		// 只读取主属性
		this.manifest = new Manifest(new ByteArrayInputStream(manifestBytes.data, 0, manifestBytes.mainAttribute.endOfSection+3));
		this.algorithm = algorithm;
	}

	public Manifest getManifest() { return manifest; }
	public CodeSource getCodeSource() { return source; }

	private MessageDigest digester() {
		Map<String, MessageDigest> map = DIGESTS.get();
		if (map == null) DIGESTS.set(map = new HashMap<>());

		MessageDigest digester = map.remove(algorithm);
		try {
			if (digester == null) digester = MessageDigest.getInstance(algorithm);
			else digester.reset();
		} catch (NoSuchAlgorithmException ignored) {}
		return digester;
	}

	public InputStream wrapInput(String name, InputStream in) {
		byte[] hash = hashes.get(name);
		if (hash == null) return in;

		MessageDigest digester = digester();
		return new DigestInputStream(in, digester, hash, name);
	}

	public void verify(String name, ByteList buf) {
		byte[] hash = hashes.get(name);
		if (hash == null) return;

		MessageDigest digester = digester();

		digester.update(buf.list, buf.arrayOffset()+buf.rIndex, buf.readableBytes());
		byte[] digest = digester.digest();

		DIGESTS.get().put(algorithm, digester);

		if (!MessageDigest.isEqual(hash, digest)) {
			throw new SecurityException(name+"的"+algorithm+"校验失败");
		}
	}

	private static final class DigestInputStream extends InputStream {
		private final InputStream in;
		private MessageDigest md;
		private final byte[] hash;
		private final String name;

		public DigestInputStream(InputStream in, MessageDigest md, byte[] hash, String name) {
			this.in = in;
			this.md = md;
			this.hash = hash;
			this.name = name;
		}

		static void init() {}

		public int available() throws IOException { return in.available(); }

		public void close() throws IOException { in.close(); }

		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) md.update((byte) b);
			else check();
			return b;
		}

		public int read(@NotNull byte[] b, int off, int len) throws IOException {
			len = in.read(b, off, len);
			if (len >= 0) md.update(b, off, len);
			else check();
			return len;
		}

		private synchronized void check() {
			if (md == null) return;

			var digest = md;
			byte[] result = digest.digest();
			md = null;
			DIGESTS.get().put(digest.getAlgorithm(), digest);

			if (!MessageDigest.isEqual(result, hash)) {
				throw new SecurityException(name+"的"+digest.getAlgorithm()+"校验失败");
			}
		}
	}

	// classloading hook
	static {DigestInputStream.init();}
	@Nullable
	public static <T extends ArchiveEntry> JarVerifier create(ArchiveFile<T> zf, File source) throws IOException {
		InputStream mfin = null;

		byte[] signfile = null;
		SignatureBlock signatureBlock = null;

		for (var entry : zf.entries()) {
			String name = entry.getName();
			if (!name.startsWith("META-INF/") || name.lastIndexOf('/') != 8) continue;

			String extName;

			if (name.equals("META-INF/MANIFEST.MF")) {
				// found more than one manifest
				if (mfin != null) {
					mfin.close();
					return null;
				}

				mfin = zf.getInputStream(entry);
			}

			if (signfile == null && VALID_CERTIFICATE_EXTENSION.contains(extName = IOUtil.getExtension(name))) {
				try {
					var in = zf.getInputStream(name.substring(0, name.length() - extName.length())+"SF");
					if (in != null) {
						signfile = IOUtil.read(in);
						signatureBlock = new SignatureBlock(zf.getInputStream(entry));
					}
				} catch (CertificateException e) {
					throw new IOException(e);
				}
			}
		}

		Manifest manifest = null;
		ManifestBytes mb = null;

		if (mfin != null) {
			if (signfile != null) mb = new ManifestBytes(mfin);
			else manifest = new Manifest(mfin);

			IOUtil.closeSilently(mfin);
		} else {
			signfile = null;
			signatureBlock = null;
		}

		URL url = source != null ? new URL("file", "", "/"+source.getAbsolutePath().replace(File.separatorChar, '/')) : null;
		return new JarVerifier(url, manifest, mb, signfile, signatureBlock);
	}

	/**
	 * 一键对jar签名
	 * @param zf 准备签名的文件
	 * @param certs 证书链
	 * @param prk 私钥，对应第一个证书
	 * @param options 选项  jarSigner:signatureHashAlgorithm jarSigner:manifestHashAlgorithm jarSigner:skipPerFileAttributes jarSigner:signatureFileName jarSigner:cacheHash jarSigner:creator
	 */
	public static void signJar(ZipEditor zf, List<Certificate> certs, PrivateKey prk, Map<String, String> options) throws GeneralSecurityException, IOException {
		String keyAlg = certs.get(0).getPublicKey().getAlgorithm();
		var digestAlg = options.getOrDefault("jarSigner:signatureHashAlgorithm", "SHA-256");

		String signatureAlgorithm = KnownOID.getSignatureAlgorithm(digestAlg, keyAlg);
		var signer = Signature.getInstance(signatureAlgorithm);

		var hashAlg = options.getOrDefault("jarSigner:manifestHashAlgorithm", "SHA-256");
		if (!VALID_CERTIFICATE_EXTENSION.contains(keyAlg.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("不支持的数字签名类型:"+keyAlg);
		if (!SECURE_HASH_ALGORITHMS.contains(hashAlg) || !SECURE_HASH_ALGORITHMS.contains(digestAlg)) throw new IllegalArgumentException("不支持的哈希函数类型:"+options);

		var md = MessageDigest.getInstance(hashAlg);

		Manifest mf;
		var mfin = zf.getInputStream("META-INF/MANIFEST.MF");
		if (mfin != null) {
			mf = new Manifest(mfin);
			IOUtil.closeSilently(mfin);
		} else {
			mf = new Manifest();
			mf.getMainAttributes().putValue("Manifest-Version", "1.0");
		}

		var cacheHash = "true".equals(options.get("jarSigner:cacheHash"));

		byte[] buf = ArrayCache.getIOBuffer();
		var digestKey = new Attributes.Name(hashAlg+"-Digest");
		for (ZipEntry entry : zf.entries()) {
			if (entry.getName().startsWith("META-INF/")) {
				if (entry.getName().equals("META-INF/MANIFEST.MF")) continue;
				if (VALID_CERTIFICATE_EXTENSION.contains(IOUtil.getExtension(entry.getName()))) {
					String oldSfName = "META-INF/"+IOUtil.getBaseName(entry.getName())+".SF";
					zf.put(entry.getName(), null);
					zf.put(oldSfName, null);
					mf.getEntries().remove(entry.getName());
					mf.getEntries().remove(oldSfName);
					continue;
				}
				if (entry.getName().endsWith(".SF")) continue;
			}

			var subattr = mf.getAttributes(entry.getName());
			if (subattr == null) {
				subattr = new Attributes(1);
				mf.getEntries().put(entry.getName(), subattr);
			}

			if (cacheHash) {
				var prevHash = options.get(digestKey+":"+entry.getName());
				if (prevHash != null) {
					int endIndex = prevHash.indexOf('|');
					int time = Integer.parseInt(prevHash.substring(0, endIndex), 36);
					if (time == ((int)(entry.getModificationTime() * entry.getCrc32()))) {
						subattr.put(digestKey, prevHash.substring(endIndex+1));
						continue;
					}
				}
			}

			try (var in = zf.getInputStream(entry)) {
				while (true) {
					int r = in.read(buf);
					if (r < 0) break;
					md.update(buf, 0, r);
				}

				var digest = Base64.encode(DynByteBuf.wrap(md.digest()), IOUtil.getSharedCharBuf()).toString();
				subattr.put(digestKey, digest);
				if (cacheHash) {
					options.put(digestKey+":"+entry.getName(), Integer.toString((int)(entry.getModificationTime() * entry.getCrc32()), 36)+"|"+digest);
				}
			} catch (Exception e) {
				throw new IOException("Exception reading "+entry.getName(), e);
			}
		}

		var ob = IOUtil.getSharedByteBuf();
		mf.write(ob);
		byte[] mfBytes = ob.toByteArray();
		zf.put("META-INF/MANIFEST.MF", new ByteList(mfBytes));

		var mb = new ManifestBytes(mfBytes);

		ob.clear();
		ob.putAscii("Signature-Version: 1.0\r\n");
		append72(ob, "Created-By: "+options.getOrDefault("jarSigner:creator", CREATED_BY));
		append72(ob, hashAlg+"-Digest-Manifest: "+IOUtil.encodeBase64(md.digest(mfBytes)));
		append72(ob, hashAlg+"-Digest-Manifest-Main-Attributes: "+IOUtil.encodeBase64(mb.digest(md)));

		if (options.getOrDefault("jarSigner:addV2", "false").equals("true")) {
			Source file = zf.source();
			file.seek(0);
			var in = file.asInputStream();
			while (true) {
				int r = in.read(buf);
				if (r < 0) break;
				md.update(buf, 0, r);
			}

			// raw data without MANIFEST / SIGNFILE / CERTIFICATE
			append72(ob, hashAlg+"-Digest-Archive: "+IOUtil.encodeBase64(md.digest()));
		}
		ArrayCache.putArray(buf);
		ob.putAscii("\r\n");

		for (String name : mb.namedAttributes.keySet()) {
			append72(ob, "Name: "+name);
			append72(ob, digestKey+": "+IOUtil.encodeBase64(mb.digest(md, name)));
			ob.putAscii("\r\n");
		}

		var sfName = options.getOrDefault("jarSigner:signatureFileName", "SIGNFILE");

		byte[] sfBytes = ob.toByteArray();
		zf.put("META-INF/"+sfName+".SF", DynByteBuf.wrap(sfBytes));

		signer.initSign(prk);
		signer.update(sfBytes);
		byte[] signature = signer.sign();

		var sb = new SignatureBlock(certs, signature, KnownOID.getDigestAlgorithm(digestAlg), signatureAlgorithm, 0, null);
		zf.put("META-INF/"+sfName+"."+keyAlg, DynByteBuf.wrap(sb.getEncoded("PKCS7")));
	}

	private static void append72(DynByteBuf out, String line) {
		if (!line.isEmpty()) {
			var tmp = new ByteList().putUTFData(line);

			var lineBytes = tmp.list;
			int length = tmp.wIndex();
			// first line can hold one byte more than subsequent lines which
			// start with a continuation line break space
			out.write(lineBytes[0]);
			int pos = 1;
			while (length - pos > 71) {
				out.write(lineBytes, pos, 71);
				pos += 71;
				out.putAscii("\r\n");
				out.write(' ');
			}
			out.write(lineBytes, pos, length - pos);
		}
		out.putAscii("\r\n");
	}
}
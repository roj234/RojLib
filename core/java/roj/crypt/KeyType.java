package roj.crypt;

import roj.io.IOUtil;
import roj.text.CharList;
import roj.text.LineReader;
import roj.util.ByteList;
import roj.util.Helpers;
import roj.util.OperationDone;

import java.io.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * @author Roj233
 * @since 2022/2/17 19:23
 */
public class KeyType {
	private final KeyPairGenerator GEN;
	private final KeyFactory FACTORY;

	public static KeyType getInstance(String alg) {
		try {
			CryptoFactory.register();
			return new KeyType(alg);
		} catch (NoSuchAlgorithmException e) {
			Helpers.athrow(e);
			throw OperationDone.NEVER;
		}
	}

	public KeyType(String alg) throws NoSuchAlgorithmException {
		GEN = KeyPairGenerator.getInstance(alg);
		FACTORY = KeyFactory.getInstance(alg);
	}

	public KeyType setKeySize(int size) {
		GEN.initialize(size);
		return this;
	}

	public KeyPair loadOrGenerateKey(File pri, byte[] pass) throws GeneralSecurityException {
		KeyPair kp;
		try {
			if (!pri.isFile()) {
				kp = generateKey();
				saveKey(kp, pass, pri);
				return kp;
			} else {
				return loadKey(pass, pri);
			}
		} catch (IOException e) {
			return null;
		}
	}

	public KeyPair generateKey() { return GEN.generateKeyPair(); }
	public void saveKey(KeyPair kp, byte[] pass, File f) throws IOException, GeneralSecurityException {
		try (OutputStream out = new FileOutputStream(f)) {
			saveKey(kp, pass, out);
		}
	}
	public KeyPair loadKey(byte[] pass, File f) throws IOException {
		try (InputStream in = new FileInputStream(f)) {
			return loadKey(in, pass);
		} catch (Exception e) {
			Helpers.athrow(e);
			throw OperationDone.NEVER;
		}
	}
	public void saveKey(KeyPair kp, byte[] pass, OutputStream cos) throws IOException, GeneralSecurityException {
		if (pass != null) {
			byte[] iv = SecureRandom.getSeed(16);
			cos.write(iv);

			pass = CryptoFactory.HKDF_HmacSha256(pass, null, 32);
			var c = RCipher.getInstance("AES/CTR/NoPadding");

			c.init(true, pass, new IvParameterSpecNC(iv), null);
			cos = new CipherOutputStream(cos, c);
		}

		byte[] b;
		if (!kp.getPrivate().getFormat().equals("PKCS#8")) {
			b = FACTORY.getKeySpec(kp.getPrivate(), PKCS8EncodedKeySpec.class).getEncoded();
		} else {
			b = kp.getPrivate().getEncoded();
		}
		cos.write(b.length >>> 8);
		cos.write(b.length);
		cos.write(b);

		if (!(kp.getPrivate() instanceof DerivablePrivateKey)) {
			if (!kp.getPublic().getFormat().equals("X.509")) {
				b = FACTORY.getKeySpec(kp.getPublic(), X509EncodedKeySpec.class).getEncoded();
			} else {
				b = kp.getPublic().getEncoded();
			}
			cos.write(b.length >>> 8);
			cos.write(b.length);
			cos.write(b);
		}

		cos.close();
	}
	public KeyPair loadKey(InputStream cis, byte[] pass) throws IOException, GeneralSecurityException {
		if (pass != null) {
			byte[] iv = new byte[16];
			IOUtil.readFully(cis, iv);

			pass = CryptoFactory.HKDF_HmacSha256(pass, null, 32);
			var c = RCipher.getInstance("AES/CTR/NoPadding");
			c.init(false, pass, new IvParameterSpecNC(iv), null);
			cis = new CipherInputStream(cis, c);
		}

		int len = (cis.read() << 8) | cis.read();
		byte[] key = new byte[len];
		IOUtil.readFully(cis, key);
		PrivateKey pri = FACTORY.generatePrivate(new PKCS8EncodedKeySpec(key));

		len = (cis.read() << 8) | cis.read();
		PublicKey pub;
		if (len < 0) {
			// interface : DerivablePrivateKey
			pub = ((DerivablePrivateKey) pri).generatePublic();
		} else {
			key = new byte[len];
			IOUtil.readFully(cis, key);
			pub = FACTORY.generatePublic(new X509EncodedKeySpec(key));
		}

		cis.close();
		return new KeyPair(pub, pri);
	}
	public String toKEY(PrivateKey pk) {
		byte[] b;
		if (!pk.getFormat().equals("PKCS#8")) {
			try {
				b = FACTORY.getKeySpec(pk, PKCS8EncodedKeySpec.class).getEncoded();
			} catch (Exception e) {
				return null;
			}
		} else {
			b = pk.getEncoded();
		}
		return Base64.encode(new ByteList(b), IOUtil.getSharedCharBuf().append("-----BEGIN PRIVATE KEY-----\n")).append("\n-----END PRIVATE KEY-----").toString();
	}
	public String toPEM(PublicKey pk) {
		byte[] b;
		if (!pk.getFormat().equals("X.509")) {
			try {
				b = FACTORY.getKeySpec(pk, X509EncodedKeySpec.class).getEncoded();
			} catch (Exception e) {
				return null;
			}
		} else {
			b = pk.getEncoded();
		}
		return Base64.encode(new ByteList(b), IOUtil.getSharedCharBuf().append("-----BEGIN PUBLIC KEY-----\n")).append("\n-----END PUBLIC KEY-----").toString();
	}

	public Key fromPEM(String s) {
		LineReader.Impl lr = LineReader.create(s, true);
		String s1 = lr.readLine();

		ByteList out = new ByteList();
		CharList sb = IOUtil.getSharedCharBuf();
		while (true) {
			if (!lr.readLine(sb)) return null;
			if (sb.startsWith("-----")) break;
			Base64.decode(sb, out);
		}

		try {
			if (s1.equals("-----BEGIN PUBLIC KEY-----") && sb.equals("-----END PUBLIC KEY-----")) {
				return FACTORY.generatePublic(new X509EncodedKeySpec(out.toByteArrayAndZero()));
			} else if (s1.equals("-----BEGIN PRIVATE KEY-----") && sb.equals("-----END PRIVATE KEY-----")) {
				return FACTORY.generatePrivate(new PKCS8EncodedKeySpec(out.toByteArrayAndZero()));
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			out.release();
		}
		return null;
	}
}
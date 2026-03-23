package roj.crypt.cert;

import org.jetbrains.annotations.Nullable;
import roj.collect.ArrayList;
import roj.collect.LinkedHashMap;
import roj.config.node.ByteArrayValue;
import roj.config.node.ConfigValue;
import roj.config.node.MapValue;
import roj.crypt.asn1.DerReader;
import roj.crypt.asn1.DerValue;
import roj.crypt.asn1.DerWriter;
import roj.crypt.asn1.KnownOID;
import roj.text.CharList;
import roj.util.ByteList;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * @author Roj234
 * @since 2025/09/07 08:45
 */
public final class CertInfo {
	public final KeyPair key;
	public @Nullable BigInteger serialNumber;
	public LinkedHashMap<KnownOID, String> DN = new LinkedHashMap<>();
	public String signatureAlgorithm = "SHA256";
	public long notBefore = System.currentTimeMillis();
	public long notAfter = notBefore + 3650 * 86400000L;
	public List<CertExtension> extensions = new ArrayList<>();

	public CertInfo(Certificate certificate, PrivateKey privateKey) {
		X509Certificate cer = (X509Certificate) certificate;

		// 这是唯一需要Java Security API的地方，因为我的KnownOID没写那么多
		key = new KeyPair(certificate.getPublicKey(), privateKey);
		signatureAlgorithm = cer.getSigAlgName();

		// 后面就和那些傻逼的opaque API无关了，除非你喜欢Unsafe
		try {
			var dr = new DerReader(new ByteList(cer.getEncoded()));
			var cert = ASN1CTX.CTX.parse("Certificate", dr).query("tbsCertificate");

			serialNumber = ((DerValue.Int) cert.query("serialNumber")).value;

			notBefore = DerWriter.GENERALIZED_TIME.parse(new CharList(cert.query("validity.notBefore").asString()));
			notAfter = DerWriter.GENERALIZED_TIME.parse(new CharList(cert.query("validity.notAfter").asString()));

			for (ConfigValue rdn : cert.query("subject").asList()) {
				MapValue attr = rdn.asList().get(0).asMap();
				var oid = attr.get("type");
				DN.put(KnownOID.valueOf(oid), attr.getString("value"));
			}

			for (ConfigValue extension : cert.query("extensions").asList()) {
				if (extension.query("extnID").equals(KnownOID.SubjectKeyID.oid)) {
					this.extensions.add(new CertExtension(KnownOID.SubjectKeyID, false, ((ByteArrayValue) extension.query("extnValue")).value));
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("证书解析失败，对于来自参数的合法证书对象，这不应该发生", e);
		}
	}

	// 证书里一定要有密钥，不是么
	public CertInfo(KeyPair key) {this.key = key;}

	public void setCommonName(String commonName) {DN.put(KnownOID.CommonName, commonName);}

	public void setupCA() {
		extensions.add(CertExtension.basicConstraints(true, -1));
		extensions.add(CertExtension.keyUsage(CertExtension.digitalSignature | CertExtension.cRLSign | CertExtension.keyCertSign));
		extensions.add(CertExtension.extendedKeyUsage(KnownOID.serverAuth, KnownOID.clientAuth));
	}

	public void setupCodeSigning() {
		extensions.add(CertExtension.basicConstraints(false, -1));
		extensions.add(CertExtension.keyUsage(CertExtension.digitalSignature));
		extensions.add(CertExtension.extendedKeyUsage(KnownOID.codeSigning));
	}

	public void setupTLS(String... domains) {
		extensions.add(CertExtension.basicConstraints(false, -1));
		extensions.add(CertExtension.keyUsage(CertExtension.digitalSignature));
		extensions.add(CertExtension.extendedKeyUsage(KnownOID.serverAuth, KnownOID.clientAuth));
		if (domains.length > 0) extensions.add(CertExtension.subjectAltName(domains));
	}
}

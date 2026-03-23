package roj.crypt.asn1;

import org.jetbrains.annotations.NotNull;
import roj.collect.HashMap;
import roj.config.node.ConfigValue;
import roj.config.node.IntArrayValue;
import roj.reflect.Reflection;
import roj.text.TextUtil;

import java.util.Map;

/**
 * @author Roj234
 * @since 2025/09/07 05:51
 */
public enum KnownOID {
	// X.500 Attributes 2.5.4.*
	CommonName("2.5.4.3", "DN"),
	Surname("2.5.4.4", "DN"),
	SerialNumber("2.5.4.5", "DN"),
	CountryName("2.5.4.6", "DN"),
	LocalityName("2.5.4.7", "DN"),
	StateName("2.5.4.8", "DN"),
	StreetAddress("2.5.4.9", "DN"),
	OrgName("2.5.4.10", "DN"),
	OrgUnitName("2.5.4.11", "DN"),
	Title("2.5.4.12", "DN"),
	GivenName("2.5.4.42", "DN"),
	Initials("2.5.4.43", "DN"),
	GenerationQualifier("2.5.4.44", "DN"),
	DNQualifier("2.5.4.46", "DN"),

	// Certificate Extension 2.5.29.*
	SubjectDirectoryAttributes("2.5.29.9", "CertExt"),
	SubjectKeyID("2.5.29.14", "CertExt"),
	KeyUsage("2.5.29.15", "CertExt"),
	PrivateKeyUsage("2.5.29.16", "CertExt"),
	SubjectAlternativeName("2.5.29.17", "CertExt"),
	IssuerAlternativeName("2.5.29.18", "CertExt"),
	BasicConstraints("2.5.29.19", "CertExt"),
	CRLNumber("2.5.29.20", "CertExt"),
	ReasonCode("2.5.29.21", "CertExt"),
	HoldInstructionCode("2.5.29.23", "CertExt"),
	InvalidityDate("2.5.29.24", "CertExt"),
	DeltaCRLIndicator("2.5.29.27", "CertExt"),
	IssuingDistributionPoint("2.5.29.28", "CertExt"),
	CertificateIssuer("2.5.29.29", "CertExt"),
	NameConstraints("2.5.29.30", "CertExt"),
	CRLDistributionPoints("2.5.29.31", "CertExt"),
	CertificatePolicies("2.5.29.32", "CertExt"),
	CE_CERT_POLICIES_ANY("2.5.29.32.0", "CertExt"),
	PolicyMappings("2.5.29.33", "CertExt"),
	AuthorityKeyID("2.5.29.35", "CertExt"),
	PolicyConstraints("2.5.29.36", "CertExt"),
	extendedKeyUsage("2.5.29.37", "CertExt"),
	anyExtendedKeyUsage("2.5.29.37.0", "CertExt"),
	FreshestCRL("2.5.29.46", "CertExt"),
	InhibitAnyPolicy("2.5.29.54", "CertExt"),

	serverAuth("1.3.6.1.5.5.7.3.1", "extendedKeyUsage"),
	clientAuth("1.3.6.1.5.5.7.3.2", "extendedKeyUsage"),
	codeSigning("1.3.6.1.5.5.7.3.3", "extendedKeyUsage"),
	emailProtection("1.3.6.1.5.5.7.3.4", "extendedKeyUsage"),
	timeStamping("1.3.6.1.5.5.7.3.8", "extendedKeyUsage"),
	OCSPSigning("1.3.6.1.5.5.7.3.9", "extendedKeyUsage"),

	// digestAlgs 1.2.840.113549.2.*

	@Deprecated
	MD5("1.2.840.113549.2.5", "digestAlg"),
	HmacSHA1("1.2.840.113549.2.7", "digestAlg"),
	HmacSHA224("1.2.840.113549.2.8", "digestAlg"),
	HmacSHA256("1.2.840.113549.2.9", "digestAlg"),
	HmacSHA384("1.2.840.113549.2.10", "digestAlg"),
	HmacSHA512("1.2.840.113549.2.11", "digestAlg"),
	HmacSHA512$224("1.2.840.113549.2.12", "digestAlg"),
	HmacSHA512$256("1.2.840.113549.2.13", "digestAlg"),

	// hashAlgs 2.16.840.1.101.3.4.2.*
	SHA256("2.16.840.1.101.3.4.2.1", "hashAlg"),
	SHA384("2.16.840.1.101.3.4.2.2", "hashAlg"),
	SHA512("2.16.840.1.101.3.4.2.3", "hashAlg"),
	SHA224("2.16.840.1.101.3.4.2.4", "hashAlg"),
	SHA512$224("2.16.840.1.101.3.4.2.5", "hashAlg"),
	SHA512$256("2.16.840.1.101.3.4.2.6", "hashAlg"),
	SHA3_224("2.16.840.1.101.3.4.2.7", "hashAlg"),
	SHA3_256("2.16.840.1.101.3.4.2.8", "hashAlg"),
	SHA3_384("2.16.840.1.101.3.4.2.9", "hashAlg"),
	SHA3_512("2.16.840.1.101.3.4.2.10", "hashAlg"),
	SHAKE128("2.16.840.1.101.3.4.2.11", "hashAlg"),
	SHAKE256("2.16.840.1.101.3.4.2.12", "hashAlg"),
	HmacSHA3_224("2.16.840.1.101.3.4.2.13", "hashAlg"),
	HmacSHA3_256("2.16.840.1.101.3.4.2.14", "hashAlg"),
	HmacSHA3_384("2.16.840.1.101.3.4.2.15", "hashAlg"),
	HmacSHA3_512("2.16.840.1.101.3.4.2.16", "hashAlg"),
	SHAKE128_LEN("2.16.840.1.101.3.4.2.17", "hashAlg"),
	SHAKE256_LEN("2.16.840.1.101.3.4.2.18", "hashAlg"),


	RSA("1.2.840.113549.1.1.1"),
	DSA("1.2.840.10040.4.1"),
	EC("1.2.840.10045.2.1"),
	OAEP("1.2.840.113549.1.1.7"),
	RSASSA_PSS("1.2.840.113549.1.1.10", "signType"),
	X25519("1.3.101.110"),
	X448("1.3.101.111"),

	Ed25519("1.3.101.112", "signAlg"),
	Ed448("1.3.101.113", "signAlg"),

	SHA1withRSA("1.2.840.113549.1.1.5", "signAlg"),
	SHA256withRSA("1.2.840.113549.1.1.11", "signAlg"),
	SHA384withRSA("1.2.840.113549.1.1.12", "signAlg"),
	SHA512withRSA("1.2.840.113549.1.1.13", "signAlg"),
	SHA224withRSA("1.2.840.113549.1.1.14", "signAlg"),
	SHA512$224withRSA("1.2.840.113549.1.1.15", "signAlg"),
	SHA512$256withRSA("1.2.840.113549.1.1.16", "signAlg"),
	SHA3_224withRSA("2.16.840.1.101.3.4.3.13", "signAlg"),
	SHA3_256withRSA("2.16.840.1.101.3.4.3.14", "signAlg"),
	SHA3_384withRSA("2.16.840.1.101.3.4.3.15", "signAlg"),
	SHA3_512withRSA("2.16.840.1.101.3.4.3.16", "signAlg"),

	SHA1withDSA("1.2.840.10040.4.3", "signAlg"),
	SHA224withDSA("2.16.840.1.101.3.4.3.1", "signAlg"),
	SHA256withDSA("2.16.840.1.101.3.4.3.2", "signAlg"),
	SHA384withDSA("2.16.840.1.101.3.4.3.3", "signAlg"),
	SHA512withDSA("2.16.840.1.101.3.4.3.4", "signAlg"),
	SHA3_224withDSA("2.16.840.1.101.3.4.3.5", "signAlg"),
	SHA3_256withDSA("2.16.840.1.101.3.4.3.6", "signAlg"),
	SHA3_384withDSA("2.16.840.1.101.3.4.3.7", "signAlg"),
	SHA3_512withDSA("2.16.840.1.101.3.4.3.8", "signAlg"),

	SHA1withECDSA("1.2.840.10045.4.1", "signAlg"),
	SHA224withECDSA("1.2.840.10045.4.3.1", "signAlg"),
	SHA256withECDSA("1.2.840.10045.4.3.2", "signAlg"),
	SHA384withECDSA("1.2.840.10045.4.3.3", "signAlg"),
	SHA512withECDSA("1.2.840.10045.4.3.4", "signAlg"),
	SHA3_224withECDSA("2.16.840.1.101.3.4.3.9", "signAlg"),
	SHA3_256withECDSA("2.16.840.1.101.3.4.3.10", "signAlg"),
	SHA3_384withECDSA("2.16.840.1.101.3.4.3.11", "signAlg"),
	SHA3_512withECDSA("2.16.840.1.101.3.4.3.12", "signAlg"),

	;

	public final IntArrayValue oid;
	public final String type;

	KnownOID(String oid, String type) {
		this.oid = DerValue.OID(oid);
		this.type = type;
	}
	KnownOID(String oid) {this(oid, "");}

	public String getOIDString() {return TextUtil.join(oid, ".");}

	public static String getSignatureAlgorithm(String digestAlgorithm, String keyAlgorithm) {
		digestAlgorithm = getDigestAlgorithm(digestAlgorithm);
		return valueOf(digestAlgorithm).type.equals("signAlg")
				? digestAlgorithm
				: digestAlgorithm+"with"+(keyAlgorithm.equals("EC")?"ECDSA":keyAlgorithm);
	}

	public static String getDigestAlgorithm(String digestAlgorithm) {
		if (Indices.byName.containsKey(digestAlgorithm)) return digestAlgorithm;
		var replace = digestAlgorithm.replace("-", "");
		if (Indices.byName.containsKey(replace)) return replace;
		replace = digestAlgorithm.replace("-", "_");
		if (Indices.byName.containsKey(replace)) return replace;
		throw new IllegalArgumentException("No such digest algorithm "+digestAlgorithm);
	}

	private static final class Indices {
		static final Map<ConfigValue, KnownOID> byOid = new HashMap<>();
		static final Map<String, KnownOID> byName = Reflection.enumConstantDirectory(KnownOID.class);
		static {
			for (KnownOID value : values()) {
				byOid.put(value.oid, value);
			}
		}
	}

	@NotNull
	public static KnownOID valueOf(ConfigValue query) {
		KnownOID oid = Indices.byOid.get(query);
		if (oid == null) throw new IllegalArgumentException("找不到已知名称"+query);
		return oid;
	}

	public KnownOID assertType(String group) {
		if (!type.startsWith(group)) throw new IllegalArgumentException("要求类型"+group+",而不是当前的"+this+"("+type+")");
		return this;
	}
}

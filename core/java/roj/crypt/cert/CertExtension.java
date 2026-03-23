package roj.crypt.cert;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.Unmodifiable;
import roj.config.node.IntArrayValue;
import roj.crypt.asn1.DerValue;
import roj.crypt.asn1.DerWriter;
import roj.crypt.asn1.KnownOID;
import roj.util.Helpers;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Extension;
import java.util.regex.Pattern;

/**
 * @author Roj234
 * @since 2025/09/07 08:43
 */
public final class CertExtension implements Extension {
	public static CertExtension extendedKeyUsage(KnownOID... usages) {
		var dw = new DerWriter();
		dw.begin(DerValue.SEQUENCE);
		for (var oid : usages) {
			oid.assertType("extendedKeyUsage");
			dw.writeOid(oid.oid.value);
		}
		dw.end();
		return new CertExtension(KnownOID.extendedKeyUsage, false, dw.toByteArray());
	}

	public static CertExtension subjectAltName(String... names) {
		var dw = new DerWriter();
		dw.begin(DerValue.SEQUENCE);
		for (var name : names) {
			if (isIPAddress(name)) {
				// We should use roj.net.Net.ip2bytes(name)
				try {
					dw.write(0x87, InetAddress.getByName(name).getAddress());
				} catch (UnknownHostException e) {
					Helpers.athrow(e);
				}
			} else {
				dw.write(0x82, name.getBytes(StandardCharsets.UTF_8));
			}
		}
		dw.end();
		return new CertExtension(KnownOID.SubjectAlternativeName, false, dw.toByteArray());
	}

	private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
	private static boolean isIPAddress(String name) {
		return IPV4.matcher(name).matches() || name.contains(":");
	}

	public static CertExtension basicConstraints(boolean isCA, long pathLengthConstraints) {
		var dw = new DerWriter();
		dw.begin(DerValue.SEQUENCE);
		if (isCA) {
			dw.writeBool(true);
			if (pathLengthConstraints != -1) dw.writeInt(BigInteger.valueOf(pathLengthConstraints));
		}
		dw.end();
		return new CertExtension(KnownOID.BasicConstraints, true, dw.toByteArray());
	}

	public static final int
			digitalSignature = 0x01,
			nonRepudiation = 0x02,
			keyEncipherment = 0x04,
			dataEncipherment = 0x08,
			keyAgreement = 0x10,
			keyCertSign = 0x20,
			cRLSign = 0x40,
			encipherOnly = 0x80,
			decipherOnly = 0x100;

	public static CertExtension keyUsage(@MagicConstant(flags = {
			digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment, keyAgreement, keyCertSign, cRLSign, encipherOnly, decipherOnly
	}) int keyUsage) {
		var dw = new DerWriter();

		int data = Integer.reverse(keyUsage);
		int bits = 32 - Integer.numberOfTrailingZeros(data);
		dw.writeBits(bits > 8 ? new byte[]{(byte) (data >>> 24), (byte) (data >>> 16)} :  new byte[]{(byte) (data >>> 24)}, bits);
		return new CertExtension(KnownOID.KeyUsage, true, dw.toByteArray());
	}

	private final KnownOID knownOID;
	private final IntArrayValue OID;
	private final boolean isCritical;
	private final byte[] data;

	public CertExtension(KnownOID oid, boolean isCritical, byte[] data) {
		oid.assertType("CertExt");
		knownOID = oid;
		OID = knownOID.oid;
		this.isCritical = isCritical;
		this.data = data;
	}
	public CertExtension(IntArrayValue oid, boolean isCritical, byte[] data) {
		knownOID = null;
		OID = oid;
		this.isCritical = isCritical;
		this.data = data;
	}


	public String toString() {return knownOID == null ? getId() : knownOID.name();}
	@Override public String getId() {return OID.toString();}
	public IntArrayValue getOID() {return OID;}

	@Override public boolean isCritical() {return isCritical;}
	@Unmodifiable @Override public byte[] getValue() {return data;}
	@Override public void encode(OutputStream out) throws IOException {out.write(data);}
}

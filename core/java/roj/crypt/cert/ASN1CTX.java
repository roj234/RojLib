package roj.crypt.cert;

import roj.crypt.asn1.Asn1Context;
import roj.io.IOUtil;
import roj.text.ParseException;

import java.io.IOException;

/**
 * @author Roj234
 * @since 2026/04/25 05:27
 */
public final class ASN1CTX {
	public static final Asn1Context CTX;
	static {
		try {
			CTX = Asn1Context.createFromString(IOUtil.getTextResourceIL("roj/crypt/jar/PKCS#7.asn"));
		} catch (ParseException | IOException e) {
			throw new RuntimeException(e);
		}
	}
}

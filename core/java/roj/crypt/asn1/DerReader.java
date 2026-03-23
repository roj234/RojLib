package roj.crypt.asn1;

import roj.collect.IntList;
import roj.config.node.BoolValue;
import roj.config.node.ByteArrayValue;
import roj.config.node.ConfigValue;
import roj.config.node.IntArrayValue;
import roj.io.CorruptedInputException;
import roj.io.XDataInput;
import roj.io.XDataInputStream;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * @author Roj234
 * @since 2023/11/4 19:03
 */
public class DerReader {
	private final XDataInput in;

	public DerReader(XDataInput din) {this.in = din;}

	public int readType() throws IOException { return in.readUnsignedByte(); }

	public ConfigValue readBool(int length) throws IOException {
		assert length == 1;
		int i = in.readUnsignedByte();
		if (i == 0xFF) return BoolValue.TRUE;
		if (i == 0) return BoolValue.FALSE;
		throw new CorruptedInputException("未预料的布尔值: "+i);
	}
	public ConfigValue readInt(int length) throws IOException {
		byte[] data;
		if (length > 65536) {
			data = safeRead(length);
		} else {
			data = ArrayCache.getByteArray(length, false);
			in.readFully(data, 0, length);
		}
		BigInteger bi = new BigInteger(data, 0, length);
		ArrayCache.putArray(data);

		return /*length <= 4 && bi.bitLength() <= 31 ? CEntry.valueOf(bi.intValue()) : */DerValue.INTEGER(bi);
	}

	public ConfigValue readBits(int length) throws IOException {
		int trash = in.readUnsignedByte();
		if (trash >= 8) throw new CorruptedInputException("bit string "+trash);
		return new DerValue.Bits(trash, readDirectBytes(length-1));
	}

	public ConfigValue readBytes(int length) throws IOException {return new ByteArrayValue(readDirectBytes(length));}
	public ConfigValue readOid(int length) throws IOException {
		long end = in.position()+ length;
		IntList oids = new IntList();
		int oid = in.readVarInt();
		// 第一个数必须是 0、1、2 三者之一， 如果是 0 或 1，则第二个数必须小于 40
		switch (oid/40) {
			case 0 -> {
				oids.add(0);
				oids.add(oid);
			}
			case 1 -> {
				oids.add(1);
				oids.add(oid - 40);
			}
			default -> {
				oids.add(2);
				oids.add(oid - 80);
			}
		}

		while (in.position() < end) {
			int oid_sub = readDerOidEntry();
			oids.add(oid_sub);
		}

		if (in.position() != end) throw new CorruptedInputException("length mismatch");
		return new IntArrayValue(oids.toArray());
	}

	public ConfigValue readIso(int len) throws IOException {return ConfigValue.valueOf(len > 65536 ? new String(safeRead(len), StandardCharsets.ISO_8859_1) : in.readAscii(len));}
	public ConfigValue readUTF(int len) throws IOException {return ConfigValue.valueOf(len > 65536 ? new String(safeRead(len), StandardCharsets.UTF_8) : in.readUTF(len));}
	public ConfigValue readOpaque(int type, int length) throws IOException {return new DerValue.Opaque(type, readDirectBytes(length));}
	private byte[] readDirectBytes(int length) throws IOException {
		byte[] data;
		if (length > 65536) {
			data = safeRead(length);
		} else {
			data = new byte[length];
			in.readFully(data);
		}
		return data;
	}

	private byte[] safeRead(int len) throws IOException {
		if (in instanceof DynByteBuf b) return b.readBytes(len);

		ByteList buf = new ByteList();
		int r = buf.readStream((XDataInputStream) in, len);
		if (r < len) throw new EOFException("没有 "+len+" 字节可用");
		return buf.toByteArrayAndFree();
	}

	public final int readLength() throws IOException {return readLength1(in);}
	public static int readLength1(XDataInput in) throws IOException {
		int len = in.readUnsignedByte();
		if ((len&0x80) == 0) return len;
		len &= 0x7F;
		return switch (len) {
			default -> throw new CorruptedInputException("data length is "+len+" bytes");
			case 4 -> in.readInt();
			case 3 -> in.readMedium();
			case 2 -> in.readUnsignedShort();
			case 1 -> in.readUnsignedByte();
			//case 0 -> -1;
		};
	}

	private int readDerOidEntry() throws IOException {
		int value = 0;
		int i = 0;

		while (i++ <= 4) {
			int chunk = in.readUnsignedByte();
			value = value << 7 | (chunk&0x7F);
			if ((chunk & 0x80) == 0) return value;
		}
		throw new RuntimeException("oid value too large ("+value+")");
	}

	public int position() throws IOException { return (int) in.position(); }

	public void skip(int i) throws IOException {
		int i1 = in.skipBytes(i);
		if (i1 < i) throw new EOFException();
	}
}
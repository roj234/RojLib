package roj.math;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;
import roj.compiler.runtime.RtUtil;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Random;

/**
 * Math utilities.
 */
public abstract class MathUtils {
	public static final double HALF_PI = Math.PI / 2, TWO_PI = Math.PI * 2;

	public static int clamp(int val, int min, int max) { return val < min ? min : val > max ? max : val; }
	public static long clamp(long val, long min, long max) { return val < min ? min : val > max ? max : val; }
	// 碰到NaN时，返回min
	public static double clamp(double val, double min, double max) { return val >= min ? val > max ? max : val : min; }

	public static long unsignedMultiplyHigh(long x, long y) {return Math.multiplyHigh(x, y) + ((x >> 63) & y) + ((y >> 63) & x);}

	@Contract(pure = true)
	public static int pow(int base, int exponent) {return RtUtil.pow(base, exponent);}
	@Contract(pure = true)
	public static int positiveMod(int dividend, @Range(from = 1, to = Integer.MAX_VALUE) int divisor) {return RtUtil.positiveMod(dividend, divisor);}
	@Contract(pure = true)
	public static long positiveMod(long dividend, @Range(from = 1, to = Integer.MAX_VALUE) long divisor) {return RtUtil.positiveMod(dividend, divisor);}

	/**
	 * Generates a <b>mathematically</b> uniform distribution {@code double}
	 * from the given random source.
	 * <p>
	 * This algorithm generate a stream of bits uniformly at random and
	 * interpret it as the fractional part of the binary expansion of a
	 * number in [0, 1], 0.00001010011111010100...; then round it.
	 * @param random The random source
	 * @return A mathematically uniform distribution double in range [0, 1]
	 */
	public static double realNextDouble(Random random) {
		int exponent = -64;
		long significand;

		// Not simply return 0 here, as there are some chances for 1e-N
		while (/*UNLIKELY*/(significand = random.nextLong()) == 0) {
			exponent -= 64;

			// Small, always rounding to zero, and nearly impossible to happen
			if (/*UNLIKELY*/exponent < Double.MIN_EXPONENT + 1 - /*Double.PRECISION*/53)
				return 0;
		}

		/*
		 * There is a 1 somewhere in significand, not necessarily in
		 * the most significant position.  If there are leading zeros,
		 * shift them into the exponent and refill the less-significant
		 * bits of the significand.  Can't predict one way or another
		 * whether there are leading zeros: there's a fifty-fifty
		 * chance, if random64 is uniformly distributed.
		 */
		int shift = Long.numberOfLeadingZeros(significand);
		if (shift != 0) {
			exponent -= shift;
			significand <<= shift;
			significand |= random.nextLong() >>> (64 - shift);
		}

		/*
		 * Set the sticky bit, since there is almost surely another 1
		 * in the bit stream.  Otherwise, we might round what looks
		 * like a tie to even when, almost surely, were we to look
		 * further in the bit stream, there would be a 1 breaking the
		 * tie.
		 */
		significand |= 1;

		/*
		 * Convert to double (rounding) and scale by 2^exponent.
		 */
		long bits = ((long) (exponent + 1023) << 52) | (significand >>> 12);
		return Double.longBitsToDouble(bits);
	}

	/**
	 * Returns an iterator providing a number sequence. This sequence starts with <i>{@code from}</i> (given as
	 * parameter) and ends with <i>{@code to}</i> (given as parameter). In between, new values are calculated as
	 * sigmoid function e^t(x) / (1 + e^t(x)).
	 *
	 * @param from First number in the sequence
	 * @param to Last number in the sequence
	 * @param steps The length of the sequence (exclusive <i>{@code from}</i> and <i>{@code to}</i>)
	 *
	 * @return an iterator providing a number sequence calculated as sigmoid function.
	 * {@link Iterator#hasNext() hasNext()} returns false, if the sequence has finished, but
	 * {@link Iterator#next() next()} will return <i>{@code to}</i>
	 *
	 * @throws IllegalArgumentException if <i>{@code steps}</i> {@code <} <i>{@code 0}</i>
	 */
	public static PrimitiveIterator.OfDouble createSigmoidSequence(double from, double to, int steps) {
		if (steps < 0) throw new IllegalArgumentException("steps < 0");

		return new PrimitiveIterator.OfDouble() {
			// e^t(x) / (1 + e^t(x)) in [0, xmax] for t(x) = 8 * x / xmax - 4

			private int step = 0;
			private final double delta = to - from;
			private final int maxStep = steps + 1;

			@Override public boolean hasNext() {return step <= maxStep;}
			@Override public double nextDouble() {
				if (step > maxStep) throw new NoSuchElementException();
				int s = step++;

				if (s == maxStep) return to;
				else if (s == 0) return from;
				else {
					double x = s / (double) maxStep;
					double tmp = Math.exp(8 * x - 4);
					return delta * (tmp / (1 + tmp)) + from;
				}
			}

			// result for (from, to, steps) = (0, 20, 18)

			// |20|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | x|
			// |19|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | x| x|  |
			// |18|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | x| x|  |  |  |
			// |17|  |  |  |  |  |  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |
			// |16|  |  |  |  |  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |
			// |15|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// |14|  |  |  |  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |
			// |13|  |  |  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |
			// |12|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// |11|  |  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |
			// |10|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 9|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 8|  |  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |  |
			// | 7|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 6|  |  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |  |  |
			// | 5|  |  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |  |  |  |
			// | 4|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 3|  |  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 2|  |  |  |  |  | x|  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 1|  |  |  | x| x|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			// | 0| x| x| x|  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
			//    | 0| 1| 2| 3| 4| 5| 6| 7| 8| 9|10|11|12|13|14|15|16|17|18|19|
		};
	}

	public static double interpolate(double old, double now, double delta) {
		return old + delta * (now - old);
	}

	public static int nextPowerOfTwo(int cap) {
		int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
		return (n < 0) ? 1 : (n >= 1<<30) ? 1<<30 : n + 1;
	}

	public static long nextPowerOfTwo(long cap) {
		long n = -1L >>> Long.numberOfLeadingZeros(cap - 1);
		return (n < 0) ? 1 : (n >= 1L<<62) ? 1L<<62 : n + 1;
	}

	public static double cos(double value) {return sin(HALF_PI + value);}
	/** 精度1e-6 */
	public static double sin(double value) {
		if (value >= 0) {
			if (value <= HALF_PI) {
				return ((((((-0.000960664 * value + 0.0102697866) * value - 0.00198601997) * value - 0.1656067221) * value - 0.0002715666) * value + 1.000026227) * value);
			} else {
				if (value >= TWO_PI) {
					value %= TWO_PI;
				}

				if (value >= Math.PI) {
					return -sin(value - Math.PI);
				}

				if (value > HALF_PI) {
					value = sin(value - HALF_PI);
					value *= value;
					return value > 1 ? 0 : Math.sqrt(1 - value);
				}

				return sin(value);
			}
		} else {
			return -sin(-value);
		}
	}

	public static int randomRange(Random rand, int min, int max) {return min + rand.nextInt(max - min + 1);}

	/**
	 * 计算值离float_num最近的分数，分母不大于max_denominator.
	 * @return {分子,分母}
	 */
	@NotNull
	@Contract(pure = true)
	public static int[] closestFraction(double float_num, @Range(from = 2, to = Integer.MAX_VALUE) int max_denominator) {
		double remainder = float_num % 1;
		int[] result;
		if (remainder < 0) {
			result = closestFractionP(-remainder, max_denominator);
			result[0] = -result[0];
		} else {
			result = closestFractionP(remainder, max_denominator);
		}
		result[0] += result[1] * (float_num-remainder);
		return result;
	}

	private static int[] closestFractionP(@Range(from = 0, to = 1) double float_num, @Range(from = 2, to = Integer.MAX_VALUE) int max_denominator) {
		double remainder = float_num;
		int p0 = 1, q0 = 0;
		int p1 = 0, q1 = 1;

		while (Math.abs(remainder) >= 1E-8) {
			remainder = 1 / remainder;
			int ai = (int) remainder;
			int p = ai * p1 + p0;
			int q = ai * q1 + q0;

			if (q > max_denominator) break;

			q0 = q1;p0 = p1;
			q1 = q ;p1 = p ;

			remainder -= ai;
		}

		var error0 = Math.abs(float_num - (double) p0 / q0);
		var error1 = Math.abs(float_num - (double) p1 / q1);
		return error0 < error1 ? new int[]{p0, q0} : new int[]{p1, q1};
	}
}
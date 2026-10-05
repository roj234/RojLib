package roj.archive.algorithms.model;

import org.jetbrains.annotations.Range;
import roj.archive.rangecoder.RangeDecoder;
import roj.archive.rangecoder.RangeEncoder;
import roj.util.NativeMemory;

import java.io.IOException;
import java.util.Arrays;

import static roj.reflect.Unsafe.U;

/**
 * PPMd7 (PPMdH) codec (translated from C).
 * <br>
 * 2023-03-05 : Igor Pavlov : Public domain
 * This code is based on PPMd var.H (2001): Dmitry Shkarin : Public domain
 * @author Roj234
 * @since 2025/10/19 17:25
 */
public final class PPMd7 implements EntropyModel {
	private static final int PPMD_INT_BITS = 7;
	private static final int PPMD_PERIOD_BITS = 7;
	private static final int PPMD_BIN_SCALE = 1 << (PPMD_INT_BITS + PPMD_PERIOD_BITS);

	private static int updateProb1(int prob) {return prob - ((prob + (1 << (PPMD_PERIOD_BITS - 2))) >>> PPMD_PERIOD_BITS);}

	private static final int PPMD_N1 = 4, PPMD_N2 = 4, PPMD_N3 = 4;
	private static final int PPMD_N4 = ((128 + 3 - 1 * PPMD_N1 - 2 * PPMD_N2 - 3 * PPMD_N3) / 4);
	private static final int PPMD_NUM_INDEXES = PPMD_N1 + PPMD_N2 + PPMD_N3 + PPMD_N4;

	private interface Context {
//		UInt16 NumStats;
//
//		union
//		{
//			UInt16 SummFreq;
//			CPpmd_State2 State2;
//		} Union2;
//
//		union
//		{
//			CPpmd_State_Ref Stats;
//			CPpmd_State4 State4;
//		} Union4;
//
//		CPpmd7_Context_Ref Suffix;
		static long oneState(long address) {return address+2L;}

		static void setStateCount(long address, int value) {U.putChar(address, (char) value);}
		static void setSumFrequency(long address, int value) {U.putChar(address+2L, (char) value);}
		static void setStateBase(long address, int value) {U.putInt(address+4L, value);}
		static void setSuffix(long address, int value) {U.putInt(address+8L, value);}

		static int getStateCount(long address) {return U.getChar(address);}
		static int getSumFrequency(long address) {return U.getChar(address+2L);}
		static int getStateBase(long address) {return U.getInt(address+4L);}

		static int getSuffix(long address) {return U.getInt(address+8L);}

		static String toString(long address) {
			return "Context@"+Long.toHexString(address)+"{" +
					"stateCount=" + getStateCount(address) +
					", sumFreq=" + getSumFrequency(address) +
					", stateBase=" + getStateBase(address) +
					", suffix=" + getSuffix(address) +
					'}';
		}
	}

	private interface State {
		//Byte Symbol;
		//Byte Freq;
		//UInt16 Successor_0;
		//UInt16 Successor_1;

		int STRUCT_SIZE = 6;

		static void setSymbol(long address, int symbol) {U.putByte(address, (byte) symbol);}
		static void setFrequency(long address, int symbol) {U.putByte(address + 1L, (byte) symbol);}
		static void setSuccessor(long address, int symbol) {U.put32UL(null, address + 2L, symbol);}

		static int getSymbol(long address) {return U.getByte(address) & 0xFF;}
		static int getFrequency(long address) {return U.getByte(address + 1) & 0xFF;}
		static int getSuccessor(long address) {return U.get32UL(null, address + 2L);}

		static String toString(long address) {
			return "State@"+Long.toHexString(address)+"{" +
					"symbol=" + getSymbol(address) +
					", freq=" + getFrequency(address) +
					", successor=" + getSuccessor(address) +
					'}';
		}
	}

	private static final class See {
		char summ; // Freq
		byte shift; // Speed of Freq change; low Shift is for fast change
		byte count; // Count to next change of Shift

		See() {}

		// Ppmd_See_UPDATE macro
		void update() {
			if (shift < PPMD_PERIOD_BITS && --count == 0) {
				summ <<= 1;
				count = (byte) (3 << shift++);
			}
		}
	}

	public static final int PPMD7_MIN_ORDER = 2;
	public static final int PPMD7_MAX_ORDER = 64;
	public static final int PPMD7_MIN_MEM_SIZE = 1 << 11;
	public static final long PPMD7_MAX_MEM_SIZE = 0xFFFFFFFFL - 12 * 3;

	private static final int MAX_FREQ = 124;
	private static final int UNIT_SIZE = 12;

	private static final /*unsigned*/ byte[] EXP_ESCAPE = {25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2};
	private static final char[] INIT_BIN_ESC = {0x3CDD, 0x1F3F, 0x59BF, 0x48F3, 0x64A1, 0x5ABC, 0x6632, 0x6051};

	/*
	PPMd Memory Map:
	{
	  [ 0 ]           contains subset of original raw text, that is required to create context
					  records, Some symbols are not written, when max order context was reached
	  [ Text ]        free area
	  [ UnitsStart ]  CPpmd_State vectors and CPpmd7_Context records
	  [ LoUnit ]      free  area for CPpmd_State and CPpmd7_Context items
	  [ HiUnit ]      CPpmd7_Context records
	  [ Size ]        end of array
	}

	These addresses don't cross at any time.
	And the following conditions is true for addresses:
	  (0  <= Text < UnitsStart <= LoUnit <= HiUnit <= Size)

	Raw text is BYTE--aligned.
	the data in block [ UnitsStart ... Size ] contains 12-bytes aligned UNITs.

	Last UNIT of array at offset (Size - 12) is root order-0 CPpmd7_Context record.
	The code can free UNITs memory blocks that were allocated to store CPpmd_State vectors.
	The code doesn't free UNITs allocated for CPpmd7_Context records.

	The code calls Ppmd7_RestartModel(), when there is no free memory for allocation.
	And Ppmd7_RestartModel() changes the state to original start state, with full free block.


	The code allocates UNITs with the following order:

	Allocation of 1 UNIT for Context record
	  - from free space (HiUnit) down to (LoUnit)
	  - from FreeList[0]
	  - Ppmd7_AllocUnitsRare()

	Ppmd7_AllocUnits() for CPpmd_State vectors:
	  - from FreeList[i]
	  - from free space (LoUnit) up to (HiUnit)
	  - Ppmd7_AllocUnitsRare()

	Ppmd7_AllocUnitsRare()
	  - if (GlueCount == 0)
		   {  Glue lists, GlueCount = 255, allocate from FreeList[i]] }
	  - loop for all higher sized FreeList[...] lists
	  - from (UnitsStart - Text), GlueCount--
	  - ERROR


	Each Record with Context contains the CPpmd_State vector, where each
	CPpmd_State contains the link to Successor.
	There are 3 types of Successor:
	  1) NULL-Successor   - NULL pointer. NULL-Successor links can be stored
							only in 0-order Root Context Record.
							We use 0 value as NULL-Successor
	  2) RAW-Successor    - the link to position in raw text,
							that "RAW-Successor" is being created after first
							occurrence of new symbol for some existing context record.
							(RAW-Successor > 0).
	  3) RECORD-Successor - the link to CPpmd7_Context record of (Order+1),
							that record is being created when we go via RAW-Successor again.

	For any successors at any time: the following conditions are true for Successor links:
	(NULL-Successor < RAW-Successor < UnitsStart <= RECORD-Successor)


	---------- Symbol Frequency, SummFreq and Range in Range_Coder ----------

	CPpmd7_Context::SummFreq = Sum(Stats[].Freq) + Escape_Freq

	The PPMd code tries to fulfill the condition:
	  (SummFreq <= (256 * 128 = RC::kBot))

	We have (Sum(Stats[].Freq) <= 256 * 124), because of (MAX_FREQ = 124)
	So (4 = 128 - 124) is average reserve for Escape_Freq for each symbol.
	If (CPpmd_State::Freq) is not aligned for 4, the reserve can be 5, 6 or 7.
	SummFreq and Escape_Freq can be changed in Ppmd7_Rescale() and *Update*() functions.
	Ppmd7_Rescale() can remove symbols only from max-order contexts. So Escape_Freq can increase after multiple calls of Ppmd7_Rescale() for
	max-order context.

	When the PPMd code still break (Total <= RC::Range) condition in range coder,
	we have two ways to resolve that problem:
	  1) we can report error, if we want to keep compatibility with original PPMd code that has no fix for such cases.
	  2) we can reduce (Total) value to (RC::Range) by reducing (Escape_Freq) part of (Total) value.
	*/
	private final NativeMemory memoryRef = new NativeMemory();

	private long minContext, maxContext;
	private long foundState;
	private /*unsigned*/ int orderFall, initialEscape, prevSuccess, maxOrder, hiBitsFlag;
	private int runLength, initialRunLength;

	private /*unsigned*/ int size;
	private /*unsigned*/ int glueCount;
	private /*unsigned*/ int alignOffset;
	private long base, loUnit, hiUnit, text, unitsStart;

	private final /*unsigned*/ int[] freeList = new int[PPMD_NUM_INDEXES];

	private static final int SIMULATE_STACK_SIZE = 8 * PPMD7_MAX_ORDER + 8;
	private long simulateStack;

	// Alloc tables
	private static final /*unsigned*/ byte[] INDEX2UNITS = new byte[PPMD_NUM_INDEXES];
	private static final /*unsigned*/ byte[] UNITS2INDEX = new byte[128];
	static {
		int i, k = 0;
		for (i = 0; i < PPMD_NUM_INDEXES; i++) {
			int step = (i >= 12 ? 4 : (i >>> 2) + 1);
			do {
				UNITS2INDEX[k++] = (byte) i;
			} while (--step != 0);
			INDEX2UNITS[i] = (byte) k;
		}
	}

	private static final /*unsigned*/ byte[] NS2BSIndex = new byte[256];
	private static final /*unsigned*/ byte[] NS2Index = new byte[256];
	static {
		// NS2BSIndex
		NS2BSIndex[0] = 0 << 1;
		NS2BSIndex[1] = 1 << 1;
		Arrays.fill(NS2BSIndex, 2, 11, (byte) (2 << 1));
		Arrays.fill(NS2BSIndex, 11, 256, (byte) (3 << 1));

		// NS2Index
		int i;
		for (i = 0; i < 3; i++)
			NS2Index[i] = (byte) i;

		for (int m = i, k = 1; i < 256; i++) {
			NS2Index[i] = (byte) m;
			if (--k == 0)
				k = (++m) - 2;
		}
	}

	// Other tables
	private static final See DUMMY_SEE = new See();
	static {DUMMY_SEE.shift = PPMD_PERIOD_BITS;}

	private static final int SEE_COLS = 16;
	private final See[] see = new See[25 * 16];

	private static final int BIN_SUMM_COLS = 64;
	private final char[] probs = new char[128 * 64];

	// Ppmd7_Construct
	public PPMd7() {
		for (int i = 0; i < 25 * 16; i++) see[i] = new See();
	}

	// Ppmd7_Alloc
	public void alloc(/*unsigned*/ int size) {
		long longSize = Integer.toUnsignedLong(size);
		if (longSize < PPMD7_MIN_MEM_SIZE || longSize > PPMD7_MAX_MEM_SIZE) {
			throw new IllegalArgumentException("Insufficient memory: 0x"+Integer.toHexString(size));
		}

		if (this.size != size) {
			free();

			alignOffset = (4 - size) & 3;
			base = memoryRef.allocate(alignOffset + longSize + SIMULATE_STACK_SIZE);
			simulateStack = base + alignOffset + longSize;
			this.size = size;
		}
	}

	// Ppmd7_Free
	public void free() {
		memoryRef.free();
		base = 0;
		size = 0;
	}

	// Ppmd7_Init
	public void init(int maxOrder) {
		this.maxOrder = maxOrder;
		reset();
	}

	// Ppmd7_RestartModel
	public void reset() {
		Arrays.fill(freeList, 0);

		long size = Integer.toUnsignedLong(this.size);
		text = base + alignOffset;
		hiUnit = text + size;
		loUnit = unitsStart = hiUnit - size / 8 / UNIT_SIZE * 7 * UNIT_SIZE;
		glueCount = 0;

		orderFall = maxOrder;
		runLength = initialRunLength = -(maxOrder < 12 ? maxOrder : 12) - 1;
		prevSuccess = 0;

		{
			long rootContext = hiUnit -= UNIT_SIZE;
			long state = loUnit;

			loUnit += U2B(256 / 2);
			maxContext = minContext = rootContext;
			foundState = state;

			Context.setStateCount(rootContext, 256);
			Context.setSumFrequency(rootContext, 256+1);
			Context.setStateBase(rootContext, EncodePointer(state));
			Context.setSuffix(rootContext, 0);

			for (int i = 0; i < 256; i++, state += State.STRUCT_SIZE) {
				State.setSymbol(state, i);
				State.setFrequency(state, 1);
				State.setSuccessor(state, 0);
			}
		}

		// Init BinSumm
		for (int i = 0; i < 128; i++) {
			for (int k = 0; k < 8; k++) {
				char val = (char) (PPMD_BIN_SCALE - INIT_BIN_ESC[k] / (i + 2));
				for (int m = 0; m < 64; m += 8) {
					probs[i * BIN_SUMM_COLS + k + m] = val; // Adjust index
				}
			}
		}

		// Init See
		for (int i = 0; i < 25; i++) {
			char summ = (char) ((5 * i + 10) << (PPMD_PERIOD_BITS - 4));
			for (int k = 0; k < 16; k++) {
				See see = this.see[i * SEE_COLS + k];
				see.summ = summ;
				see.shift = PPMD_PERIOD_BITS - 4;
				see.count = 4;
			}
		}
	}

	private int EncodePointer(long ptr) {return (int) (ptr - base);}
	private long DecodePointer(int ref) {
		//if (ref >= size) throw new IllegalArgumentException("Illegal pointer "+ref);
		return base + Integer.toUnsignedLong(ref);
	}

	private static int U2B(int nu) {return nu * UNIT_SIZE;}
	private static int U2I(int nu) {return UNITS2INDEX[nu - 1] & 0xFF;}
	private static int I2U(int indx) {return INDEX2UNITS[indx] & 0xFF;}

	// region Memory allocator internals
	private interface Node {
		int STRUCT_SIZE = 12;
		int NEXT = 4;

		/* must be at offset 0 as CPpmd7_Context::NumStats. Stamp=0 means free */
		static void setStamp(long address, int stamp) {U.putChar(address, (char) stamp);}
		static void setNumberOfUnits(long address, int nu) {U.putChar(address+2L, (char) nu);}
		/* must be at offset >= 4 */
		static void setNext(long address, int next) {U.putInt(address+4L, next);}
		static void setPrev(long address, int prev) {U.putInt(address+8L, prev);}

		static int getStamp(long address) {return U.getChar(address);}
		static int getNumberOfUnits(long address) {return U.getChar(address+2L);}
		static int getNext(long address) {return U.getInt(address+4L);}
		static int getPrev(long address) {return U.getInt(address+8L);}
	}

	private static final int EMPTY_NODE = 0;

	private void insertNode(long node, int indx) {
		U.putInt(node, freeList[indx]);
		freeList[indx] = EncodePointer(node);
	}

	private long removeNode(int indx) {
		long node = DecodePointer(freeList[indx]);
		freeList[indx] = U.getInt(node);
		return node;
	}

	private void splitBlock(long ptr, int oldIndx, int newIndx) {
		int nu = I2U(oldIndx) - I2U(newIndx);
		ptr += U2B(I2U(newIndx)); // Advance in buffer
		int i = U2I(nu);
		if (I2U(i) != nu) {
			int k = I2U(--i);
			insertNode(ptr + U2B(k), nu - k - 1);
		}
		insertNode(ptr, i);
	}

	private void glueFreeBlocks() {
		glueCount = 255;
		/* we set guard NODE at LoUnit */
		if (loUnit != hiUnit) {
			Node.setStamp(loUnit, 1);
		}

		/* Create list of free blocks.
		   We still need one additional list walk pass before Glue. */
		int n = 0;
		for (int i = 0; i < PPMD_NUM_INDEXES; i++) {
			int nu = I2U(i);
			int next = freeList[i];
			freeList[i] = 0;
			while (next != 0) {
				/* Don't change the order of the following commands: */
				long node = DecodePointer(next);
				final int tmp = next;
				next = U.getInt(node);

				Node.setStamp(node, EMPTY_NODE);
				Node.setNumberOfUnits(node, nu);
				Node.setNext(node, n);

				n = tmp;
			}
		}

		/* Glue and Fill must walk the list in same direction */
		long head = simulateStack + SIMULATE_STACK_SIZE - 4; // simulated stack pointer
		U.putInt(head, n);

		/* Glue free blocks */
		long prev = head;
		while (n != 0) {
			long node = DecodePointer(n);
			int nu = Node.getNumberOfUnits(node);
			n = Node.getNext(node);

			if (nu == 0) {
				U.putInt(prev, n);
				continue;
			}

			prev = node + Node.NEXT;
			while (true) {
				long node2 = node + (long) nu * Node.STRUCT_SIZE;
				nu += Node.getNumberOfUnits(node2);
				if (Node.getStamp(node2) != EMPTY_NODE || nu >= 0x10000)
					break;
				Node.setNumberOfUnits(node, nu);
				Node.setNumberOfUnits(node2, 0);
			}
		}

		// Fill free lists
		n = U.getInt(head);

		while (n != 0) {
			long node = DecodePointer(n);
			int nu = Node.getNumberOfUnits(node);
			n = Node.getNext(node);

			if (nu == 0) continue;

			for (; nu > 128; nu -= 128, node += 128 * Node.STRUCT_SIZE)
				insertNode(node, PPMD_NUM_INDEXES - 1);

			int i = U2I(nu);
			if (I2U(i) != nu) {
				int k = I2U(--i);
				insertNode(node + U2B(k), nu - k - 1);
			}
			insertNode(node, i);
		}
	}

	private long allocUnits(int indx) {
		int free = freeList[indx];
		if (free != 0) return removeNode(indx);

		int numBytes = U2B(I2U(indx));
		long lo = loUnit;
		if (hiUnit - lo >= numBytes) {
			loUnit = lo + numBytes;
			return lo;
		}

		return allocUnitsRare(indx);
	}
	private long allocUnitsRare(int indx) {
		if (glueCount == 0) {
			glueFreeBlocks();
			if (freeList[indx] != 0) {
				return removeNode(indx);
			}
		}

		int i = indx;
		do {
			if (++i == PPMD_NUM_INDEXES) {
				int numBytes = U2B(I2U(indx));
				long unitStartPos = unitsStart;
				glueCount--;
				return ((unitStartPos - text) > numBytes) ? (unitsStart = unitStartPos - numBytes) : 0;
			}
		} while (freeList[i] == 0);

		long block = removeNode(i);
		splitBlock(block, i, indx);
		return block;
	}
	//endregion

	// Alloc a context (1 unit)
	private long allocContext() {
		if (hiUnit != loUnit) { // Free space in HiUnit -> LoUnit
			hiUnit -= UNIT_SIZE;
			return hiUnit;
		} else if (freeList[0] != 0) {
			return removeNode(0);
		} else {
			return allocUnitsRare(0);
		}
	}

	/*
	  Ppmd7_CreateSuccessors()
	  It's called when (FoundState->Successor) is RAW-Successor,
	  that is the link to position in Raw text.
	  So we create Context records and write the links to
	  FoundState->Successor and to identical RAW-Successors in suffix
	  contexts of MinContex.

	  The function returns:
	  if (OrderFall == 0) then MinContext is already at MAX order,
		{ return pointer to new or existing context of same MAX order }
	  else
		{ return pointer to new real context that will be (Order+1) in comparison with MinContext

	  also it can return pointer to real context of same order,
	*/
	private long createSuccessors() {
		long ctx = minContext;
		int upBranch = State.getSuccessor(foundState);
		int newSym, newFreq;
		int numPs = 0;
		long ps = simulateStack;

		if (orderFall != 0) {
			U.putLong(ps, foundState);
			numPs++;
		}

		while (Context.getSuffix(ctx) != 0) {
			long state;
			ctx = DecodePointer(Context.getSuffix(ctx));

			if (Context.getStateCount(ctx) != 1) {
				int sym = State.getSymbol(foundState);
				state = DecodePointer(Context.getStateBase(ctx));
				while (State.getSymbol(state) != sym) {
					state += State.STRUCT_SIZE;
				}
			} else {
				state = Context.oneState(ctx);
			}

			int successor = State.getSuccessor(state);
			if (successor != upBranch) {
				// (c) is real record Context here,
				ctx = DecodePointer(successor);
				if (numPs == 0) {
					// (c) is real record MAX Order Context here,
					// So we don't need to create any new contexts.
					return ctx;
				}
				break;
			}
			//noinspection IntegerMultiplicationImplicitCastToLong
			U.putLong(ps + 8 * numPs++, state);
		}

		// All created contexts will have single-symbol with new RAW-Successor
		// All new RAW-Successors will point to next position in RAW text
		// after FoundState->Successor
		newSym = U.getByte(DecodePointer(upBranch)) & 0xFF;
		upBranch++;

		if (Context.getStateCount(ctx) == 1) {
			newFreq = State.getFrequency(Context.oneState(ctx));
		} else {
			long s = DecodePointer(Context.getStateBase(ctx));
			while (State.getSymbol(s) != newSym)
				s += State.STRUCT_SIZE;

			int cf = State.getFrequency(s) - 1;
			int s0 = Context.getSumFrequency(ctx) - Context.getStateCount(ctx) - cf;
			/*
			  cf - is frequency of symbol that will be Successor in new context records.
			  s0 - is commulative frequency sum of another symbols from parent context.
			  max(newFreq)= (s->Freq + 1), when (s0 == 1)
			  we have requirement (Ppmd7Context_OneState()->Freq <= 128) in BinSumm[]
			  so (s->Freq < 128) - is requirement for multi-symbol contexts
			*/
			newFreq = (1 + ((2 * cf <= s0) ? (5 * cf > s0) ? 1 : 0 : (2 * cf + s0 - 1) / (2 * s0) + 1));
		}

		// Create new single-symbol contexts from low order to high order in loop
		do {
			long c1 = allocContext();
			if (c1 == 0) return 0; // Alloc failed

			Context.setStateCount(c1, 1);
			long oneStateAddr = Context.oneState(c1);
			State.setSymbol(oneStateAddr, newSym);
			State.setFrequency(oneStateAddr, newFreq);
			State.setSuccessor(oneStateAddr, upBranch);
			Context.setSuffix(c1, EncodePointer(ctx));
			//noinspection IntegerMultiplicationImplicitCastToLong
			State.setSuccessor(U.getLong(ps + 8 * --numPs), EncodePointer(c1));
			ctx = c1;
		} while (numPs != 0);

		return ctx;
	}

	private long SWAP_STATES(long s) {
		long prevS = s - State.STRUCT_SIZE;
		U.copyMemory(s, simulateStack, State.STRUCT_SIZE); // tmp = s[0]
		U.copyMemory(prevS, s, State.STRUCT_SIZE); // s[0] = s[-1]
		U.copyMemory(simulateStack, prevS, State.STRUCT_SIZE); // s[-1] = tmp
		return prevS;
	}

	// Ppmd7_UpdateModel
	private void updateModel() {
		int maxSuccessor, minSuccessor;
		long c, mc;
		int s0, ns;

		if (State.getFrequency(foundState) < MAX_FREQ / 4 && Context.getSuffix(minContext) != 0) {
			/* Update Freqs in Suffix Context */
			c = DecodePointer(Context.getSuffix(minContext));

			if (Context.getStateCount(c) == 1) {
				long s = Context.oneState(c);
				if (State.getFrequency(s) < 32) {
					State.setFrequency(s, State.getFrequency(s) + 1);
				}
			} else {
				long s = DecodePointer(Context.getStateBase(c));
				int sym = State.getSymbol(foundState);

				if (State.getSymbol(s) != sym) {
					do {
						s += State.STRUCT_SIZE;
					} while (State.getSymbol(s) != sym);

					if (State.getFrequency(s) >= State.getFrequency(s - State.STRUCT_SIZE)) {
						s = SWAP_STATES(s);
					}
				}

				if (State.getFrequency(s) < MAX_FREQ - 9) {
					State.setFrequency(s, State.getFrequency(s) + 2);
					Context.setSumFrequency(c, Context.getSumFrequency(c) + 2);
				}
			}
		}

		if (orderFall == 0) {
			/* MAX ORDER context */
			/* (FoundState->Successor) is RAW-Successor. */
			maxContext = minContext = createSuccessors();
			if (minContext == 0) {
				reset();
				return;
			}
			State.setSuccessor(foundState, EncodePointer(minContext));
			return;
		}

		/* NON-MAX ORDER context */
		{
			long textPtr = text;
			U.putByte(textPtr++, (byte) State.getSymbol(foundState));
			text = textPtr;
			if (textPtr >= unitsStart) {
				reset();
				return;
			}
			maxSuccessor = EncodePointer(textPtr);
		}

		minSuccessor = State.getSuccessor(foundState);

		if (minSuccessor != 0) {
			// there is Successor for FoundState in MinContext.
			// So the next context will be one order higher than MinContext.

			if (Integer.compareUnsigned(minSuccessor, maxSuccessor) < 0) {
				// minSuccessor is RAW-Successor. So we will create real contexts records:
				long cs = createSuccessors();
				if (cs == 0) {
					reset();
					return;
				}
				minSuccessor = EncodePointer(cs);
			}

			// minSuccessor now is real Context pointer that points to existing (Order+1) context

			if (--orderFall == 0) {
				/*
				if we move to MaxOrder context, then minSuccessor will be common Succesor for both:
				  MinContext that is (MaxOrder - 1)
				  MaxContext that is (MaxOrder)
				so we don't need new RAW-Successor, and we can use real minSuccessor
				as succssors for both MinContext and MaxContext.
				*/
				maxSuccessor = minSuccessor;

				/*
				if (MaxContext != MinContext)
				{
				  there was order fall from MaxOrder and we don't need current symbol
				  to transfer some RAW-Succesors to real contexts.
				  So we roll back pointer in raw data for one position.
				}
				*/
				text -= (maxContext != minContext ? 1 : 0);
			}
		} else {
			/*
			FoundState has NULL-Successor here.
			And only root 0-order context can contain NULL-Successors.
			We change Successor in FoundState to RAW-Successor,
			And next context will be same 0-order root Context.
			*/
			State.setSuccessor(foundState, maxSuccessor);
			minSuccessor = EncodePointer(minContext);
		}

		mc = minContext;
		c = maxContext;

		maxContext = minContext = DecodePointer(minSuccessor);

		if (c == mc) {
			return;
		}

		// s0 : is pure Escape Freq
		s0 = Context.getSumFrequency(mc) - (ns = Context.getStateCount(mc)) - (State.getFrequency(foundState) - 1);

		do {
			int ns1 = Context.getStateCount(c);
			int sum;

			if (ns1 != 1) {
				if ((ns1 & 1) == 0) {
					/* Expand for one UNIT */
					int oldNU = ns1 >>> 1;
					int i = U2I(oldNU);
					if (i != U2I(oldNU + 1)) {
						long ptr = allocUnits(i + 1);
						long oldPtr;
						if (ptr == 0) {
							reset();
							return;
						}
						oldPtr = DecodePointer(Context.getStateBase(c));
						U.copyMemory(oldPtr, ptr, oldNU * 12L);
						insertNode(oldPtr, i);
						Context.setStateBase(c, EncodePointer(ptr));
					}
				}
				sum = Context.getSumFrequency(c);
				/* max increase of Escape_Freq is 3 here.
				   total increase of Union2.SummFreq for all symbols is less than 256 here */
				sum += ((2 * ns1 < ns) ? 1 : 0) + 2 * (((4 * ns1 <= ns) ? 1 : 0) & ((sum <= 8L * ns1) ? 1 : 0));
				/* original PPMdH uses 16-bit variable for (sum) here.
				   But (sum < 0x9000). So we don't truncate (sum) to 16-bit */
			} else {
				// instead of One-symbol context we create 2-symbol context
				long s = allocUnits(0);  // Alloc 1 unit for State
				if (s == 0) {
					reset();
					return;
				}
				{
					long oneState = Context.oneState(c);
					int freq = State.getFrequency(oneState);
					State.setSymbol(s, State.getSymbol(oneState));
					State.setSuccessor(s, State.getSuccessor(oneState));
					// SetSuccessor(s, c->Union4.Stats);  // call it only for debug purposes to check the order of
					// (Successor_0 and Successor_1) in LE/BE.
					Context.setStateBase(c, EncodePointer(s));
					if (freq < MAX_FREQ / 4 - 1) {
						freq <<= 1;
					} else {
						freq = MAX_FREQ - 4;
					}
					// (max(s->freq) == 120), when we convert from 1-symbol into 2-symbol context
					State.setFrequency(s, freq);
					// max(InitEsc = PPMD7_kExpEscape[*]) is 25. So the max(escapeFreq) is 26 here
					sum = (freq + initialEscape + (ns > 3 ? 1 : 0));
				}
			}

			{
				long s = DecodePointer(Context.getStateBase(c)) + (long) ns1 * State.STRUCT_SIZE;
				int cf = 2 * (sum + 6) * State.getFrequency(foundState);
				int sf = s0 + sum;
				State.setSymbol(s, State.getSymbol(foundState));
				Context.setStateCount(c, ns1 + 1);
				State.setSuccessor(s, maxSuccessor);

				if (cf < 6 * sf) {
					cf = 1 + (cf > sf ? 1 : 0) + (cf >= 4 * sf ? 1 : 0);
					sum += 3;
					/* It can add (0, 1, 2) to Escape_Freq */
				} else {
					cf = 4 + (cf >= 9 * sf ? 1 : 0) + (cf >= 12 * sf ? 1 : 0) + (cf >= 15 * sf ? 1 : 0);
					sum += cf;
				}

				Context.setSumFrequency(c, sum);
				State.setFrequency(s, cf);
			}

			c = DecodePointer(Context.getSuffix(c));
		} while (c != mc);
	}

	// Ppmd7_Rescale
	private void rescale() {
		long stats = DecodePointer(Context.getStateBase(minContext));
		long s = foundState;

		/* Sort the list by Freq */
		if (s != stats) {
			U.copyMemory(s, simulateStack, State.STRUCT_SIZE);
			do {
				U.copyMemory(s - State.STRUCT_SIZE, s, State.STRUCT_SIZE);
			} while ((s -= State.STRUCT_SIZE) != stats);
			U.copyMemory(simulateStack, stats, State.STRUCT_SIZE);
		}

		int sumFreq = State.getFrequency(s);
		int escFreq = Context.getSumFrequency(minContext) - sumFreq;

		  /*
		  if (p->OrderFall == 0), adder = 0 : it's     allowed to remove symbol from     MAX Order context
		  if (p->OrderFall != 0), adder = 1 : it's NOT allowed to remove symbol from NON-MAX Order context
		  */

		int adder = (orderFall != 0) ? 1 : 0;
		// 如果支持 order-0，添加: adder |= (maxOrder == 0);

		sumFreq = (sumFreq + 4 + adder) >>> 1;
		int i = Context.getStateCount(minContext) - 1;
		State.setFrequency(s, sumFreq);

		do {
			int freq = State.getFrequency(s += State.STRUCT_SIZE);
			escFreq -= freq;
			freq = (freq + adder) >>> 1;
			sumFreq += freq;
			State.setFrequency(s, freq);

			if (freq > State.getFrequency(s - State.STRUCT_SIZE)) {
				U.copyMemory(s, simulateStack, State.STRUCT_SIZE);
				long s1 = s;
				do {
					U.copyMemory(s1 - State.STRUCT_SIZE, s1, State.STRUCT_SIZE);
				} while ((s1 -= State.STRUCT_SIZE) != stats && freq > State.getFrequency(s1 - State.STRUCT_SIZE));
				U.copyMemory(simulateStack, s1, State.STRUCT_SIZE);
			}
		} while (--i > 0);

		if (State.getFrequency(s) == 0) {
			/* Remove all items with Freq == 0 */

			i = 0;
			do {
				i++;
			} while (State.getFrequency(s -= State.STRUCT_SIZE) == 0);

			// escFreq += i (increase for removed symbols, avg 0.5 per after halving)
			escFreq += i;
			long mc = minContext;
			int numStats = Context.getStateCount(mc);
			int numStatsNew = numStats - i;
			Context.setStateCount(mc, numStatsNew);
			int n0 = (numStats + 1) >>> 1;

			if (numStatsNew == 1) {
				/* Create Single-Symbol context */
				int freq = State.getFrequency(stats);

				do {
					escFreq >>>= 1;
					freq = (freq + 1) >>> 1;
				} while (escFreq > 1);

				s = Context.oneState(mc);
				U.copyMemory(stats, s, State.STRUCT_SIZE);
				State.setFrequency(s, freq); // (freq <= 260 / 4)
				foundState = s;
				insertNode(stats, U2I(n0));
				return;
			}

			int n1 = (numStatsNew + 1) >>> 1;
			if (n0 != n1) {
				// p->MinContext->Union4.Stats = STATS_REF(ShrinkUnits(p, stats, n0, n1));
				int i0 = U2I(n0);
				int i1 = U2I(n1);
				if (i0 != i1) {
					if (freeList[i1] != 0) {
						long ptr = removeNode(i1);
						Context.setStateBase(minContext, EncodePointer(ptr));
						U.copyMemory(stats, ptr, (long) n1 * UNIT_SIZE);
						insertNode(stats, i0);
					}
					else
						splitBlock(stats, i0, i1);
				}
			}
		}

		long mc = minContext;
		// Escape_Freq halving here
		Context.setSumFrequency(mc, sumFreq + escFreq - (escFreq >>> 1));
		foundState = DecodePointer(Context.getStateBase(mc));
	}

	private int _pEscFreq;
	// Ppmd7_MakeEscFreq
	private See makeEscFreq(int numMasked) {
		See see;
		long mc = minContext;
		int numStats = Context.getStateCount(mc);
		if (numStats != 256) {
			int nonMasked = numStats - numMasked;
			see = this.see[NS2Index[nonMasked - 1] * SEE_COLS +
					(nonMasked < Context.getStateCount(DecodePointer(Context.getSuffix(mc))) - numStats ? 1 : 0) +
					(Context.getSumFrequency(mc) < 11 * numStats ? 2 : 0) +
					(numMasked > nonMasked ? 4 : 0) +
					hiBitsFlag // maybe 8 ?
			];

			// if (see->Summ) field is larger than 16-bit, we need only low 16 bits of Summ
			int summ = see.summ;  // UInt16
			int r = summ >>> see.shift;
			see.summ = (char) (summ - r);
			_pEscFreq = r + (r == 0 ? 1 : 0);
		} else {
			see = DUMMY_SEE;
			_pEscFreq = 1;
		}
		return see;
	}

	// Ppmd7_NextContext
	private void nextContext() {
		long c = DecodePointer(State.getSuccessor(foundState));
		// (const Byte *)c > p->Text
		if (orderFall == 0 && c > text) {
			maxContext = minContext = c;
		} else {
			updateModel();
		}
	}

	// Ppmd7_Update1
	private void update1() {
		long s = foundState;
		int freq = State.getFrequency(s);
		freq += 4;
		Context.setSumFrequency(minContext, Context.getSumFrequency(minContext) + 4);
		State.setFrequency(s, freq);
		if (freq > State.getFrequency(s - State.STRUCT_SIZE)) {  // s[-1].Freq
			s = SWAP_STATES(s);
			foundState = s;
			if (freq > MAX_FREQ)
				rescale();
		}
		nextContext();
	}

	// Ppmd7_Update1_0
	private void update1_0() {
		long s = foundState;
		long mc = minContext;
		int freq = State.getFrequency(s);
		final int summFreq = Context.getSumFrequency(mc);
		prevSuccess = (2 * freq > summFreq) ? 1 : 0;
		runLength += prevSuccess;
		Context.setSumFrequency(mc, summFreq + 4);
		freq += 4;
		State.setFrequency(s, freq);
		if (freq > MAX_FREQ)
			rescale();

		nextContext();
	}

	// Ppmd7_Update2
	private void update2() {
		long s = foundState;
		int freq = State.getFrequency(s);
		freq += 4;
		runLength = initialRunLength;
		long mc = minContext;
		Context.setSumFrequency(mc, Context.getSumFrequency(mc) + 4);
		State.setFrequency(s, freq);
		if (freq > MAX_FREQ)
			rescale();

		updateModel();
	}

	// PPMD7_SYM_END and ERROR
	public static final int PPMD7_SYM_END = -1, PPMD7_SYM_ERROR = -2;

	private static int hiBitsFlag3(int sym) {return (((sym + 0xC0) >>> (8 - 3)) & (1 << 3));}
	private static int hiBitsFlag4(int sym) {return (((sym + 0xC0) >>> (8 - 4)) & (1 << 4));}

	private final /*aligned(sizeof(pointer))*/ byte[] charMask = new byte[256];

	public long getMemoryUsage() {
		long root = base + alignOffset;
		long textUsed = text - root;
		long loUnitUsed = loUnit - unitsStart;
		long hiUnitUsed = root + size - hiUnit;
		return textUsed + loUnitUsed + hiUnitUsed;
	}

	/**
	 * Encodes a single symbol.
	 */
	public void encodeSymbol(RangeEncoder rc, @Range(from = -1, to = 0xFF) int symbol) throws IOException {
		if (Context.getStateCount(minContext) != 1) {
			long s = DecodePointer(Context.getStateBase(minContext));

			rc.range = Integer.divideUnsigned(rc.range, Context.getSumFrequency(minContext));

			if (State.getSymbol(s) == symbol) {
				// R->Range /= p->MinContext->Union2.SummFreq;
				rc.encodeFinal(0, State.getFrequency(s));
				foundState = s;
				update1_0();
				return;
			}
			prevSuccess = 0;
			int sum = State.getFrequency(s);
			int i = Context.getStateCount(minContext) - 1;
			do {
				if (State.getSymbol(s += State.STRUCT_SIZE) == symbol) {
					// R->Range /= p->MinContext->Union2.SummFreq;
					rc.encodeFinal(sum, State.getFrequency(s));
					foundState = s;
					update1();
					return;
				}
				sum += State.getFrequency(s);
			} while (--i != 0);

			// R->Range /= p->MinContext->Union2.SummFreq;
			rc.encode(sum, Context.getSumFrequency(minContext) - sum);

			hiBitsFlag = hiBitsFlag3(State.getSymbol(foundState));
			Arrays.fill(charMask, (byte) 0xFF); // PPMD_SetAllBitsIn256Bytes(charMask)

			// MASK(s->Symbol) = 0;
			// i = p->MinContext->NumStats - 1;
			// do { MASK((--s)->Symbol) = 0; } while (--i);
			long s2 = DecodePointer(Context.getStateBase(minContext));
			charMask[State.getSymbol(s)] = 0;
			do {
				charMask[State.getSymbol(s2)] = 0;
				charMask[State.getSymbol(s2 + State.STRUCT_SIZE)] = 0;
				s2 += 2L * State.STRUCT_SIZE;
			} while (s2 < s);
		} else {
			char[] probArray = probs;
			int probIdx = getProbs();

			int pr = probArray[probIdx];
			final int bound = (rc.range >>> 14) * pr;
			pr = updateProb1(pr); // PPMD_UPDATE_PROB_1

			long s = Context.oneState(minContext);

			if (State.getSymbol(s) == symbol) {
				probArray[probIdx] = (char) (pr + (1 << PPMD_INT_BITS));
				// RangeEnc_EncodeBit_0(p, bound);
				rc.range = bound;
				rc.normalize();

				// Ppmd7_UpdateBin(p);
				foundState = s;
				prevSuccess = 1;
				runLength++;
				int freq = State.getFrequency(s);
				State.setFrequency(s, freq + (freq < 128 ? 1 : 0));

				nextContext();
				return;
			}

			probArray[probIdx] = (char) pr;
			initialEscape = EXP_ESCAPE[pr >>> 10];
			// RangeEnc_EncodeBit_1(p, bound);
			rc.low += Integer.toUnsignedLong(bound);
			rc.range -= bound;

			Arrays.fill(charMask, (byte) 0xFF); // PPMD_SetAllBitsIn256Bytes(charMask)
			charMask[State.getSymbol(s)] = 0;
			prevSuccess = 0;
		}

		for (;;) {
			rc.normalize();
			rc.normalize();

			long mc = minContext;
			int numMasked = Context.getStateCount(mc);

			int i;
			do {
				orderFall++;
				int suffix = Context.getSuffix(mc);
				if (suffix == 0)
					return; /* EndMarker (symbol = -1) */
				mc = DecodePointer(suffix);
				i = Context.getStateCount(mc);
			} while (i == numMasked);

			minContext = mc;

			See see = makeEscFreq(numMasked);
			int escFreq = _pEscFreq;

			long s = DecodePointer(Context.getStateBase(mc));
			int sum = 0;

			do {
				final int cur = State.getSymbol(s);
				if (cur == symbol) {
					final int low = sum;
					final int freq = State.getFrequency(s);

					see.update();
					foundState = s;
					sum += escFreq;

					int num2 = i >>> 1;
					i &= 1;
					sum += freq & -i;

					if (num2 != 0) {
						s += (long) i * State.STRUCT_SIZE;
						do {
							sum += (State.getFrequency(s) & charMask[State.getSymbol(s)]);
							s += State.STRUCT_SIZE;
							sum += (State.getFrequency(s) & charMask[State.getSymbol(s)]);
							s += State.STRUCT_SIZE;
						} while (--num2 != 0);
					}

					rc.range = Integer.divideUnsigned(rc.range, sum);
					rc.encodeFinal(low, freq);
					update2();
					return;
				}
				sum += (State.getFrequency(s) & charMask[cur]);
				s += State.STRUCT_SIZE;
			} while (--i != 0);

			{
				final int total = sum + escFreq;
				see.summ = (char) (see.summ + total);

				rc.range = Integer.divideUnsigned(rc.range, total);
				rc.encode(sum, escFreq);
			}

			s -= State.STRUCT_SIZE;
			charMask[State.getSymbol(s)] = 0;

			long s2 = DecodePointer(Context.getStateBase(minContext));
			do {
				charMask[State.getSymbol(s2)] = 0;
				s2 += State.STRUCT_SIZE;
				charMask[State.getSymbol(s2)] = 0;
				s2 += State.STRUCT_SIZE;
			} while (s2 < s);
		}
	}

	/**
	 * Decodes a single symbol.
	 * Returns: >=0 symbol, PPMD7_SYM_END (-1), PPMD7_SYM_ERROR (-2)
	 */
	public @Range(from = -2, to = 0xFF) int decodeSymbol(RangeDecoder rc) throws IOException {
		long mc = minContext; // MinContext

		if (Context.getStateCount(mc) != 1) {
			long s = DecodePointer(Context.getStateBase(mc));
			int summFreq = Context.getSumFrequency(mc);

			int count = rc.getThreshold(summFreq);
			int hiCnt = count;

			if ((count -= State.getFrequency(s)) < 0) {
				rc.decodeFinal(0, State.getFrequency(s));
				foundState = s;
				int sym = State.getSymbol(s);
				update1_0();
				return sym;
			}

			prevSuccess = 0;
			int i = Context.getStateCount(mc) - 1;

			do {
				s += State.STRUCT_SIZE;
				int frequency = State.getFrequency(s);

				if ((count -= frequency) < 0) {
					rc.decodeFinal((hiCnt - count) - frequency, frequency);
					foundState = s;
					int sym = State.getSymbol(s);
					update1();
					return sym;
				}
			} while (--i != 0);

			if (hiCnt >= summFreq)
				return PPMD7_SYM_ERROR;

			hiCnt -= count;
			rc.decode(hiCnt, summFreq - hiCnt);

			hiBitsFlag = hiBitsFlag3(State.getSymbol(foundState));
			Arrays.fill(charMask, (byte) 0xFF); // PPMD_SetAllBitsIn256Bytes

			// i = p->MinContext->NumStats - 1;
			// do { MASK((--s)->Symbol) = 0; } while (--i);
			long s2 = DecodePointer(Context.getStateBase(mc));
			charMask[State.getSymbol(s)] = 0;
			do {
				charMask[State.getSymbol(s2)] = 0;
				charMask[State.getSymbol(s2 + State.STRUCT_SIZE)] = 0;
				s2 += 2 * State.STRUCT_SIZE;
			} while (s2 < s);
		} else {
			long s = Context.oneState(mc);
			int probIndex = getProbs();
			char[] probArray = probs;

			int pr = probArray[probIndex];

			int size0 = (rc.range >>> 14) * pr;
			pr = updateProb1(pr); // PPMD_UPDATE_PROB_1

			if (Integer.compareUnsigned(rc.code, size0) < 0) {
				// Decode 0: the symbol
				probArray[probIndex] = (char) (pr + (1 << PPMD_INT_BITS));
				rc.range = size0;
				rc.fill(); // RC_NORM_1
				/* we can use single byte normalization here because of
         		   (min(BinSumm[][]) = 95) > (1 << (14 - 8)) */

				// Ppmd7_UpdateBin(p);
				foundState = s;
				prevSuccess = 1;
				runLength++;
				int freq = State.getFrequency(s);
				State.setFrequency(s, freq + (freq < 128 ? 1 : 0));
				int sym = State.getSymbol(s);

				nextContext();
				return sym;
			}

			probArray[probIndex] = (char) pr;
			initialEscape = EXP_ESCAPE[pr >>> 10];

			// RangeDec_DecodeBit1(size0);

			rc.code -= size0;
			rc.range -= size0;

			Arrays.fill(charMask, (byte) 0xFF); // PPMD_SetAllBitsIn256Bytes
			charMask[State.getSymbol(s)] = 0;
			prevSuccess = 0;
		}

		// Escape loop
		for (;;) {
			long s; // renamed to avoid conflict
			int hiCnt;

			rc.fill();
			rc.fill();

			long mc_ = minContext;
			int numMasked = Context.getStateCount(mc_);

			do {
				orderFall++;
				if (Context.getSuffix(mc_) == 0)
					return PPMD7_SYM_END;

				mc_ = DecodePointer(Context.getSuffix(mc_));
			} while (Context.getStateCount(mc_) == numMasked);

			s = DecodePointer(Context.getStateBase(mc_));

			{
				int num = Context.getStateCount(mc_);
				int num2 = num >>> 1;

				num &= 1;
				hiCnt = (State.getFrequency(s) & charMask[State.getSymbol(s)]) & -num;
				s += (long) num * State.STRUCT_SIZE;
				minContext = mc_;

				do {
					hiCnt += (State.getFrequency(s) & charMask[State.getSymbol(s)]);
					s += State.STRUCT_SIZE;
					hiCnt += (State.getFrequency(s) & charMask[State.getSymbol(s)]);
					s += State.STRUCT_SIZE;
				} while (--num2 != 0);
			}

			// Get escFreq
			See see = makeEscFreq(numMasked);
			int freqSum = _pEscFreq + hiCnt;

			int count = rc.getThreshold(freqSum);
			if (count < hiCnt) {
				s = DecodePointer(Context.getStateBase(minContext));
				hiCnt = count;

				int frequency;
				while (true) {
					frequency = State.getFrequency(s);
					count -= frequency & charMask[State.getSymbol(s)];
					if (count < 0) break;
					s += State.STRUCT_SIZE;
				}

				rc.decodeFinal((hiCnt - count) - frequency, frequency);

				// new (see->Summ) value can overflow over 16-bits in some rare cases
				see.update();

				foundState = s;
				int sym = State.getSymbol(s);
				update2();
				return sym;
			}

			if (count >= freqSum)
				return PPMD7_SYM_ERROR;

			rc.decode(hiCnt, freqSum - hiCnt);

			// We increase (see->Summ) for sum of Freqs of all non_Masked symbols.
			// new (see->Summ) value can overflow over 16-bits in some rare cases
			see.summ = (char) (see.summ + freqSum);

			// Clear masks for all symbols in this level
			s = DecodePointer(Context.getStateBase(minContext));
			long s2 = s + (long) Context.getStateCount(minContext) * State.STRUCT_SIZE;
			do {
				charMask[State.getSymbol(s)] = 0;
				s += State.STRUCT_SIZE;
			} while (s != s2);
		}
	}

	private int getProbs() {
		return (State.getFrequency(Context.oneState(minContext)) - 1) * BIN_SUMM_COLS
				+ prevSuccess + ((runLength >>> 26) & 0x20)
				+ NS2BSIndex[Context.getStateCount(DecodePointer(Context.getSuffix(minContext))) - 1]
				+ hiBitsFlag4(State.getSymbol(Context.oneState(minContext)))
				+ (hiBitsFlag = hiBitsFlag3(State.getSymbol(foundState)));
	}
}
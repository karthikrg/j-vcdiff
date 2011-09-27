package com.googlecode.jvcdiff;

import static com.googlecode.jvcdiff.VCDiffCodeTableData.VCD_COPY;
import static com.googlecode.jvcdiff.VCDiffCodeTableData.VCD_ADD;
import static com.googlecode.jvcdiff.VCDiffCodeTableData.kNoOpcode;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author David Ehrmann
 * The method calls after construction *must* conform
 * to the following pattern:
 *    {{Add|Copy|Run}* [AddChecksum] Output}*
 *
 * When Output has been called in this sequence, a complete target window
 * (as defined in RFC 3284 section 4.3) will have been appended to
 * out (unless no calls to Add, Run, or Copy were made, in which
 * case Output will do nothing.)  The output will not be available for use
 * until after each call to Output().
 *
 * NOT threadsafe.
 */
public class VCDiffCodeTableWriter implements CodeTableWriterInterface<OutputStream> {

	private static final byte[] kHeaderStandardFormat = new byte[] {
		(byte)0xD6, // Header1: "V" | 0x80
		(byte)0xC3, // Header2: "C" | 0x80
		(byte)0xC4, // Header3: "D" | 0x80
		(byte)0x00, // Header4: Draft standard format
		(byte)0x00	// Hdr_Indicator: No compression, no custom code table
	};

	private static final byte[] kHeaderExtendedFormat = new byte[] {
		(byte)0xD6, // Header1: "V" | 0x80
		(byte)0xC3, // Header2: "C" | 0x80
		(byte)0xC4, // Header3: "D" | 0x80
		(byte)'S',  // Header4: VCDIFF/SDCH, extensions used
		(byte)0x00	// Hdr_Indicator: No compression, no custom code table
	};  

	protected static final int VCD_SOURCE = 0x01;
	// private static final int VCD_TARGET = 0x02;
	protected static final int VCD_CHECKSUM = 0x04;
	
	
	/**
	 * The maximum value for the mode of a COPY instruction.
	 */
	private final int max_mode_;

	// A series of instruction opcodes, each of which may be followed
	// by one or two Varint values representing the size parameters
	// of the first and second instruction in the opcode.
	// TODO: replace with a Vector-like data structure
	private ByteBuffer instructions_and_sizes_ = ByteBuffer.allocate(1024 * 1024);

	// A series of data arguments (byte values) used for ADD and RUN
	// instructions.  Depending on whether interleaved output is used
	// for streaming or not, the pointer may point to
	// separate_data_for_add_and_run_ or to instructions_and_sizes_.
	// TODO:
	private ByteBuffer data_for_add_and_run_;
	private final ByteBuffer separate_data_for_add_and_run_ = ByteBuffer.allocate(128 * 1024);

	// A series of Varint addresses used for COPY instructions.
	// For the SAME mode, a byte value is stored instead of a Varint.
	// Depending on whether interleaved output is used
	// for streaming or not, the pointer may point to
	// separate_addresses_for_copy_ or to instructions_and_sizes_.
	// TODO:
	private ByteBuffer addresses_for_copy_;
	private final ByteBuffer separate_addresses_for_copy_ = ByteBuffer.allocate(128 * 1024);

	private final VCDiffAddressCache address_cache_;

	private int dictionary_size_;

	// The number of bytes of target data that has been encoded so far.
	// Each time Add(), Copy(), or Run() is called, this will be incremented.
	// The target length is used to compute HERE mode addresses
	// for COPY instructions, and is also written into the header
	// of the delta window when Output() is called.
	//
	private int target_length_;

	private final VCDiffCodeTableData code_table_data_;

	// The instruction map facilitates finding an opcode quickly given an
	// instruction inst, size, and mode.  This is an alternate representation
	// of the same information that is found in code_table_data_.
	private VCDiffInstructionMap instruction_map_;

	// The zero-based index within instructions_and_sizes_ of the byte
	// that contains the last single-instruction opcode generated by
	// EncodeInstruction().  (See that function for exhaustive details.)
	// It is necessary to use an index rather than a pointer for this value
	// because instructions_and_sizes_ may be resized, which would invalidate
	// any pointers into its data buffer.  The value -1 is reserved to mean that
	// either no opcodes have been generated yet, or else the last opcode
	// generated was a double-instruction opcode.
	private int last_opcode_index_;

	// If true, an Adler32 checksum of the target window data will be written as
	// a variable-length integer, just after the size of the addresses section.
	private boolean add_checksum_;

	// The checksum to be written to the current target window,
	// if add_checksum_ is true.
	// This will not be calculated based on the individual calls to Add(), Run(),
	// and Copy(), which would be unnecessarily expensive.  Instead, the code
	// that uses the VCDiffCodeTableWriter object is expected to calculate
	// the checksum all at once and to call AddChecksum() with that value.
	// Must be called sometime before calling Output(), though it can be called
	// either before or after the calls to Add(), Run(), and Copy().
	private long checksum_;

	/**
	 * This constructor uses the default code table.
	 * If interleaved is true, the encoder writes each delta file window
	 * by interleaving instructions and sizes with their corresponding
	 * addresses and data, rather than placing these elements into three
	 * separate sections.  This facilitates providing partially
	 * decoded results when only a portion of a delta file window
	 * is received (e.g. when HTTP over TCP is used as the
	 * transmission protocol.)  The interleaved format is
	 * not consistent with the VCDIFF draft standard.
	 * 
	 * @param interleaved Whether or not to interleave the output data
	 */
	public VCDiffCodeTableWriter(boolean interleaved) {
		max_mode_ = VCDiffAddressCache.DefaultLastMode();
		dictionary_size_ = 0;
		target_length_ = 0;
		code_table_data_ = VCDiffCodeTableData.kDefaultCodeTableData;
		instruction_map_ = null;
		last_opcode_index_ = -1;
		add_checksum_ = false;
		checksum_ = 0;
		address_cache_ = new VCDiffAddressCacheImpl();
		InitSectionPointers(interleaved);
	}

	/**
	 * 
	 * Uses a non-standard code table and non-standard cache sizes.  The caller
	 * must guarantee that code_table_data remains allocated for the lifetime of
	 * the VCDiffCodeTableWriter object.  Note that this is different from how
	 * VCDiffCodeTableReader::UseCodeTable works.  It is assumed that a given
	 * encoder will use either the default code table or a statically-defined
	 * non-standard code table, whereas the decoder must have the ability to read
	 * an arbitrary non-standard code table from a delta file and discard it once
	 * the file has been decoded.
	 * 
	 * @param interleaved
	 * @param near_cache_size
	 * @param same_cache_size
	 */
	public VCDiffCodeTableWriter(boolean interleaved, short near_cache_size, short same_cache_size, VCDiffCodeTableData code_table_data, short max_mode) {
		address_cache_ = new VCDiffAddressCacheImpl(near_cache_size, same_cache_size);
		dictionary_size_ = 0;
		target_length_ = 0;
		code_table_data_ = code_table_data;
		instruction_map_ = null;
		last_opcode_index_ = -1;
		add_checksum_ = false;
		checksum_ = 0;
		max_mode_ = max_mode;
		InitSectionPointers(interleaved);
	}

	/**
	 * Initializes the constructed object for use.
	 * This method must be called after a VCDiffCodeTableWriter is constructed
	 * and before any of its other methods can be called.  It will return
	 * false if there was an error initializing the object, or true if it
	 *  was successful.  After the object has been initialized and used,
	 * Init() can be called again to restore the initial state of the object.
	 */
	public void Init(int dictionary_size) {
		dictionary_size_ = dictionary_size;
		if (instruction_map_ == null) {
			if (code_table_data_ == VCDiffCodeTableData.kDefaultCodeTableData) {
				instruction_map_ = VCDiffInstructionMap.DEFAULT_INSTRUCTION_MAP;
			} else {
				instruction_map_ = new VCDiffInstructionMap(code_table_data_, (byte)max_mode_);
			}
		}

		address_cache_.Init();

		target_length_ = 0;
		last_opcode_index_ = -1;
	}

	/**
	 *  Encode an ADD opcode with the "size" bytes starting at data
	 */
	public void Add(byte[] data, int offset, int length) {
		if (offset + length > data.length || length < offset) {
			throw new IllegalArgumentException();
		}

		EncodeInstruction(VCDiffCodeTableData.VCD_ADD, length - offset);
		data_for_add_and_run_.put(data, offset, length);
		target_length_ += length - offset;
	}

	public void AddChecksum(int checksum) {
		add_checksum_ = true;
		checksum_ = checksum;
	}

	/**
	 *  Encode a COPY opcode with args "offset" (into dictionary) and "size" bytes.
	 */
	public void Copy(int offset, int size) {
		if (instruction_map_ == null) {
			throw new IllegalStateException("Copy called without calling Init().");
		}

		// If a single interleaved stream of encoded values is used
		// instead of separate sections for instructions, addresses, and data,
		// then the string instructions_and_sizes_ may be the same as
		// addresses_for_copy_.  The address should therefore be encoded
		// *after* the instruction and its size.
		AtomicInteger encoded_addr = new AtomicInteger(0);
		final byte mode = (byte)address_cache_.EncodeAddress(offset, dictionary_size_ + target_length_, encoded_addr);
		EncodeInstruction(VCD_COPY, size, mode);
		if (address_cache_.WriteAddressAsVarintForMode(mode)) {
			VarInt.putInt(addresses_for_copy_, encoded_addr.get());
		} else {
			addresses_for_copy_.put((byte)encoded_addr.get());
		}
		target_length_ += size;
	}

	/**
	 * There should not be any need to output more data
	 * since EncodeChunk() encodes a complete target window
	 * and there is no end-of-delta-file marker.
	 */
	public void FinishEncoding(OutputStream out) throws IOException {

	}

	public int getDeltaWindowSize() {
		final int length_of_the_delta_encoding = CalculateLengthOfTheDeltaEncoding();
		final int delta_window_size =
			length_of_the_delta_encoding +
			1 +  // Win_Indicator
			CalculateLengthOfSizeAsVarint(dictionary_size_) +
			CalculateLengthOfSizeAsVarint(0) +
			CalculateLengthOfSizeAsVarint(length_of_the_delta_encoding);

		return delta_window_size;
	}

	/**
	 * Appends the encoded delta window to the output
	 * string.  The output string is not null-terminated and may contain embedded
	 * '\0' characters.
	 */
	public void Output(OutputStream out2) throws IOException {
		if (instructions_and_sizes_.position() == 0) {
			// "Empty input; no delta window produced"
		} else {
			CountingOutputStream out = new CountingOutputStream(out2);
			
			// Add first element: Win_Indicator
			if (add_checksum_) {
				out.write(VCD_SOURCE | VCD_CHECKSUM);
			} else {
				out.write(VCD_SOURCE);
			}

			// Source segment size: dictionary size
			VarInt.writeInt(out, dictionary_size_);
			
			// Source segment position: 0 (start of dictionary)
			VarInt.writeInt(out, 0);

			// [Here is where a secondary compressor would be used
			//  if the encoder and decoder supported that feature.]

			final int length_of_the_delta_encoding = this.CalculateLengthOfTheDeltaEncoding();
			
			VarInt.writeInt(out, length_of_the_delta_encoding);
			
			// Start of Delta Encoding
			final int size_before_delta_encoding = out.bytesWritten;
			
			VarInt.writeInt(out, target_length_);
			out.write(0x00);  // Delta_Indicator: no compression
			VarInt.writeInt(out, separate_data_for_add_and_run_.position());
			VarInt.writeInt(out, instructions_and_sizes_.position());
			VarInt.writeInt(out, separate_addresses_for_copy_.position());
			if (add_checksum_) {
				// The checksum is a 32-bit *unsigned* integer.  VarintBE requires a
				// signed type, so use a 64-bit signed integer to store the checksum.
				VarInt.writeLong(out, checksum_);
			}
			
			out.write(separate_data_for_add_and_run_.array(), separate_data_for_add_and_run_.arrayOffset(), separate_data_for_add_and_run_.position());
			out.write(instructions_and_sizes_.array(), instructions_and_sizes_.arrayOffset(), instructions_and_sizes_.position());
			out.write(separate_addresses_for_copy_.array(), separate_addresses_for_copy_.arrayOffset(), separate_addresses_for_copy_.position());
			
			// End of Delta Encoding
			final int size_after_delta_encoding = out.bytesWritten;
			if (length_of_the_delta_encoding != (size_after_delta_encoding - size_before_delta_encoding)) {
				String.format("Internal error: calculated length of the delta encoding (%d) does not match actual length (%d)",
						length_of_the_delta_encoding, (size_after_delta_encoding - size_before_delta_encoding));
			}
			separate_data_for_add_and_run_.clear();
			instructions_and_sizes_.clear();
			separate_addresses_for_copy_.clear();
			if (target_length_ == 0) {
				String.format("Empty target window");
			}
		}

		// Reset state for next window; assume we are using same code table
		// and dictionary.  The caller will have to invoke Init() if a different
		// dictionary is used.
		//
		// Notably, Init() calls address_cache_.Init().  This resets the address
		// cache between delta windows, as required by RFC section 5.1.
		Init(dictionary_size_);
	}

	/**
	 *  Encode a RUN opcode for "size" copies of the value "byte".
	 */
	public void Run(int size, byte b) {
		EncodeInstruction(VCDiffCodeTableData.VCD_RUN, size);
		data_for_add_and_run_.put(b);
		target_length_ += size;
	}

	/**
	 * Write the header (as defined in section 4.1 of the RFC) to *out.
	 * This includes information that can be gathered
	 * before the first chunk of input is available.
	 */
	public void WriteHeader(OutputStream out, EnumSet<VCDiffFormatExtensionFlags> formatExtensions) throws IOException {
		if (formatExtensions.contains(VCDiffFormatExtensionFlags.VCD_STANDARD_FORMAT) && formatExtensions.size() == 1) {
			out.write(kHeaderStandardFormat);
		} else {
			out.write(kHeaderExtendedFormat);
		}

		// If custom cache table sizes or a custom code table were used
		// for encoding, here is where they would be appended to *output.
		// This implementation of the encoder does not use those features,
		// although the decoder can understand and interpret them.
	}

	public int target_length() {
		return target_length_;
	}

	/**
	 * If interleaved is true, sets data_for_add_and_run_ and
	 * addresses_for_copy_ to point at instructions_and_sizes_,
	 * so that instructions, sizes, addresses and data will be
	 * combined into a single interleaved stream.
	 * If interleaved is false, sets data_for_add_and_run_ and
	 * addresses_for_copy_ to point at their corresponding
	 * separate_... strings, so that the three sections will
	 * be generated separately from one another.
	 */
	void InitSectionPointers(boolean interleaved) {
		if (interleaved) {
			data_for_add_and_run_ = instructions_and_sizes_;
			addresses_for_copy_ = instructions_and_sizes_;
		} else {
			data_for_add_and_run_ = separate_data_for_add_and_run_;
			addresses_for_copy_ = separate_addresses_for_copy_;
		}
	}

	// Determines the best opcode to encode an instruction, and appends
	// or substitutes that opcode and its size into the
	// instructions_and_sizes_ string.
	private void EncodeInstruction(byte inst, int size, byte mode) {
		if (instruction_map_ == null) {
			throw new IllegalStateException("EncodeInstruction() called without calling Init()");
		}

		if (last_opcode_index_ >= 0) {
			final byte last_opcode = instructions_and_sizes_.get(last_opcode_index_);
			// The encoding engine should not generate two ADD instructions in a row.
			// This won't cause a failure, but it's inefficient encoding and probably
			// represents a bug in the higher-level logic of the encoder.
			if (inst == VCD_ADD && code_table_data_.inst1[last_opcode & 0xff] == VCD_ADD) {
				// VCD_WARNING << "EncodeInstruction() called for two ADD instructions in a row" << VCD_ENDL;
			}

			short compound_opcode = kNoOpcode;
			if (size <= 255) {
				compound_opcode = instruction_map_.LookupSecondOpcode(last_opcode, inst, (byte)size, mode);
				if (compound_opcode != kNoOpcode) {
					instructions_and_sizes_.put(last_opcode_index_, (byte)compound_opcode);
					last_opcode_index_ = -1;
					return;
				}
			}

			// Try finding a compound opcode with size 0.
			compound_opcode = instruction_map_.LookupSecondOpcode(last_opcode, inst, (byte)0, mode);
			if (compound_opcode != kNoOpcode) {
				instructions_and_sizes_.put(last_opcode_index_, (byte)compound_opcode);
				last_opcode_index_ = -1;
				VarInt.putInt(instructions_and_sizes_, size);
				return;
			}
		}

		short opcode = kNoOpcode;
		if (size <= 255) {
			opcode = instruction_map_.LookupFirstOpcode(inst, (byte)size, mode);
			if (opcode != kNoOpcode) {
				instructions_and_sizes_.put((byte)opcode);
				last_opcode_index_ = instructions_and_sizes_.position() - 1;
				return;
			}
		}

		// There should always be an opcode with size 0.
		opcode = instruction_map_.LookupFirstOpcode(inst, (byte)0, mode);
		if (opcode == kNoOpcode) {
			String.format("No matching opcode found for inst %d, mode %d, size 0", inst, mode);
			return;
		}

		instructions_and_sizes_.put((byte)opcode);
		last_opcode_index_ = instructions_and_sizes_.position() - 1;
		VarInt.putInt(instructions_and_sizes_, size);
	}

	private void EncodeInstruction(byte inst, int size) {
		EncodeInstruction(inst, size, (byte)0);
	}

	/**
	 * Calculates the number of bytes needed to store the given size value as a
	 * variable-length integer (VarintBE).
	 */
	static int CalculateLengthOfSizeAsVarint(int size) {
		return VarInt.calculateIntLength(size);
	}

	// Calculates the "Length of the delta encoding" field for the delta window
	// header, based on the sizes of the sections and of the other header
	// elements.
	private int CalculateLengthOfTheDeltaEncoding() {
		int length_of_the_delta_encoding =
			CalculateLengthOfSizeAsVarint(target_length_) +
			1 +  // Delta_Indicator
			CalculateLengthOfSizeAsVarint(separate_data_for_add_and_run_.position()) +
			CalculateLengthOfSizeAsVarint(instructions_and_sizes_.position()) +
			CalculateLengthOfSizeAsVarint(separate_addresses_for_copy_.position()) +
			separate_data_for_add_and_run_.position() +
			instructions_and_sizes_.position() +
			separate_addresses_for_copy_.position();
		if (add_checksum_) {
			length_of_the_delta_encoding += VarInt.calculateLongLength(checksum_);
		}

		return length_of_the_delta_encoding;
	}
	
	private static class CountingOutputStream extends FilterOutputStream {
		int bytesWritten = 0;
		
		public CountingOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			super.write(b, off, len);
			bytesWritten += len;
		}

		@Override
		public void write(byte[] b) throws IOException {
			super.write(b);
			bytesWritten += b.length;
		}

		@Override
		public void write(int b) throws IOException {
			super.write(b);
			bytesWritten++;
		}
	}
}
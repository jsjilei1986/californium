/*******************************************************************************
 * Copyright (c) 2015, 2017 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Stefan Jucker - DTLS implementation
 *    Kai Hudalla (Bosch Software Innovations GmbH) - expose sequence number as
 *                   property of type long in order to prevent tedious conversions
 *                   in client code
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add initial support for Block Ciphers
 *    Achim Kraus (Bosch Software Innovations GmbH) - add isNewClientHello
 *    Bosch Software Innovations GmbH - migrate to SLF4J
 *    Achim Kraus (Bosch Software Innovations GmbH) - use handshake parameter and
 *                                                    generic handshake messages to
 *                                                    process reordered handshake messages
 ******************************************************************************/
package org.eclipse.californium.scandium.dtls;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;

import org.eclipse.californium.elements.util.DatagramReader;
import org.eclipse.californium.elements.util.DatagramWriter;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.scandium.dtls.cipher.CCMBlockCipher;
import org.eclipse.californium.scandium.dtls.cipher.CipherManager;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.cipher.InvalidMacException;
import org.eclipse.californium.scandium.util.ByteArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An object representation of the DTLS <em>Record</em> layer data structure(s).
 * <p>
 * The <em>Datagram Transport Layer Security</em> specification defines
 * a set of data structures at the <a href="http://tools.ietf.org/html/rfc6347#section-4.3.1">
 * Record</a> layer containing the data to be exchanged with peers.
 * <p>
 * This class is used to transform these data structures from their binary encoding
 * as received from the network interface to their object representation and vice versa.
 */
public class Record {

	// Logging ////////////////////////////////////////////////////////

	private static final Logger LOGGER = LoggerFactory.getLogger(Record.class.getCanonicalName());

	// DTLS specific constants/////////////////////////////////////////

	public static final int CONTENT_TYPE_BITS = 8;

	public static final int VERSION_BITS = 8; // for major and minor each

	public static final int EPOCH_BITS = 16;

	public static final int SEQUENCE_NUMBER_BITS = 48;

	public static final int LENGTH_BITS = 16;
	
	public static final int CID_LENGTH_BITS = 8;

	public static final int RECORD_HEADER_BITS = CONTENT_TYPE_BITS + VERSION_BITS + VERSION_BITS +
			EPOCH_BITS + SEQUENCE_NUMBER_BITS + LENGTH_BITS;

	private static final long MAX_SEQUENCE_NO = 281474976710655L; // 2^48 - 1

	// Members ////////////////////////////////////////////////////////

	/** The higher-level protocol used to process the enclosed fragment */
	private ContentType type = null;

	/**
	 * The version of the protocol being employed. DTLS version 1.2 uses { 254,
	 * 253 }
	 */
	private ProtocolVersion version = new ProtocolVersion();

	/** A counter value that is incremented on every cipher state change */
	private int epoch = -1;

	/** The sequence number for this record */
	private long sequenceNumber;

	/** The length (in bytes) of the following {@link DTLSMessage} as bytes. */
	private int fragmentLength = 0;

	/**
	 * The application data. This data is transparent and treated as an
	 * independent block to be dealt with by the higher-level protocol specified
	 * by the type field.
	 */
	private DTLSMessage fragment = null;

	/** The raw byte representation of the fragment. */
	private byte[] fragmentBytes = null;

	/** The connection id. */
	private ConnectionId connectionId = null;

	/** padding to be used, if cid is used */
	private int padding = 0;

	/** The DTLS session. */
	private DTLSSession session;

	/** The peer address. */
	private InetSocketAddress peerAddress;

	// Constructors ///////////////////////////////////////////////////

	/**
	 * Creates a record from a <em>DTLSCiphertext</em> struct received from the network.
	 * 
	 * Called when reconstructing the record from a byte array. The fragment
	 * will remain in its binary representation up to the DTLS Layer.
	 * 
	 * @param type the content type
	 * @param version the version
	 * @param epoch the epoch
	 * @param sequenceNumber the sequence number
	 * @param connectionId the connection id
	 * @param fragmentBytes the encrypted data
	 */
	Record(ContentType type, ProtocolVersion version, int epoch, long sequenceNumber, ConnectionId connectionId, byte[] fragmentBytes,
			InetSocketAddress peerAddress) {
		this(type, epoch, sequenceNumber);
		this.version = version;
		this.connectionId = connectionId;
		this.fragmentBytes = fragmentBytes;
		this.fragmentLength = fragmentBytes.length;
		this.peerAddress = peerAddress;
	}

	/**
	 * Creates an outbound record containing a {@link DTLSMessage} as its payload.
	 * 
	 * The given <em>fragment</em> is encoded into its binary representation 
	 * and encrypted according to the given session's current write state.
	 * In order to create a <code>Record</code> containing an un-encrypted fragment, use the
	 * {@link #Record(ContentType, long, DTLSMessage, InetSocketAddress)} constructor.
	 * 
	 * @param type
	 *            the type of the record's payload
	 * @param epoch
	 *            the epoch
	 * @param sequenceNumber
	 *            the 48-bit sequence number
	 * @param fragment
	 *            the payload
	 * @param session
	 *            the session to determine the current write state from
	 * @param cid 
	 *            if {@code true} use write connection id from provided session.
	 *            Otherwise use {@code null} as connection id
	 * @param pad 
	 *            if cid is enabled, pad could be used to add that number of 
	 *            zero-bytes as padding to the payload to obfuscate the payload length
	 * @throws NullPointerException if the given session is <code>null</code>
	 * @throws IllegalArgumentException if the given sequence number is longer than 48 bits
	 * @throws GeneralSecurityException if the message could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */
	public Record(ContentType type, int epoch, long sequenceNumber, DTLSMessage fragment, DTLSSession session,
			boolean cid, int pad) throws GeneralSecurityException {
		this(type, epoch, sequenceNumber);
		if (session == null) {
			throw new NullPointerException("Session must not be null");
		}
		this.fragment = fragment;
		this.session = session;
		this.peerAddress = session.getPeer();
		if (cid) {
			this.connectionId = session.getWriteConnectionId();
			this.padding = pad;
		}
		setFragment(fragment);
	}

	/**
	 * Creates a record representing a {@link DTLSMessage} as its payload.
	 * 
	 * The payload will be sent un-encrypted using epoch 0.
	 * 
	 * @param type
	 *            the type of the record's payload
	 * @param sequenceNumber
	 *            the 48-bit sequence number
	 * @param fragment
	 *            the payload to send
	 * @param peerAddress
	 *            the IP address and port of the peer this record should be sent to
	 * @throws IllegalArgumentException if the given sequence number is longer than 48 bits
	 */
	public Record(ContentType type, long sequenceNumber, DTLSMessage fragment, InetSocketAddress peerAddress) {
		this(type, 0, sequenceNumber);
		this.peerAddress = peerAddress;
		try {
			setFragment(fragment);
		} catch (GeneralSecurityException e) {
			// cannot happen because we do not have a session
			LOGGER.warn("Unexpected attempt to encrypt outbound record payload", e);
		}
	}

	private Record(ContentType type, int epoch, long sequenceNumber) {
		if (sequenceNumber > MAX_SEQUENCE_NO) {
			throw new IllegalArgumentException("Sequence number must be 48 bits only");
		}
		this.type = type;
		this.epoch = epoch;
		this.sequenceNumber = sequenceNumber;
	}

	// Serialization //////////////////////////////////////////////////

	/**
	 * Encodes this record into its corresponding <em>DTLSCiphertext</em> structure.
	 * 
	 * @return a byte array containing the <em>DTLSCiphertext</em> structure
	 */
	public byte[] toByteArray() {
		DatagramWriter writer = new DatagramWriter();

		if (useConnectionId()) {
			writer.write(ContentType.TLS12_CID.getCode(), CONTENT_TYPE_BITS);
		} else {
			writer.write(type.getCode(), CONTENT_TYPE_BITS);
		}

		writer.write(version.getMajor(), VERSION_BITS);
		writer.write(version.getMinor(), VERSION_BITS);

		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);
		if (useConnectionId()) {
			writer.writeBytes(connectionId.getBytes());
		}
		fragmentLength = fragmentBytes.length;
		writer.write(fragmentLength, LENGTH_BITS);

		writer.writeBytes(fragmentBytes);

		return writer.toByteArray();
	}

	/**
	 * Parses a sequence of <em>DTLSCiphertext</em> structures into <code>Record</code> instances.
	 * 
	 * The binary representation is expected to comply with the <em>DTLSCiphertext</em> structure
	 * defined in <a href="http://tools.ietf.org/html/rfc6347#section-4.3.1">RFC6347, Section 4.3.1</a>.
	 * 
	 * @param byteArray the raw binary representation containing one or more DTLSCiphertext strctures
	 * @param peerAddress the IP address and port of the peer from which the bytes have been
	 *           received
	 * @return the <code>Record</code> instances
	 * @throws NullPointerException if either one of the byte array or peer address is <code>null</code>
	 */
	public static List<Record> fromByteArray(byte[] byteArray, InetSocketAddress peerAddress, Integer connectionIdLength) {
		if (byteArray == null) {
			throw new NullPointerException("Byte array must not be null");
		} else if (peerAddress == null) {
			throw new NullPointerException("Peer address must not be null");
		}

		List<Record> records = new ArrayList<Record>();

		DatagramReader reader = new DatagramReader(byteArray);

		while (reader.bytesAvailable()) {

			if (reader.bitsLeft() < RECORD_HEADER_BITS) {
				LOGGER.debug("Received truncated DTLS record(s). Discarding ...");
				return records;
			}

			int type = reader.read(CONTENT_TYPE_BITS);
			int major = reader.read(VERSION_BITS);
			int minor = reader.read(VERSION_BITS);
			ProtocolVersion version = new ProtocolVersion(major, minor);

			int epoch = reader.read(EPOCH_BITS);
			long sequenceNumber = reader.readLong(SEQUENCE_NUMBER_BITS);

			ConnectionId connectionId = null;
			if (type == ContentType.TLS12_CID.getCode()) {
				if (connectionIdLength == null) {
					LOGGER.debug("Received DTLS record of type [{}], but connection is not used. Discarding ...", type);
					continue;
				} else {
					connectionId = new ConnectionId(reader.readBytes(connectionIdLength));
				}
			}
			int length = reader.read(LENGTH_BITS);

			if (reader.bitsLeft() < length) {
				LOGGER.debug("Received truncated DTLS record(s). Discarding ...");
				return records;
			}

			// delay decryption/interpretation of fragment
			byte[] fragmentBytes = reader.readBytes(length);

			ContentType contentType = ContentType.getTypeByValue(type);
			if (contentType == null) {
				LOGGER.debug("Received DTLS record of unsupported type [{}]. Discarding ...", type);
			} else {
				records.add(new Record(contentType, version, epoch, sequenceNumber, connectionId, fragmentBytes,
						peerAddress));
			}
		}

		return records;
	}

	// Cryptography /////////////////////////////////////////////////////////

	/**
	 * Encrypts a TLSPlaintext.fragment according to the <em>current</em> DTLS connection state.
	 * 
	 * @param plaintextFragment
	 *            the TLSPlaintext.fragment to encrypt
	 * @return the (encrypted) TLSCiphertext.fragment
	 * @throws GeneralSecurityException if the plaintext could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */
	private byte[] encryptFragment(byte[] plaintextFragment) throws GeneralSecurityException {

		if (session == null || epoch == 0) {
			return plaintextFragment;
		}

		byte[] encryptedFragment = plaintextFragment;

		CipherSuite cipherSuite = session.getWriteState().getCipherSuite();
		LOGGER.trace("Encrypting record fragment using current write state{}{}", StringUtil.lineSeparator(), session.getWriteState());

		switch (cipherSuite.getCipherType()) {
		case NULL:
			// do nothing
			break;
			
		case AEAD:
			encryptedFragment = encryptAEAD(plaintextFragment);
			break;
			
		case BLOCK:
			encryptedFragment = encryptBlockCipher(plaintextFragment);
			break;
			
		case STREAM:
			// Currently, Scandium does not support any stream ciphers
			// RC4 is explicitly ruled out from being used in DTLS
			// see http://tools.ietf.org/html/rfc6347#section-4.1.2.2
			break;

		default:
			break;
		}

		return encryptedFragment;
	}

	/**
	 * Decrypts a TLSCiphertext.fragment according to the <em>current</em> DTLS connection state.
	 * 
	 * So, potentially no decryption takes place at all.
	 * 
	 * @param ciphertextFragment
	 *            the TLSCiphertext.fragment to decrypt
	 * @param currentReadState the encryption params to use, if <code>null</code>
	 *           the fragment is assumed to already be plaintext and is thus returned <em>as is</em>
	 * @return the (de-crypted) TLSPlaintext.fragment
	 * @throws GeneralSecurityException
	 *             if de-cryption fails, e.g. because the MAC could not be validated.
	 */
	private byte[] decryptFragment(byte[] ciphertextFragment, final DTLSConnectionState currentReadState) throws GeneralSecurityException {
		if (currentReadState == null) {
			return ciphertextFragment;
		}

		byte[] result = ciphertextFragment;

		CipherSuite cipherSuite = currentReadState.getCipherSuite();
		LOGGER.trace("Decrypting record fragment using current read state{}{}", StringUtil.lineSeparator(), currentReadState);

		switch (cipherSuite.getCipherType()) {
		case NULL:
			// do nothing
			break;
			
		case AEAD:
			result = decryptAEAD(ciphertextFragment, currentReadState);
			break;
			
		case BLOCK:
			result = decryptBlockCipher(ciphertextFragment, currentReadState);
			break;
			
		case STREAM:
			// Currently, Scandium does not support any stream ciphers
			// RC4 is explicitly ruled out from being used in DTLS
			// see http://tools.ietf.org/html/rfc6347#section-4.1.2.2
			break;

		default:
			break;
		}

		return result;
	}

	// Block Cipher Cryptography //////////////////////////////////////

	/**
	 * Converts a given TLSCompressed.fragment to a
	 * TLSCiphertext.fragment structure as defined by
	 * <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.2">
	 * RFC 5246, section 6.2.3.2</a>
	 * 
	 * <pre>
	 * struct {
	 *    opaque IV[SecurityParameters.record_iv_length];
	 *    block-ciphered struct {
	 *       opaque content[TLSCompressed.length];
	 *       opaque MAC[SecurityParameters.mac_length];
	 *       uint8 padding[GenericBlockCipher.padding_length];
	 *       uint8 padding_length;
	 *    };
	 * } GenericBlockCipher;
	 * </pre>
	 * 
	 * The particular cipher to use is determined from the negotiated
	 * cipher suite in the <em>current</em> DTLS connection state.
	 *  
	 * @param compressedFragment the TLSCompressed.fragment
	 * @return the TLSCiphertext.fragment
	 * @throws NullPointerException if the given fragment is <code>null</code>
	 * @throws IllegalStateException if the {@link #session} is not
	 * @throws GeneralSecurityException if the JVM does not support the negotiated block cipher
	 */
	protected final byte[] encryptBlockCipher(byte[] compressedFragment) throws GeneralSecurityException {
		if (session == null) {
			throw new IllegalStateException("DTLS session must be set on record");
		} else if (compressedFragment == null) {
			throw new NullPointerException("Compressed fragment must not be null");
		}

		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.2 for
		 * explanation
		 */
		DatagramWriter plaintext = new DatagramWriter();
		plaintext.writeBytes(compressedFragment);

		// add MAC
		plaintext.writeBytes(getBlockCipherMac(session.getWriteState(), compressedFragment));

		// determine padding length
		int ciphertextLength = compressedFragment.length + session.getWriteState().getCipherSuite().getMacLength() + 1;
		int smallestMultipleOfBlocksize = session.getWriteState().getRecordIvLength();
		while ( smallestMultipleOfBlocksize <= ciphertextLength) {
			smallestMultipleOfBlocksize += session.getWriteState().getRecordIvLength();
		}
		int paddingLength = smallestMultipleOfBlocksize % ciphertextLength;

		// create padding
		byte[] padding = new byte[paddingLength + 1];
		Arrays.fill(padding, (byte) paddingLength);
		plaintext.writeBytes(padding);
		Cipher blockCipher = CipherManager.getInstance(session.getWriteState().getCipherSuite().getTransformation());
		blockCipher.init(Cipher.ENCRYPT_MODE, session.getWriteState().getEncryptionKey());

		// create GenericBlockCipher structure
		DatagramWriter result = new DatagramWriter();
		result.writeBytes(blockCipher.getIV());
		result.writeBytes(blockCipher.doFinal(plaintext.toByteArray()));
		return result.toByteArray();
	}

	/**
	 * Converts a given TLSCiphertext.fragment to a
	 * TLSCompressed.fragment structure as defined by
	 * <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.2">
	 * RFC 5246, section 6.2.3.2</a>:
	 * 
	 * <pre>
	 * struct {
	 *    opaque IV[SecurityParameters.record_iv_length];
	 *    block-ciphered struct {
	 *       opaque content[TLSCompressed.length];
	 *       opaque MAC[SecurityParameters.mac_length];
	 *       uint8 padding[GenericBlockCipher.padding_length];
	 *       uint8 padding_length;
	 *    };
	 * } GenericBlockCipher;
	 * </pre>
	 * 
	 * The particular cipher to use is determined from the negotiated
	 * cipher suite in the <em>current</em> DTLS connection state.
	 * 
	 * @param ciphertextFragment the TLSCiphertext.fragment
	 * @param currentReadState the encryption parameters to use
	 * @return the TLSCompressed.fragment
	 * @throws NullPointerException if the given ciphertext or encryption params is <code>null</code>
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if the ciphertext could not be decrpyted, e.g.
	 *             because the JVM does not support the negotiated block cipher
	 */
	protected final byte[] decryptBlockCipher(byte[] ciphertextFragment, DTLSConnectionState currentReadState) throws GeneralSecurityException {
		if (currentReadState == null) {
			throw new NullPointerException("Current read state must not be null");
		} else if (ciphertextFragment == null) {
			throw new NullPointerException("Ciphertext must not be null");
		}
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.2 for
		 * explanation
		 */
		DatagramReader reader = new DatagramReader(ciphertextFragment);
		byte[] iv = reader.readBytes(currentReadState.getRecordIvLength());
		Cipher blockCipher = CipherManager.getInstance(currentReadState.getCipherSuite().getTransformation());
		blockCipher.init(Cipher.DECRYPT_MODE,
				currentReadState.getEncryptionKey(),
				new IvParameterSpec(iv));
		byte[] plaintext = blockCipher.doFinal(reader.readBytesLeft());
		// last byte contains padding length
		int paddingLength = plaintext[plaintext.length - 1];
		int fragmentLength = plaintext.length
				- 1 // paddingLength byte
				- paddingLength
				- currentReadState.getCipherSuite().getMacLength();

		reader = new DatagramReader(plaintext);
		byte[] content = reader.readBytes(fragmentLength);			
		byte[] macFromMessage = reader.readBytes(currentReadState.getCipherSuite().getMacLength());
		byte[] mac = getBlockCipherMac(currentReadState, content);
		if (Arrays.equals(macFromMessage, mac)) {
			return content;
		} else {
			throw new InvalidMacException(mac, macFromMessage);
		}
	}

	/**
	 * Calculates a MAC for use with CBC block ciphers as specified
	 * by <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.2">
	 * RFC 5246, section 6.2.3.2</a>.
	 * 
	 * @param conState the security parameters for calculating the MAC
	 * @param content the data to calculate the MAC for
	 * @return the MAC
	 * @throws GeneralSecurityException if the MAC could not be calculated,
	 *           e.g. because the JVM does not support the cipher suite's
	 *           HMac algorithm
	 */
	private byte[] getBlockCipherMac(DTLSConnectionState conState, byte[] content) throws GeneralSecurityException {

		Mac hmac = Mac.getInstance(conState.getCipherSuite().getMacName());
		hmac.init(conState.getMacKey());
		
		DatagramWriter mac = new DatagramWriter();
		mac.writeBytes(generateAdditionalData(content.length));
		mac.writeBytes(content);
		return hmac.doFinal(mac.toByteArray());
	}

	// AEAD Cryptography //////////////////////////////////////////////

	protected byte[] encryptAEAD(byte[] byteArray) throws GeneralSecurityException {
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.3 for
		 * explanation of additional data or
		 * http://tools.ietf.org/html/rfc5116#section-2.1
		 */
		byte[] iv = session.getWriteState().getIv().getIV();
		byte[] nonce = generateNonce(iv);
		byte[] key = session.getWriteState().getEncryptionKey().getEncoded();
		byte[] additionalData = generateAdditionalData(byteArray.length);

		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("encrypt: {} bytes", byteArray.length);
			LOGGER.trace("nonce: {}", StringUtil.byteArray2HexString(nonce, StringUtil.NO_SEPARATOR, 0));
			LOGGER.trace("adata: {}", StringUtil.byteArray2HexString(additionalData, StringUtil.NO_SEPARATOR, 0));
		}
		byte[] encryptedFragment = CCMBlockCipher.encrypt(key, nonce, additionalData, byteArray, 8);

		/*
		 * Prepend the explicit nonce as specified in
		 * http://tools.ietf.org/html/rfc5246#section-6.2.3.3 and
		 * http://tools.ietf.org/html/draft-mcgrew-tls-aes-ccm-04#section-3
		 */
		byte[] explicitNonce = generateExplicitNonce();
		encryptedFragment = ByteArrayUtils.concatenate(explicitNonce, encryptedFragment);
		LOGGER.trace("==> {} bytes", encryptedFragment.length);

		return encryptedFragment;
	}

	/**
	 * Decrypts the given byte array using a AEAD cipher.
	 * 
	 * @param byteArray the ciphertext to be decrypted
	 * @param currentReadState the encryption parameters to use
	 * @return the decrypted message
	 * @throws NullPointerException if the given ciphertext or encryption params is <code>null</code>
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if de-cryption failed
	 */
	protected byte[] decryptAEAD(byte[] byteArray, DTLSConnectionState currentReadState) throws GeneralSecurityException {

		if (currentReadState == null) {
			throw new NullPointerException("Current read state must not be null");
		} else if (byteArray == null) {
			throw new NullPointerException("Ciphertext must not be null");
		}
		// the "implicit" part of the nonce is the salt as exchanged during the session establishment
		byte[] iv = currentReadState.getIv().getIV();
		// the symmetric key exchanged during the DTLS handshake
		byte[] key = currentReadState.getEncryptionKey().getEncoded();
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.3 and
		 * http://tools.ietf.org/html/rfc5116#section-2.1 for an
		 * explanation of "additional data" and its structure
		 * 
		 * The decrypted message is always 16 bytes shorter than the cipher (8
		 * for the authentication tag and 8 for the explicit nonce).
		 */
		byte[] additionalData = generateAdditionalData(byteArray.length - 16);

		DatagramReader reader = new DatagramReader(byteArray);
	
		// create explicit nonce from values provided in DTLS record 
		byte[] explicitNonce = generateExplicitNonce();
		// retrieve actual explicit nonce as contained in GenericAEADCipher struct (8 bytes long)
		byte[] explicitNonceUsed = reader.readBytes(8);
		if (LOGGER.isDebugEnabled() && !Arrays.equals(explicitNonce, explicitNonceUsed)) {
			StringBuilder b = new StringBuilder("The explicit nonce used by the sender does not match the values provided in the DTLS record");
			b.append(StringUtil.lineSeparator()).append("Used    : ").append(ByteArrayUtils.toHexString(explicitNonceUsed));
			b.append(StringUtil.lineSeparator()).append("Expected: ").append(ByteArrayUtils.toHexString(explicitNonce));
			LOGGER.debug(b.toString());
		}

		byte[] nonce = getNonce(iv, explicitNonceUsed);
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("decrypt: {} bytes", byteArray.length - 16);
			LOGGER.trace("nonce: {}", StringUtil.byteArray2HexString(nonce, StringUtil.NO_SEPARATOR, 0));
			LOGGER.trace("adata: {}", StringUtil.byteArray2HexString(additionalData, StringUtil.NO_SEPARATOR, 0));
		}
		return CCMBlockCipher.decrypt(key, nonce, additionalData, reader.readBytesLeft(), 8);
	}

	// Cryptography Helper Methods ////////////////////////////////////

	/**
	 * http://tools.ietf.org/html/draft-mcgrew-tls-aes-ccm-ecc-03#section-2:
	 * 
	 * <pre>
	 * struct {
	 *   case client:
	 *     uint32 client_write_IV;  // low order 32-bits
	 *   case server:
	 *     uint32 server_write_IV;  // low order 32-bits
	 *  uint64 seq_num;
	 * } CCMNonce.
	 * </pre>
	 * 
	 * @param iv
	 *            the write IV (either client or server).
	 * @return the 12 bytes nonce.
	 */
	private byte[] generateNonce(byte[] iv) {
		return getNonce(iv, generateExplicitNonce());
	}

	private byte[] getNonce(byte[] implicitNonce, byte[] explicitNonce) {
		DatagramWriter writer = new DatagramWriter();
		
		writer.writeBytes(implicitNonce);
		writer.writeBytes(explicitNonce);
		
		return writer.toByteArray();
	}

	
	/**
	 * Generates the explicit part of the nonce to be used with the AEAD Cipher.
	 * 
	 * <a href="http://tools.ietf.org/html/rfc6655#section-3">RFC6655, Section 3</a>
	 * encourages the use of the session's 16bit epoch value concatenated
	 * with a monotonically increasing 48bit sequence number as the explicit nonce. 
	 * 
	 * @return the 64-bit explicit nonce constructed from the epoch and sequence number
	 */
	private byte[] generateExplicitNonce() {
		
		//TODO: re-factor to use simple byte array manipulation instead of using bit-based DatagramWriter
		DatagramWriter writer = new DatagramWriter();
		
		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);
		
		return writer.toByteArray();
	}

	/**
	 * See <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.3">RFC
	 * 5246</a>:
	 * 
	 * <pre>
	 * additional_data = seq_num + TLSCompressed.type +
	 * TLSCompressed.version + TLSCompressed.length;
	 * </pre>
	 * 
	 * where "+" denotes concatenation.
	 * 
	 * @return the additional authentication data.
	 */
	private byte[] generateAdditionalData(int length) {
		DatagramWriter writer = new DatagramWriter();
		
		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);

		if (useConnectionId()) {
			writer.write(ContentType.TLS12_CID.getCode(), CONTENT_TYPE_BITS);
		} else {
			writer.write(type.getCode(), CONTENT_TYPE_BITS);
		}
		writer.write(version.getMajor(), VERSION_BITS);
		writer.write(version.getMinor(), VERSION_BITS);
		if (useConnectionId()) {
			writer.writeBytes(connectionId.getBytes());
			writer.write(connectionId.length(), CID_LENGTH_BITS);
		}
		writer.write(length, LENGTH_BITS);

		return writer.toByteArray();
	}

	// Getters and Setters ////////////////////////////////////////////

	/**
	 * Check, if record is CLIENT_HELLO of epoch 0.
	 * 
	 * This is important to detect a new association according RFC6347, section 4.2.8.
	 * 
	 * @return {@code true}, if record contains CLIENT_HELLO of epoch 0,
	 *         {@code false} otherwise.
	 */
	public boolean isNewClientHello() {
		if (0 < epoch || type != ContentType.HANDSHAKE || null == fragmentBytes || 0 == fragmentBytes.length) {
			return false;
		}
		HandshakeType handshakeType = HandshakeType.getTypeByCode(fragmentBytes[0]);
		return handshakeType == HandshakeType.CLIENT_HELLO;
	}

	public ContentType getType() {
		return type;
	}

	public ProtocolVersion getVersion() {
		return version;
	}

	public int getEpoch() {
		return epoch;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * Sets the record's sequence number.
	 * 
	 * This is primarily intended for cases where the record needs to be re-transmitted
	 * with a new sequence number.
	 * 
	 * This method also takes care of re-encrypting the record's message fragment if
	 * a CBC block cipher is used in order to update the ciphertext's MAC which is
	 * parameterized with the record's sequence number.
	 *  
	 * @param sequenceNumber the new sequence number
	 * @throws GeneralSecurityException if the fragment could not be re-encrypted
	 */
	public void setSequenceNumber(long sequenceNumber) throws GeneralSecurityException {
		if (sequenceNumber > MAX_SEQUENCE_NO) {
			throw new IllegalArgumentException("Sequence number must have max 48 bits");
		}
		if (this.sequenceNumber != sequenceNumber) {
			this.sequenceNumber = sequenceNumber;
			if (session != null && session.getWriteState() != null && epoch > 0) {
				byte[] byteArray = fragment.toByteArray();
				byteArray = applyInnerTypeAndPadding(byteArray);
				fragmentLength = byteArray.length;
				fragmentBytes = encryptFragment(byteArray);
			}
		}
	}

	/**
	 * Gets the length of the fragment contained in this record in bytes.
	 * <p>
	 * The overall length of this record's <em>DTLSCiphertext</em>
	 * representation is thus <code>Record.length</code> + 13 (DTLS record headers)
	 * bytes.
	 * 
	 * @return the fragment length excluding record headers
	 */
	public int getFragmentLength() {
		return fragmentLength;
	}

	/**
	 * Set associated session.
	 * 
	 * Take over peer address from session.
	 * 
	 * @param session session to be set
	 */
	public void setSession(DTLSSession session) {
		this.session = session;
		if (session != null && this.peerAddress == null) {
			this.peerAddress = session.getPeer();
		}
	}

	/**
	 * Get peer address.
	 * 
	 * @return peer address
	 * @throws IllegalStateException if peer address is not available
	 */
	public InetSocketAddress getPeerAddress() {
		if (peerAddress != null) {
			return peerAddress;
		} else {
			throw new IllegalStateException("Record does not have a peer address");
		}
	}

	/**
	 * Get connection id.
	 * 
	 * @return connection id
	 */
	public ConnectionId getConnectionId() {
		return connectionId;
	}

	/**
	 * Get fragment payload as byte array.
	 * 
	 * @return fragments byte array.
	 */
	public byte[] getFragmentBytes() {
		return fragmentBytes;
	}

	/**
	 * Gets the object representation of this record's <em>DTLSPlaintext.fragment</em>.
	 *  
	 * If this record only contains the fragment's ciphertext representation, it is
	 * first decrypted and then parsed into a <code>DTLSMessage</code> instance using
	 * the DTLS connection's <em>current</em> read state.
	 * 
	 * @return the plaintext fragment
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if de-cryption fails, e.g. because
	 *             the JVM does not support the negotiated cipher algorithm
	 * @throws HandshakeException if the TLSPlaintext.fragment could not be parsed into
	 *             a valid handshake message
	 */
	public DTLSMessage getFragment() throws GeneralSecurityException, HandshakeException {
		if (session != null) {
			return getFragment(session.getReadState());
		} else {
			return getFragment(null);
		}
	}	

	/**
	 * Gets the object representation of this record's <em>DTLSPlaintext.fragment</em>. 
	 * <p>
	 * If this record only contains the fragment's ciphertext representation, it is
	 * first decrypted and then parsed into a <code>DTLSMessage</code> instance.
	 * 
	 * @param currentReadState the crypto params to use for de-crypting the ciphertext,
	 *           if <code>null</code> the fragment bytes are expected to be plaintext already
	 *           and will be parsed into a <code>DTLSMessage</code> directly
	 * @return the message object
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if de-cryption fails, e.g. because
	 *             the JVM does not support the negotiated cipher algorithm
	 * @throws HandshakeException if the TLSPlaintext.fragment could not be parsed into
	 *             a valid handshake message
	 */
	public DTLSMessage getFragment(final DTLSConnectionState currentReadState) throws GeneralSecurityException, HandshakeException {
		if (fragment == null) {
			ContentType innerType = type;
			// decide, which type of fragment need de-cryption
			byte[] decryptedMessage = decryptFragment(fragmentBytes, currentReadState);

			if (ContentType.TLS12_CID == type) {
				int index = decryptedMessage.length - 1;
				while (index >= 0 && decryptedMessage[index] == 0) {
					--index;
				}
				if (index < 0) {
					throw new IllegalStateException("inner type!");
				}
				int typeCode = decryptedMessage[index];
				innerType =  ContentType.getTypeByValue(typeCode);
				if (innerType == null) {
					LOGGER.debug("Received DTLS record of unsupported inner type [{}]. Discarding {} bytes ...", typeCode, index);
					return null;
				}
				decryptedMessage = Arrays.copyOf(decryptedMessage, index);
				fragmentLength = decryptedMessage.length;
			}

			switch (innerType) {
			case ALERT:
				// http://tools.ietf.org/html/rfc5246#section-7.2:
				// "Like other messages, alert messages are encrypted and
				// compressed, as specified by the current connection state."
				fragment = AlertMessage.fromByteArray(decryptedMessage, getPeerAddress());
				break;

			case APPLICATION_DATA:
				// http://tools.ietf.org/html/rfc5246#section-7.2:
				// "Like other messages, alert messages are encrypted and
				// compressed, as specified by the current connection state."
				fragment = ApplicationMessage.fromByteArray(decryptedMessage, getPeerAddress());
				break;

			case CHANGE_CIPHER_SPEC:
				// http://tools.ietf.org/html/rfc5246#section-7.1:
				// "is encrypted and compressed under the current (not the pending)
				// connection state"
				fragment = ChangeCipherSpecMessage.fromByteArray(decryptedMessage, getPeerAddress());
				break;

			case HANDSHAKE:
				fragment = decryptHandshakeMessage(decryptedMessage);
				break;

			default:
				LOGGER.warn("Cannot decrypt message of unsupported type [{}]", type);
			}
			type = innerType;
		}

		return fragment;
	}

	private DTLSMessage decryptHandshakeMessage(byte[] decryptedMessage) throws GeneralSecurityException, HandshakeException {
		// TODO: it is unclear to me whether handshake messages are encrypted or not
		// http://tools.ietf.org/html/rfc5246#section-7.4:
		// "Handshake messages are supplied to the TLS record layer, where they
		//  are encapsulated within one or more TLSPlaintext structures, which
		//  are processed and transmitted as specified by the current active session state."
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("Decrypting HANDSHAKE message ciphertext{}{}", StringUtil.lineSeparator(),
				ByteArrayUtils.toHexString(fragmentBytes));
		}

		HandshakeParameter parameter = null;
		if (session != null) {
			parameter = session.getParameter();
		} else {
			LOGGER.debug("Parsing message without a session");
		}
		if (LOGGER.isDebugEnabled()) {
			StringBuilder msg = new StringBuilder("Parsing HANDSHAKE message plaintext [{}]");
			if (LOGGER.isTraceEnabled()) {
				msg.append(":").append(StringUtil.lineSeparator()).append(ByteArrayUtils.toHexString(decryptedMessage));
			}
			LOGGER.debug(msg.toString(), parameter);
		}
		return HandshakeMessage.fromByteArray(decryptedMessage, parameter, getPeerAddress());
	}

	/**
	 * Sets the DTLS fragment. At the same time, it creates the corresponding
	 * raw binary representation and encrypts it if necessary (depending on
	 * current connection state).
	 * 
	 * @param fragment the DTLS fragment
	 * @throws GeneralSecurityException if the message could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */
	public void setFragment(DTLSMessage fragment) throws GeneralSecurityException {

		if (fragmentBytes == null) {
			// serialize fragment and if necessary encrypt byte array

			byte[] byteArray = fragment.toByteArray();
			// the current length of the unprotected message
			// this value is needed to generate the additional data when using AEAD
			fragmentLength = byteArray.length;

			switch (type) {
			case ALERT:
			case APPLICATION_DATA:
			case HANDSHAKE:
			case CHANGE_CIPHER_SPEC:
				byteArray = applyInnerTypeAndPadding(byteArray);
				fragmentLength = byteArray.length;
				byteArray = encryptFragment(byteArray);
				break;

			default:
				LOGGER.error("Unknown content type: " + type.toString());
				break;
			}
			this.fragmentBytes = byteArray;

		}
		this.fragment = fragment;
	}

	private final boolean useConnectionId() {
		return connectionId != null && !connectionId.isEmpty();
	}

	private byte[] applyInnerTypeAndPadding(byte[] fragment) {
		if (useConnectionId()) {
			int index = fragment.length;
			fragment = Arrays.copyOf(fragment, index + 1 + padding);
			fragment[index] = (byte) type.getCode();
		}
		return fragment;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("==[ DTLS Record ]==============================================");
		sb.append(StringUtil.lineSeparator()).append("Content Type: ").append(type.toString());
		sb.append(StringUtil.lineSeparator()).append("Peer address: ").append(getPeerAddress());
		sb.append(StringUtil.lineSeparator()).append("Version: ").append(version.getMajor()).append(", ").append(version.getMinor());
		sb.append(StringUtil.lineSeparator()).append("Epoch: ").append(epoch);
		sb.append(StringUtil.lineSeparator()).append("Sequence Number: ").append(sequenceNumber);
		if (connectionId != null) {
			sb.append(StringUtil.lineSeparator()).append("connection id: ").append(connectionId.getAsString());
		}
		sb.append(StringUtil.lineSeparator()).append("Length: ").append(fragmentLength);
		sb.append(StringUtil.lineSeparator()).append("Fragment:");
		if (fragment != null) {
			sb.append(StringUtil.lineSeparator()).append(fragment);
		} else {
			sb.append(StringUtil.lineSeparator()).append("fragment is not decrypted yet");
		}
		sb.append(StringUtil.lineSeparator()).append("===============================================================");

		return sb.toString();
	}

}

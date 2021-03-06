package org.cryptomator.siv;
/*******************************************************************************
 * Copyright (c) 2015 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/

import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.Mac;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.paddings.ISO7816d4Padding;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Implements the RFC 5297 SIV mode.
 */
public final class SivMode {

	private static final byte[] BYTES_ZERO = new byte[16];
	private static final byte DOUBLING_CONST = (byte) 0x87;

	private final ThreadLocal<BlockCipher> threadLocalCipher;

	/**
	 * Creates an AES-SIV instance using JCE's cipher implementation, which should normally be the best choice.<br>
	 * 
	 * For embedded systems, you might want to consider using {@link #SivMode(BlockCipherFactory)} with {@link AESLightEngine} instead.
	 * 
	 * @see #SivMode(BlockCipherFactory)
	 */
	public SivMode() {
		this(new BlockCipherFactory() {

			@Override
			public BlockCipher create() {
				return new JceAesBlockCipher();
			}

		});
	}

	/**
	 * Creates an instance using a specific Blockcipher.get(). If you want to use AES, just use the default constructor.
	 * 
	 * @param cipherFactory A factory method creating a Blockcipher.get(). Must use a block size of 128 bits (16 bytes).
	 */
	public SivMode(final BlockCipherFactory cipherFactory) {
		// Try using cipherFactory to check that the block size is valid.
		// We assume here that the block size will not vary across calls to .create().
		if (cipherFactory.create().getBlockSize() != 16) {
			throw new IllegalArgumentException("cipherFactory must create BlockCipher objects with a 16-byte block size");
		}

		this.threadLocalCipher = new ThreadLocal<BlockCipher>() {

			@Override
			protected BlockCipher initialValue() {
				return cipherFactory.create();
			}

		};
	}

	/**
	 * Creates {@link BlockCipher}s.
	 */
	public static interface BlockCipherFactory {
		BlockCipher create();
	}

	/**
	 * Convenience method, if you are using the javax.crypto API. This is just a wrapper for {@link #encrypt(byte[], byte[], byte[], byte[]...)}.
	 * 
	 * @param ctrKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param macKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param plaintext Your plaintext, which shall be encrypted.
	 * @param associatedData Optional associated data, which gets authenticated but not encrypted.
	 * @return IV + Ciphertext as a concatenated byte array.
	 * @throws IllegalArgumentException if keys are invalid or {@link SecretKey#getEncoded()} is not supported.
	 */
	public byte[] encrypt(SecretKey ctrKey, SecretKey macKey, byte[] plaintext, byte[]... associatedData) {
		final byte[] ctrKeyBytes = ctrKey.getEncoded();
		final byte[] macKeyBytes = macKey.getEncoded();
		if (ctrKeyBytes == null || macKeyBytes == null) {
			throw new IllegalArgumentException("Can't get bytes of given key.");
		}
		try {
			return encrypt(ctrKeyBytes, macKeyBytes, plaintext, associatedData);
		} finally {
			Arrays.fill(ctrKeyBytes, (byte) 0);
			Arrays.fill(macKeyBytes, (byte) 0);
		}
	}

	/**
	 * Encrypts plaintext using SIV mode. A block cipher defined by the constructor is being used.<br>
	 * 
	 * @param ctrKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param macKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param plaintext Your plaintext, which shall be encrypted.
	 * @param associatedData Optional associated data, which gets authenticated but not encrypted.
	 * @return IV + Ciphertext as a concatenated byte array.
	 * @throws IllegalArgumentException if the either of the two keys is of invalid length for the used {@link BlockCipher}.
	 */
	public byte[] encrypt(byte[] ctrKey, byte[] macKey, byte[] plaintext, byte[]... associatedData) {
		// Check if plaintext length will cause overflows
		if (plaintext.length > (Integer.MAX_VALUE - 16)) {
			throw new IllegalArgumentException("Plaintext is too long");
		}

		assert plaintext.length + 15 < Integer.MAX_VALUE;
		final int numBlocks = (plaintext.length + 15) / 16;
		final byte[] iv = s2v(macKey, plaintext, associatedData);
		final byte[] keystream = generateKeyStream(ctrKey, iv, numBlocks);
		final byte[] ciphertext = xor(plaintext, keystream);

		// concat IV + ciphertext:
		final byte[] result = new byte[iv.length + ciphertext.length];
		System.arraycopy(iv, 0, result, 0, iv.length);
		System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
		return result;
	}

	/**
	 * Convenience method, if you are using the javax.crypto API. This is just a wrapper for {@link #decrypt(byte[], byte[], byte[], byte[]...)}.
	 * 
	 * @param ctrKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param macKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param ciphertext Your cipehrtext, which shall be decrypted.
	 * @param associatedData Optional associated data, which needs to be authenticated during decryption.
	 * @return Plaintext byte array.
	 * @throws IllegalArgumentException If keys are invalid or {@link SecretKey#getEncoded()} is not supported.
	 * @throws UnauthenticCiphertextException If the authentication failed, e.g. because ciphertext and/or associatedData are corrupted.
	 * @throws IllegalBlockSizeException If the provided ciphertext is of invalid length.
	 */
	public byte[] decrypt(SecretKey ctrKey, SecretKey macKey, byte[] ciphertext, byte[]... associatedData) throws UnauthenticCiphertextException, IllegalBlockSizeException {
		final byte[] ctrKeyBytes = ctrKey.getEncoded();
		final byte[] macKeyBytes = macKey.getEncoded();
		if (ctrKeyBytes == null || macKeyBytes == null) {
			throw new IllegalArgumentException("Can't get bytes of given key.");
		}
		try {
			return decrypt(ctrKeyBytes, macKeyBytes, ciphertext, associatedData);
		} finally {
			Arrays.fill(ctrKeyBytes, (byte) 0);
			Arrays.fill(macKeyBytes, (byte) 0);
		}
	}

	/**
	 * Decrypts ciphertext using SIV mode. A block cipher defined by the constructor is being used.<br>
	 * 
	 * @param ctrKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param macKey SIV mode requires two separate keys. You can use one long key, which is splitted in half. See https://tools.ietf.org/html/rfc5297#section-2.2
	 * @param ciphertext Your ciphertext, which shall be encrypted.
	 * @param associatedData Optional associated data, which needs to be authenticated during decryption.
	 * @return Plaintext byte array.
	 * @throws IllegalArgumentException If the either of the two keys is of invalid length for the used {@link BlockCipher}.
	 * @throws UnauthenticCiphertextException If the authentication failed, e.g. because ciphertext and/or associatedData are corrupted.
	 * @throws IllegalBlockSizeException If the provided ciphertext is of invalid length.
	 */
	public byte[] decrypt(byte[] ctrKey, byte[] macKey, byte[] ciphertext, byte[]... associatedData) throws UnauthenticCiphertextException, IllegalBlockSizeException {
		if (ciphertext.length < 16) {
			throw new IllegalBlockSizeException("Input length must be greater than or equal 16.");
		}

		final byte[] iv = Arrays.copyOf(ciphertext, 16);
		final byte[] actualCiphertext = Arrays.copyOfRange(ciphertext, 16, ciphertext.length);

		assert actualCiphertext.length == ciphertext.length - 16;
		assert actualCiphertext.length + 15 < Integer.MAX_VALUE;
		final int numBlocks = (actualCiphertext.length + 15) / 16;
		final byte[] keystream = generateKeyStream(ctrKey, iv, numBlocks);
		final byte[] plaintext = xor(actualCiphertext, keystream);
		final byte[] control = s2v(macKey, plaintext, associatedData);

		// time-constant comparison (taken from MessageDigest.isEqual in JDK8)
		assert iv.length == control.length;
		int diff = 0;
		for (int i = 0; i < iv.length; i++) {
			diff |= iv[i] ^ control[i];
		}

		if (diff == 0) {
			return plaintext;
		} else {
			throw new UnauthenticCiphertextException("authentication in SIV decryption failed");
		}
	}

	byte[] generateKeyStream(byte[] ctrKey, byte[] iv, int numBlocks) {
		final byte[] keystream = new byte[numBlocks * 16];

		// clear out the 31st and 63rd (rightmost) bit:
		final byte[] ctr = Arrays.copyOf(iv, 16);
		ctr[8] = (byte) (ctr[8] & 0x7F);
		ctr[12] = (byte) (ctr[12] & 0x7F);
		final ByteBuffer ctrBuf = ByteBuffer.wrap(ctr);
		final long initialCtrVal = ctrBuf.getLong(8);

		final BlockCipher cipher = threadLocalCipher.get();
		cipher.init(true, new KeyParameter(ctrKey));
		for (int i = 0; i < numBlocks; i++) {
			ctrBuf.putLong(8, initialCtrVal + i);
			cipher.processBlock(ctr, 0, keystream, i * 16);
			cipher.reset();
		}

		return keystream;
	}

	// Visible for testing, throws IllegalArgumentException if key is not accepted by CMac#init(CipherParameters)
	byte[] s2v(byte[] macKey, byte[] plaintext, byte[]... associatedData) {
		// Maximum permitted AD length is the block size in bits - 2
		if (associatedData.length > 126) {
			// SIV mode cannot be used safely with this many AD fields
			throw new IllegalArgumentException("too many Associated Data fields");
		}

		final CipherParameters params = new KeyParameter(macKey);
		final CMac mac = new CMac(threadLocalCipher.get());
		mac.init(params);

		byte[] d = mac(mac, BYTES_ZERO);

		for (byte[] s : associatedData) {
			d = xor(dbl(d), mac(mac, s));
		}

		final byte[] t;
		if (plaintext.length >= 16) {
			t = xorend(plaintext, d);
		} else {
			t = xor(dbl(d), pad(plaintext));
		}

		return mac(mac, t);
	}

	private static byte[] mac(Mac mac, byte[] in) {
		byte[] result = new byte[mac.getMacSize()];
		mac.update(in, 0, in.length);
		mac.doFinal(result, 0);
		return result;
	}

	// First bit 1, following bits 0.
	private static byte[] pad(byte[] in) {
		final byte[] result = Arrays.copyOf(in, 16);
		new ISO7816d4Padding().addPadding(result, in.length);
		return result;
	}

	// Code taken from {@link org.bouncycastle.crypto.macs.CMac}
	static int shiftLeft(byte[] block, byte[] output) {
		int i = block.length;
		int bit = 0;
		while (--i >= 0) {
			int b = block[i] & 0xff;
			output[i] = (byte) ((b << 1) | bit);
			bit = (b >>> 7) & 1;
		}
		return bit;
	}

	// Code taken from {@link org.bouncycastle.crypto.macs.CMac}
	static byte[] dbl(byte[] in) {
		byte[] ret = new byte[in.length];
		int carry = shiftLeft(in, ret);
		int xor = 0xff & DOUBLING_CONST;

		/*
		 * NOTE: This construction is an attempt at a constant-time implementation.
		 */
		int mask = (-carry) & 0xff;
		ret[in.length - 1] ^= xor & mask;

		return ret;
	}

	static byte[] xor(byte[] in1, byte[] in2) {
		assert in1.length <= in2.length : "Length of first input must be <= length of second input.";
		final byte[] result = new byte[in1.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = (byte) (in1[i] ^ in2[i]);
		}
		return result;
	}

	static byte[] xorend(byte[] in1, byte[] in2) {
		assert in1.length >= in2.length : "Length of first input must be >= length of second input.";
		final byte[] result = Arrays.copyOf(in1, in1.length);
		final int diff = in1.length - in2.length;
		for (int i = 0; i < in2.length; i++) {
			result[i + diff] = (byte) (result[i + diff] ^ in2[i]);
		}
		return result;
	}

}

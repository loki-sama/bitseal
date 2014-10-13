package org.bitseal.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.bitseal.crypt.AddressGenerator;
import org.bitseal.crypt.CryptProcessor;
import org.bitseal.crypt.KeyConverter;
import org.bitseal.crypt.SigProcessor;
import org.bitseal.data.Address;
import org.bitseal.data.Payload;
import org.bitseal.data.Pubkey;
import org.bitseal.database.AddressProvider;
import org.bitseal.database.PayloadProvider;
import org.bitseal.database.PubkeyProvider;
import org.bitseal.database.PubkeysTable;
import org.bitseal.network.ServerCommunicator;
import org.bitseal.pow.POWProcessor;
import org.bitseal.util.ArrayCopier;
import org.bitseal.util.ByteUtils;
import org.bitseal.util.VarintEncoder;
import org.spongycastle.jce.interfaces.ECPrivateKey;
import org.spongycastle.jce.interfaces.ECPublicKey;

import android.util.Base64;
import android.util.Log;

/**
 * A class which provides various methods used for processing pubkeys within Bitseal. 
 * 
 * @author Jonathan Coe
 */
public class PubkeyProcessor
{
	private static final int MIN_VALID_ADDRESS_VERSION = 1;
	private static final int MAX_VALID_ADDRESS_VERSION = 4;
	
	private static final int MIN_VALID_STREAM_NUMBER = 1;
	private static final int MAX_VALID_STREAM_NUMBER = 1;
	
	private static final int DEFAULT_NONCE_TRIALS_PER_BYTE = 320;
	private static final int DEFAULT_EXTRA_BYTES = 14000;
	
	private static final int EMPTY_SIGNATURE_LENGTH = 0; // Pubkeys of version 2 and below do not have signatures
	private static final byte[] EMPTY_SIGNATURE = new byte[]{0};
	
	private static final String TAG = "PUBKEY_PROCESSOR";
	
	/**
	 * Checks whether a given Pubkey and Bitmessage address are valid for
	 * each other. 
	 * 
	 * @param pubkey - A Pubkey object to be validated
	 * @param addressString - A String containing the Bitmessage address to 
	 * validate the Pubkey against
	 * 
	 * @return A boolean indicating whether or not the Pubkey and address String
	 * are valid for each other
	 */
	public boolean validatePubkey (Pubkey pubkey, String addressString)
	{
		// First check that the given address string is a valid Bitmessage address.
		AddressProcessor addProc = new AddressProcessor();
		boolean addressStringValid = addProc.validateAddress(addressString);
		if (addressStringValid == false)
		{
			Log.i(TAG, "While running PubkeyProcessor.validatePubkey(), it was found that the supplied \n" +
					"address String was NOT a valid Bitmessage address");
			return false;
		}
		
		// Check that the pubkey is valid by using its public signing key, public encryption key, 
		// address version number, and stream number to recreate the address string that it corresponds to.
		// This should match the address string that we started with.
		AddressGenerator addGen = new AddressGenerator();
		String recreatedAddress = addGen.recreateAddressString(pubkey.getAddressVersion(), pubkey.getStreamNumber(),
				pubkey.getPublicSigningKey(), pubkey.getPublicEncryptionKey());
		
		Log.i(TAG, "Recreated address String: " + recreatedAddress);
		boolean recreatedAddressValid = recreatedAddress.equals(addressString);
		if (recreatedAddressValid == false)
		{
			Log.i(TAG, "While running PubkeyProcessor.validatePubkey(), it was found that the recreated address String \n" +
					    "generated using data from the pubkey did not match the original address String. \n" +
						"The original address String was : " + addressString + "\n" +
						"The recreated address String was: " + recreatedAddress);
			return false;
		}
		
		// If this pubkey is of version 2 or above, also check that the signature of the pubkey is valid
		int[] addressNumbers = addProc.decodeAddressNumbers(addressString);
		int addressVersion = addressNumbers[0];
		if (addressVersion > 2)
		{
			// To verify the signature we first have to convert the public signing key from the retrieved pubkey into an ECPublicKey object
			KeyConverter keyConv = new KeyConverter();
			ECPublicKey publicSigningKey = keyConv.reconstructPublicKey(pubkey.getPublicSigningKey());
			
			SigProcessor sigProc = new SigProcessor();
			byte[] signaturePayload = sigProc.createPubkeySignaturePayload(pubkey);
			boolean sigValid = (sigProc.verifySignature(signaturePayload, pubkey.getSignature(), publicSigningKey));
			
			if (sigValid == false)
			{
				Log.i(TAG, "While running PubkeyProcessor.validatePubkey(), it was found that the pubkey's signature was invalid");
				return false;
			}
		}
		
		// If the recreated address String and signature were both valid
		return true;
	}
	
	/**
	 * Takes a String representing a Bitmessage address and uses it to retrieve the Pubkey that
	 * corresponds to that address. <br><br>
	 * 
	 * This method is intended to be used to retrieve the Pubkey of another person 
	 * when we have their address and wish to send them a message.<br><br>
	 * 
	 * Note: If the pubkey cannot be retrieved, this method will return null. 
	 * 
	 * @param addressString - A String containing the Bitmessage address that we wish to retrieve
	 * the pubkey for - e.g. "BM-NBpe4wbtC59sWFKxwaiGGNCb715D6xvY"
	 * 
	 * @return A Pubkey object that represents the pubkey for the supplied address
	 */
	public Pubkey retrievePubkeyByAddressString (String addressString)
	{
		// First, extract the ripe hash from the address String
		byte[] ripeHash = new AddressProcessor().extractRipeHashFromAddress(addressString);
		
		// Now search the application's database to see if the pubkey we need is stored there
		PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
		ArrayList<Pubkey> retrievedPubkeys = pubProv.searchPubkeys(PubkeysTable.COLUMN_RIPE_HASH, Base64.encodeToString(ripeHash, Base64.DEFAULT));
		if (retrievedPubkeys.size() > 1)
		{
			Log.i(TAG, "We seem to have found duplicate pubkeys during the database search. We will use the first one and delete the duplicates.");
			
			for (Pubkey p : retrievedPubkeys)
			{
				if (retrievedPubkeys.indexOf(p) != 0) // Keep the first record and delete all the others
				{
					pubProv.deletePubkey(p);
				}
			}
			
			Pubkey pubkey = retrievedPubkeys.get(0);
			return pubkey;
		}
		else if (retrievedPubkeys.size() == 1)
		{
			Pubkey pubkey = retrievedPubkeys.get(0);
			return pubkey;
		}
		else
		{
			Log.i(TAG, "Unable to find the requested pubkey in the application database. The pubkey will now be requested from a server.");
			
			// Extract the address version from the address string in order to determine whether the pubkey will
			// be encrypted (version 4 and above)
			AddressProcessor addProc = new AddressProcessor();
			int[] decodedAddressValues = addProc.decodeAddressNumbers(addressString);
			int addressVersion = decodedAddressValues[0];
			
			// Retrieve the pubkey from a server
			ServerCommunicator servCom = new ServerCommunicator();
			Pubkey pubkey = null;
			
			if (addressVersion >= 4) // The pubkey will be encrypted
			{
				// Calculate the tag that will be used to request the encrypted pubkey
				byte[] tag = addProc.calculateAddressTag(addressString);
				
				// Retrieve the encrypted pubkey from a server
				pubkey = servCom.requestPubkeyFromServer(addressString, tag, addressVersion);
			}
			else // The pubkey is of version 3 or below, and will therefore not be encrypted
			{
				pubkey = servCom.requestPubkeyFromServer(addressString, ripeHash, addressVersion);
			}
			
			// Save the pubkey to the database and set its ID with the one generated by the database
			long id = pubProv.addPubkey(pubkey);
			pubkey.setId(id);
			
			return pubkey; // If the ServerCommunicator fails to retrieve the Pubkey then it will throw a RuntimeException. This will be passed
						   // up the method call hierarchy and handled. 
		}
	}
		
	/**
	 * Reconstructs a pubkey from its encoded byte[] form, typically
	 * the data received from a server after requesting a pubkey. 
	 * 
	 * @param pubkeyData - A byte[] containing the encoded data for a pubkey
	 * @param addressString - If the pubkey is to be reconstructed is of address
	 * version 4 or above, then a String representing the Bitmessage address
	 * corresponding to the pubkey must be supplied, in order for the encrypted
	 * part of the pubkey to be decrypted. Otherwise, the addressString parameter
	 * will not be used. 
	 * 
	 * @return A Pubkey object constructed from the data provided
	 */
	public Pubkey reconstructPubkey (byte[] pubkeyData, String addressString)
	{
		// Parse the individual fields from the decrypted msg data
		int readPosition = 0;
		
		long powNonce = ByteUtils.bytesToLong((ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 8)));
		readPosition += 8; //The pow nonce should always be 8 bytes in length
		
		long time = ByteUtils.bytesToInt((ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 4)));
		if (time == 0) // Need to check whether 4 or 8 byte time has been used
		{
			time = ByteUtils.bytesToLong((ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 8)));
			readPosition += 8;
		}
		else
		{
			readPosition += 4;
		}
		
		long[] decoded = VarintEncoder.decode(ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 9)); // Take 9 bytes, the maximum length for an encoded var_int
		int addressVersion = (int) decoded[0]; // Get the var_int encoded value
		readPosition += (int) decoded[1]; // Find out how many bytes the var_int was in length and adjust the read position accordingly
		if (addressVersion < MIN_VALID_ADDRESS_VERSION || addressVersion > MAX_VALID_ADDRESS_VERSION)
		{
			throw new RuntimeException("Decoded address version number was invalid. Aborting pubkey decoding. The invalid value was " + addressVersion);
		}
		
		decoded = VarintEncoder.decode(ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 9)); // Take 9 bytes, the maximum length for an encoded var_int
		int streamNumber = (int) decoded[0]; // Get the var_int encoded value
		readPosition += (int) decoded[1]; // Find out how many bytes the var_int was in length and adjust the read position accordingly
		if (streamNumber < MIN_VALID_STREAM_NUMBER || streamNumber > MAX_VALID_STREAM_NUMBER)
		{
			throw new RuntimeException("Decoded stream number was invalid. Aborting pubkey decoding. The invalid value was " + streamNumber);
		}
		
		// Pubkeys of version 4 and above have most of their data encrypted. 
		if (addressVersion >= 4)
		{
			byte[] encryptedData = ArrayCopier.copyOfRange(pubkeyData, readPosition + 32, pubkeyData.length); // Skip over the tag
			
			// Create the ECPrivateKey object that we will use to decrypt encrypted the pubkey data
			AddressProcessor addProc = new AddressProcessor();
			byte[] encryptionKey = addProc.calculateAddressEncryptionKey(addressString);
			KeyConverter keyConv = new KeyConverter();
			ECPrivateKey k = keyConv.calculatePrivateKeyFromDoubleHashKey(encryptionKey);
			
			// Attempt to decrypt the encrypted pubkey data
			CryptProcessor cryptProc = new CryptProcessor();
			pubkeyData = cryptProc.decrypt(encryptedData, k);
			readPosition = 0; // Reset the read position so that we start from the beginning of the decrypted data
		}
		
		int behaviourBitfield = ByteUtils.bytesToInt((ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 4))); 
		readPosition += 4; //The behaviour bitfield should always be 4 bytes in length
		
		byte[] publicSigningKey = ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 64);
		readPosition += 64;
		// Both the public signing and public encryption keys need to have the 0x04 byte which was stripped off for transmission
		// over the wire added back on to them
		byte[] fourByte = new byte[]{4};
		publicSigningKey = ByteUtils.concatenateByteArrays(fourByte, publicSigningKey); 
		
		byte[] publicEncryptionKey = ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 64);
		readPosition += 64;
		publicEncryptionKey = ByteUtils.concatenateByteArrays(fourByte, publicEncryptionKey);
		
		// Set the nonceTrialsPerByte and extraBytes values to their defaults. If the pubkey adrress version is 
		// 3 or greater, we will then set these two values to those specified in the pubkey. Otherwise they remain at
		// their default values.
		int nonceTrialsPerByte = DEFAULT_NONCE_TRIALS_PER_BYTE;
		int extraBytes = DEFAULT_EXTRA_BYTES;
		
		// Set the signature and signature length to some default blank values. Pubkeys of address version 2 and below
		// do not have signatures.
		int signatureLength = EMPTY_SIGNATURE_LENGTH;
		byte[] signature = EMPTY_SIGNATURE;
		
		// Only unencrypted msgs of address version 3 or greater contain
		// values for nonceTrialsPerByte, extraBytes, signatureLength, and
		// signature
		if (addressVersion >= 3)
		{
			decoded = VarintEncoder.decode(ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 9)); // Take 9 bytes, the maximum length for an encoded var_int
			nonceTrialsPerByte = (int) decoded[0]; // Get the var_int encoded value
			readPosition += (int) decoded[1]; // Find out how many bytes the var_int was in length and adjust the read position accordingly
			
			decoded = VarintEncoder.decode(ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 9)); // Take 9 bytes, the maximum length for an encoded var_int
			extraBytes = (int) decoded[0]; // Get the var_int encoded value
			readPosition += (int) decoded[1]; // Find out how many bytes the var_int was in length and adjust the read position accordingly
		
			decoded = VarintEncoder.decode(ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + 9)); // Take 9 bytes, the maximum length for an encoded var_int
			signatureLength = (int) decoded[0]; // Get the var_int encoded value
			readPosition += (int) decoded[1]; // Find out how many bytes the var_int was in length and adjust the read position accordingly
			
			signature = (ArrayCopier.copyOfRange(pubkeyData, readPosition, readPosition + signatureLength));
		}
				
		// Recalculate the ripe hash of this pubkey so that it can be stored in the database
		byte[] ripeHash = new AddressGenerator().calculateRipeHash(publicSigningKey, publicEncryptionKey);

		Pubkey pubkey = new Pubkey();
		pubkey.setBelongsToMe(false);
		pubkey.setRipeHash(ripeHash);
		pubkey.setPOWNonce(powNonce);
		pubkey.setTime(time);
		pubkey.setAddressVersion(addressVersion);
		pubkey.setStreamNumber(streamNumber);
		pubkey.setBehaviourBitfield(behaviourBitfield);
		pubkey.setPublicSigningKey(publicSigningKey);
		pubkey.setPublicEncryptionKey(publicEncryptionKey);
		pubkey.setNonceTrialsPerByte(nonceTrialsPerByte);
		pubkey.setExtraBytes(extraBytes);
		pubkey.setSignatureLength(signatureLength);
		pubkey.setSignature(signature);
		
		return pubkey;
	}
	
	/**
	 * Takes a Pubkey and encodes it into a single byte[], in a way that is compatible
	 * with the way that PyBitmessage does. This payload can then be sent to a server
	 * to be disseminated across the network.  
	 * 
	 * @param pubkey - An Pubkey object containing the pubkey data used to create
	 * the payload.
	 * @param powDone - A boolean value indicating whether or not POW has been done for this pubkey
	 * 
	 * @return A Payload object containing the pubkey payload
	 */
	public Payload constructPubkeyPayload (Pubkey pubkey, boolean doPOW)
	{
		// Construct the pubkey payload
		byte[] payload = null;
		ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
		try
		{
			payloadStream.write(ByteUtils.longToBytes(pubkey.getTime()));
			payloadStream.write(VarintEncoder.encode(pubkey.getAddressVersion())); 
			payloadStream.write(VarintEncoder.encode(pubkey.getStreamNumber())); 
			
			if (pubkey.getAddressVersion() >= 4) // Pubkeys of version 4 and above have most of their data encrypted
			{
				// Combine all the data to be encrypted into a single byte[]
				ByteArrayOutputStream dataToEncryptStream = new ByteArrayOutputStream();
				
				dataToEncryptStream.write(ByteBuffer.allocate(4).putInt(pubkey.getBehaviourBitfield()).array());
				
				// If the public signing and public encryption keys have their leading 0x04 byte in place then we need to remove them
				byte[] publicSigningKey = pubkey.getPublicSigningKey();
				if (publicSigningKey[0] == (byte) 4  && publicSigningKey.length == 65)
				{
					publicSigningKey = ArrayCopier.copyOfRange(publicSigningKey, 1, publicSigningKey.length);
				}
				dataToEncryptStream.write(publicSigningKey);
				
				byte[] publicEncryptionKey = pubkey.getPublicEncryptionKey();
				if (publicEncryptionKey[0] == (byte) 4  && publicEncryptionKey.length == 65)
				{
					publicEncryptionKey = ArrayCopier.copyOfRange(publicEncryptionKey, 1, publicEncryptionKey.length);
				}
				dataToEncryptStream.write(publicEncryptionKey);
				
				dataToEncryptStream.write(VarintEncoder.encode(pubkey.getNonceTrialsPerByte()));
				dataToEncryptStream.write(VarintEncoder.encode(pubkey.getExtraBytes()));
				dataToEncryptStream.write(VarintEncoder.encode(pubkey.getSignatureLength()));
				dataToEncryptStream.write(pubkey.getSignature());
				
				// Create the ECPublicKey object that we will use to encrypt the data. First we will
				// retrieve the Address corresponding to this pubkey, so that we can calculate the encryption
				// key derived from the double hash of the address data.
				AddressProvider addProv = AddressProvider.get(App.getContext());
				Address address = addProv.searchForSingleRecord(pubkey.getCorrespondingAddressId());
				String addressString = address.getAddress();
				AddressProcessor addProc = new AddressProcessor();
				byte[] encryptionKey = addProc.calculateAddressEncryptionKey(addressString);
				KeyConverter keyConv = new KeyConverter();
				ECPublicKey K = keyConv.calculatePublicKeyFromDoubleHashKey(encryptionKey);
				
				// Encrypt the pubkey data
				byte[] dataToEncrypt = dataToEncryptStream.toByteArray();
				CryptProcessor cryptProc = new CryptProcessor();
				byte[] encryptedPayload = cryptProc.encrypt(dataToEncrypt, K);
				
				// Get the tag used to identify the pubkey payload
				byte[] tag = address.getTag();
				
				// Add the tag and the encrypted data to the rest of the pubkey payload
				payloadStream.write(tag);
				payloadStream.write(encryptedPayload);
			}
			
			else // For pubkeys of version 3 and below
			{
				payloadStream.write(ByteBuffer.allocate(4).putInt(pubkey.getBehaviourBitfield()).array());  //The behaviour bitfield should always be 4 bytes in length
				
				// If the public signing and public encryption keys have their leading 0x04 byte in place then we need to remove them
				byte[] publicSigningKey = pubkey.getPublicSigningKey();
				if (publicSigningKey[0] == (byte) 4  && publicSigningKey.length == 65)
				{
					publicSigningKey = ArrayCopier.copyOfRange(publicSigningKey, 1, publicSigningKey.length);
				}
				payloadStream.write(publicSigningKey);
				
				byte[] publicEncryptionKey = pubkey.getPublicEncryptionKey();
				if (publicEncryptionKey[0] == (byte) 4  && publicEncryptionKey.length == 65)
				{
					publicEncryptionKey = ArrayCopier.copyOfRange(publicEncryptionKey, 1, publicEncryptionKey.length);
				}
				payloadStream.write(publicEncryptionKey);
				
				payloadStream.write(VarintEncoder.encode(pubkey.getNonceTrialsPerByte())); 
				payloadStream.write(VarintEncoder.encode(pubkey.getExtraBytes())); ;
				payloadStream.write(VarintEncoder.encode(pubkey.getSignatureLength()));
				payloadStream.write(pubkey.getSignature());
			}
			
			payload = payloadStream.toByteArray();
		} 
		catch (IOException e)
		{
			throw new RuntimeException("IOException occurred in PubkeyProcessor.constructPubkeyPayloadForDissemination()", e);
		}
		
		if (doPOW == true)
		{
			long powNonce = new POWProcessor().doPOW(payload, POWProcessor.NETWORK_NONCE_TRIALS_PER_BYTE, POWProcessor.NETWORK_EXTRA_BYTES);
			payload = ByteUtils.concatenateByteArrays(ByteUtils.longToBytes(powNonce), payload);
		}
		
		// Create a new Payload object to hold the payload data
		Payload pubkeyPayload = new Payload();
		pubkeyPayload.setRelatedAddressId(pubkey.getCorrespondingAddressId());
		pubkeyPayload.setBelongsToMe(true);
		pubkeyPayload.setPOWDone(doPOW);
		pubkeyPayload.setType(Payload.OBJECT_TYPE_PUBKEY);
		pubkeyPayload.setPayload(payload);
		
		// Save the Payload object to the database
		PayloadProvider payProv = PayloadProvider.get(App.getContext());
		long pubkeyPayloadID = payProv.addPayload(pubkeyPayload);
		
		// Finally, set the pubkey payload's ID to the one generated by the database
		pubkeyPayload.setId(pubkeyPayloadID);
		
		return pubkeyPayload;
	}
}
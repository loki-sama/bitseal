package org.bitseal.pow;

import java.math.BigInteger;
import java.text.NumberFormat;

import org.bitseal.crypt.SHA512;
import org.bitseal.util.ByteUtils;
import org.bitseal.util.TimeUtils;

import android.util.Log;

/**
 * Offers various methods relating to Proof of Work calculations.<br><br>
 * 
 * Updated for version 3 of the Bitmessage protocol.<br><br> 
 * See: https://bitmessage.org/wiki/Protocol_specification_v3
 * 
 * @author Jonathan Coe
 */
public class POWProcessor
{
	private static final String TAG = "POW_PROCESSOR";
	
	/** In Bitmessage protocol version 3, the network standard value for nonce trials per byte is 1000. */
	public static final long NETWORK_NONCE_TRIALS_PER_BYTE = 1000;
	/** In Bitmessage protocol version 3, the network standard value for extra bytes is 1000. */
	public static final long NETWORK_EXTRA_BYTES = 1000;
		
	/** The minimum 'time to live' value to use when checking if a given payload's POW is sufficient */
	private static final int MINIMUM_TIME_TO_LIVE_VALUE = 300;
	
//	/**
//	 * For testing, use this version of the doPOW method to avoid waiting for POW
//	 * to be calculated.
//	 * 
//	 * @param payload - A byte[] containing the payload to do the POW for.
//	 * @param expirationTime - The expiration time for this payload
//	 * @param nonceTrialsPerByte - The nonceTrialsPerByte value to use
//	 * @param extraBytes - The extraBytes value to use
//	 * 
//	 * @return A random long that can act as a placeholder for a POW nonce
//	 */
//	public long doPOW(byte[] payload, long expirationTime, long nonceTrialsPerByte, long extraBytes)
//	{	
//		byte[] fakePOWNonce = new byte[8];
//		new SecureRandom().nextBytes(fakePOWNonce);
//		return ByteUtils.bytesToLong(fakePOWNonce);
//	}
	
	/**
	 * Does the POW for the given payload.<br />
	 * <b>WARNING: Takes a long time!!!</b>
	 * 
	 * @param payload - A byte[] containing the payload to do the POW for.
	 * @param expirationTime - The expiration time for this payload
	 * @param nonceTrialsPerByte - The nonceTrialsPerByte value to use
	 * @param extraBytes - The extraBytes value to use
	 * 
	 * @return A long containing the calculated POW nonce. 
	 */
	public long doPOW(byte[] payload, long expirationTime, long nonceTrialsPerByte, long extraBytes) 
	{
		long timeToLive = calculateTimeToLiveValue(expirationTime);
		
		POWCalculator powCalc = new POWCalculator();
		long powTarget = calculatePOWTarget(payload.length, nonceTrialsPerByte, extraBytes, timeToLive);
		powCalc.setTarget(powTarget);
		powCalc.setInitialHash(SHA512.sha512(payload));
		
		Log.d(TAG, "Doing POW calculations for a payload.\n" +
				"Payload length        : " + NumberFormat.getIntegerInstance().format(payload.length) + " bytes\n" +
				"Nonce trials per byte : " + NumberFormat.getIntegerInstance().format(nonceTrialsPerByte) + "\n" +
				"Extra bytes           : " + NumberFormat.getIntegerInstance().format(extraBytes) + "\n" +
				"Time to live          : " + TimeUtils.getTimeMessage(timeToLive) + "\n" +
				"Target                : " + NumberFormat.getIntegerInstance().format(powTarget));
		
		return powCalc.execute();
	}
	
	/**
	 * Checks whether the proof of work done for a given payload is sufficient.
	 * 
	 * @param payload - A byte[] containing the payload.
	 * @param nonce - A long containing the POW nonce.
	 * @param expirationTime - The expiration time for this payload
	 * @param nonceTrialsPerByte - The nonceTrialsPerByte value to use
	 * @param extraBytes - The extraBytes value to use
	 * 
	 * @return A boolean value indicating whether or not the POW is sufficient. 
	 */
	public boolean checkPOW(byte[] payload, long nonce, long expirationTime, long nonceTrialsPerByte, long extraBytes) 
	{		
		byte[] initialHash = SHA512.sha512(payload);
		byte[] dataToHash = ByteUtils.concatenateByteArrays(ByteUtils.longToBytes(nonce), initialHash);
		byte[] hash = SHA512.doubleHash(dataToHash);
		
		long timeToLive = calculateTimeToLiveValue(expirationTime);
		
		long value = ByteUtils.bytesToLong(hash);
		long target = calculatePOWTarget(payload.length, nonceTrialsPerByte, extraBytes, timeToLive);
		
		return value >= 0 && target >= value;
	}
	
	/**
	 * Calculates the 'time to live' value for a given expiration time value
	 * 
	 * @param expirationTime - The expiration time for this payload
	 * 
	 * @return The calculated 'time to live' value'
	 */
	private long calculateTimeToLiveValue(long expirationTime)
	{
		// Calculate the 'time to live' value for this payload
		long currentTime = (System.currentTimeMillis() / 1000);
		long timeToLive = expirationTime - currentTime;
		
		if (timeToLive < MINIMUM_TIME_TO_LIVE_VALUE)
		{
			timeToLive = MINIMUM_TIME_TO_LIVE_VALUE;
		}
		
		return timeToLive;
	}
	
	/**
	 * Returns the POW target for a payload of the given length.
	 * 
	 * @param length - The message length.
	 * @param nonceTrialsPerByte - The nonceTrialsPerByte value to use
	 * @param extraBytes - The extraBytes value to use
	 * @param timeToLive - The 'time to live' value to use
	 * 
	 * @return An int representing the POW target for a message with the given length.
	 */
	private long calculatePOWTarget(int length, long nonceTrialsPerByte, long extraBytes, long timeToLive)
	{
		BigInteger powTarget = BigInteger.valueOf(2);
		powTarget = powTarget.pow(64);
		
		BigInteger lengthValue = BigInteger.valueOf(length + extraBytes);
		
		long tempTimeValue = length + extraBytes;
		tempTimeValue = tempTimeValue * timeToLive;
		BigInteger timeValue = BigInteger.valueOf(tempTimeValue);
		
		BigInteger timeTarget = BigInteger.valueOf(2);
		timeTarget = timeTarget.pow(16);
		timeValue = timeValue.divide(timeTarget);
		
		BigInteger divisorValue = lengthValue.add(timeValue);
		divisorValue = divisorValue.multiply(BigInteger.valueOf(nonceTrialsPerByte));
		
		powTarget = powTarget.divide(divisorValue);
		
		// Note that we are dividing through at least 8, so that the value is
		// smaller than 2^61 and fits perfectly into a long.
		return powTarget.longValue();
	}
}